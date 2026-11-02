package com.routescout.mobile.vpn

/**
 * Uses the upstream directory score first, then its published ping and throughput.
 * These are VPN Gate directory measurements, not measurements performed by this device.
 */
object ServerSelectionEngine {
    fun bestServer(
        servers: List<FreeVpnServer>,
        countryCode: String? = null
    ): FreeVpnServer? {
        val candidates = servers.asSequence()
            .filter { it.openVpnConfigBase64.isNotBlank() }
            .filter { countryCode == null || it.countryCode.equals(countryCode, ignoreCase = true) }
            .sortedWith(
                compareByDescending<FreeVpnServer> { it.score }
                    .thenBy { it.pingMs ?: Int.MAX_VALUE }
                    .thenByDescending { it.speedBitsPerSecond ?: 0L }
                    .thenByDescending { it.uptimeSeconds }
            )
        return candidates.firstOrNull()
    }
}
