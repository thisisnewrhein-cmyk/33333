package com.routescout.mobile.vpn

/**
 * Public metadata returned by the official VPN Gate directory.
 *
 * The Base64 OpenVPN profile may be kept in the app's private local cache;
 * it is never written to the repository or included in diagnostic logs.
 */
data class FreeVpnServer(
    val hostName: String,
    val ipAddress: String,
    val score: Long,
    val pingMs: Int?,
    val speedBitsPerSecond: Long?,
    val countryName: String,
    val countryCode: String,
    val activeSessions: Long,
    val uptimeSeconds: Long,
    val totalUsers: Long,
    val totalTrafficBytes: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val openVpnConfigBase64: String
) {
    val key: String
        get() = "$hostName|$ipAddress"
}
