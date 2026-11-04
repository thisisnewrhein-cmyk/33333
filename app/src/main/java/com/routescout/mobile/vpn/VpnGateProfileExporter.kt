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
        val encodedProfile = server.openVpnConfigBase64
        if (encodedProfile.isBlank() || encodedProfile.length > MAX_ENCODED_PROFILE_CHARS) {
            throw IOException("The OpenVPN profile is empty or exceeds the supported size limit")
        }

        val decoded = try {
            Base64.getMimeDecoder().decode(encodedProfile)
        } catch (_: IllegalArgumentException) {
            throw IOException("The server returned an invalid OpenVPN profile")
        }

        if (decoded.size > MAX_DECODED_PROFILE_BYTES) {
            throw IOException("The OpenVPN profile exceeds the supported size limit")
        }

        val source = String(decoded, Charsets.UTF_8)
        val safeConfig = OpenVpnProfileSanitizer.sanitize(source)
        val directory = File(context.cacheDir, "vpn-profiles")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create temporary profile directory")
        }

        cleanupExpiredProfiles(directory)

        val country = server.countryCode
            .uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' }
            .take(2)
            .ifBlank { "XX" }
        val address = server.ipAddress
            .replace(Regex("[^A-Za-z0-9-]"), "-")
            .take(64)
            .ifBlank { "unknown" }
        val file = File.createTempFile("RouteScout_${country}_${address}_", ".ovpn", directory)

        try {
            file.writeText(safeConfig, Charsets.UTF_8)
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (error: Exception) {
            file.delete()
            throw IOException("Unable to export the sanitized OpenVPN profile", error)
        }
    }

    private fun cleanupExpiredProfiles(directory: File) {
        val expiration = System.currentTimeMillis() - PROFILE_RETENTION_MS
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("RouteScout_") && it.lastModified() < expiration }
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_DECODED_PROFILE_BYTES = 256 * 1024
        const val MAX_ENCODED_PROFILE_CHARS = 384 * 1024
        const val PROFILE_RETENTION_MS = 24L * 60L * 60L * 1000L
    }
}
