package com.routescout.mobile.diagnostics

enum class SiteProbeFailure { NONE, DNS, HTTPS }

data class SiteConnectivityResult(
    val label: String,
    val host: String,
    val dnsLatencyMs: Long? = null,
    val httpsLatencyMs: Long? = null,
    val httpStatusCode: Int? = null,
    val failure: SiteProbeFailure = SiteProbeFailure.NONE,
    val error: String? = null
) {
    val isReachable: Boolean
        get() = failure == SiteProbeFailure.NONE && httpsLatencyMs != null
}

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
    val ipSourcesAgree: Boolean? = null,
    val siteChecks: List<SiteConnectivityResult> = emptyList(),
    val siteAccessSummary: String? = null,
    val filteringSuspected: Boolean? = null
) {
    val hasResults: Boolean
        get() = checkedAt != null
}
