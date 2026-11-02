package com.routescout.mobile.diagnostics

data class DiagnosticSnapshot(
    val internetAvailable: Boolean? = null,
    val dnsLatencyMs: Long? = null,
    val httpsLatencyMs: Long? = null,
    val checkedAt: String? = null,
    val error: String? = null
) {
    val hasResults: Boolean
        get() = checkedAt != null
}
