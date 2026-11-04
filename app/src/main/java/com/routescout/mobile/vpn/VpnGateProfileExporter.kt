package com.routescout.mobile.vpn

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Base64

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
        val safeConfig = OpenVpnProfileSanitizer.sanitize(source)
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
}
