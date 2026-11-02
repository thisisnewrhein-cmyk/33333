package com.routescout.mobile.vpn

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.Locale

class VpnGateProfileExporter(
    private val context: Context
) {
    fun export(server: FreeVpnServer): Uri {
        val decoded = try {
            Base64.getMimeDecoder().decode(server.openVpnConfigBase64)
        } catch (_: IllegalArgumentException) {
            throw IOException("The server returned an invalid OpenVPN profile")
        }

        val source = String(decoded, Charsets.UTF_8)
        val safeConfig = sanitize(source)
        val directory = File(context.cacheDir, "vpn-profiles")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create temporary profile directory")
        }

        val country = server.countryCode.filter { it.isLetter() }.take(2).ifBlank { "XX" }
        val address = server.ipAddress.replace(Regex("[^A-Za-z0-9-]"), "-")
        val file = File(directory, "RouteScout_${country}_${address}.ovpn")
        file.writeText(safeConfig, Charsets.UTF_8)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * OpenVPN profiles are untrusted input. Preserve ordinary connection directives
     * and inline certificates, but reject external key/certificate files and remove
     * local script/plugin/management directives before sharing the profile.
     */
    private fun sanitize(source: String): String {
        val output = StringBuilder()
        val inlineBlocks = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "pkcs12", "crl-verify")
        val fileDirectives = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "pkcs12", "crl-verify")
        val forbiddenDirectives = setOf(
            "up", "down", "route-up", "route-pre-down", "ipchange", "plugin",
            "management", "management-client", "management-hold", "management-query-passwords",
            "daemon", "log", "log-append", "status", "writepid", "cd", "chroot",
            "config", "include", "script-security", "client-connect", "client-disconnect",
            "learn-address", "tls-verify", "auth-user-pass-verify"
        )

        var activeBlock: String? = null
        var hasClient = false
        var hasRemote = false
        var hasAuthUserPass = false

        source.lineSequence().forEach { line ->
            val trimmed = line.trim()

            if (activeBlock != null) {
                output.appendLine(line)
                if (trimmed.equals("</$activeBlock>", ignoreCase = true)) activeBlock = null
                return@forEach
            }

            val openingBlock = inlineBlocks.firstOrNull { trimmed.equals("<$it>", ignoreCase = true) }
            if (openingBlock != null) {
                activeBlock = openingBlock
                output.appendLine(line)
                return@forEach
            }

            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                output.appendLine(line)
                return@forEach
            }

            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val directive = parts.first().lowercase(Locale.ROOT)

            if (directive in forbiddenDirectives) return@forEach

            if (directive in fileDirectives) {
                throw IOException("The OpenVPN profile references an external '$directive' file")
            }

            if (directive == "auth-user-pass") {
                hasAuthUserPass = true
                output.appendLine("auth-user-pass")
                return@forEach
            }

            if (directive == "client") hasClient = true
            if (directive == "remote") {
                val remoteParts = trimmed.split(Regex("\\s+"))
                if (remoteParts.size >= 3 && remoteParts[2].toIntOrNull() != null) hasRemote = true
            }

            output.appendLine(line)
        }

        if (activeBlock != null) throw IOException("The OpenVPN profile contains an unfinished inline block")
        if (!hasClient || !hasRemote) throw IOException("The OpenVPN profile is missing a client or remote directive")
        if (!hasAuthUserPass) output.appendLine("auth-user-pass")

        return output.toString().trimEnd() + "\n"
    }
}
