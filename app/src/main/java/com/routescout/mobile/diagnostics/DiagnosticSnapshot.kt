package com.routescout.mobile.diagnostics

data class DiagnosticSnapshot(
    val internetAvailable: Boolean? = null,
    val dnsLatencyMs: Long? = null,
    val httpsLatencyMs: Long? = null,
    val checkedAt: String? = null,
    val error: String? = null,
    val publicIp: String? = null,
    val publicIpv4: String? = null,
    val publicIpv6: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
    val asn: String? = null,
    val ipSourcesAgree: Boolean? = null
) {
    val hasResults: Boolean
        get() = checkedAt != null
}
