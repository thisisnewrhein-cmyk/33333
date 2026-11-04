package com.routescout.mobile.vpn

import java.io.IOException
import java.util.Locale

/**
 * Sanitizes untrusted VPN Gate OpenVPN profiles before they are handed to a client.
 * This is not a complete OpenVPN parser; unknown ordinary directives are preserved.
 */
internal object OpenVpnProfileSanitizer {
    private val inlineBlocks = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "pkcs12", "crl-verify"
    )

    private val fileDirectives = setOf(
        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "pkcs12", "crl-verify"
    )

    private val forbiddenDirectives = setOf(
        "up", "down", "route-up", "route-pre-down", "ipchange", "plugin",
        "management", "management-client", "management-hold", "management-query-passwords",
        "daemon", "log", "log-append", "status", "writepid", "cd", "chroot",
        "config", "include", "script-security", "client-connect", "client-disconnect",
        "learn-address", "tls-verify", "auth-user-pass-verify"
    )

    fun sanitize(source: String): String {
        val output = StringBuilder()
        var activeBlock: String? = null
        var hasClient = false
        var hasRemote = false
        var hasAuthUserPass = false
        var hasInlineCaContent = false
        var hasPeerFingerprint = false
        var hasRemoteCertTlsServer = false

        source.lineSequence().forEach { line ->
            val trimmed = line.trim()

            if (activeBlock != null) {
                output.appendLine(line)
                if (
                    activeBlock == "ca" &&
                    trimmed.isNotBlank() &&
                    !trimmed.equals("</ca>", ignoreCase = true) &&
                    !trimmed.startsWith("#") &&
                    !trimmed.startsWith(";")
                ) {
                    hasInlineCaContent = true
                }
                if (trimmed.equals("</$activeBlock>", ignoreCase = true)) {
                    activeBlock = null
                }
                return@forEach
            }

            val openingBlock = inlineBlocks.firstOrNull {
                trimmed.equals("<$it>", ignoreCase = true)
            }
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
                // Never retain a local path from an untrusted profile.
                output.appendLine("auth-user-pass")
                return@forEach
            }

            if (directive == "client") hasClient = true

            if (directive == "remote") {
                val remoteParts = trimmed.split(Regex("\\s+"))
                val port = remoteParts.getOrNull(2)?.toIntOrNull()
                if (remoteParts.size >= 3 && port != null && port in 1..65535) {
                    hasRemote = true
                }
            }

            if (directive == "peer-fingerprint") {
                hasPeerFingerprint = !parts.getOrNull(1).isNullOrBlank()
            }

            if (directive == "remote-cert-tls") {
                val role = parts.getOrNull(1)
                    ?.substringBefore("#")
                    ?.substringBefore(";")
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                if (role != "server") {
                    throw IOException("The OpenVPN profile must verify a remote TLS server certificate")
                }
                hasRemoteCertTlsServer = true
            }

            output.appendLine(line)
        }

        if (activeBlock != null) {
            throw IOException("The OpenVPN profile contains an unfinished inline block")
        }
        if (!hasClient || !hasRemote) {
            throw IOException("The OpenVPN profile is missing a valid client or remote endpoint")
        }
        if (!hasInlineCaContent && !hasPeerFingerprint) {
            throw IOException("The OpenVPN profile has no inline CA certificate or peer fingerprint")
        }

        // Prevent the missing-certificate-verification warning shown by OpenVPN.
        if (!hasRemoteCertTlsServer) output.appendLine("remote-cert-tls server")
        if (!hasAuthUserPass) output.appendLine("auth-user-pass")

        return output.toString().trimEnd() + "\n"
    }
}
