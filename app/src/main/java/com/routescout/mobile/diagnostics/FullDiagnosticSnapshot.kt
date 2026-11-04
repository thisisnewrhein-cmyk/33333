// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.diagnostics

data class HttpsProbeSample(
    val target: String,
    val attempt: Int,
    val latencyMs: Long?,
    val failureClass: String? = null
)

data class DiagnosticFinding(
    val code: String,
    val severity: String,
    val confidence: String,
    val message: String
)

data class FullDiagnosticSnapshot(
    val quick: DiagnosticSnapshot,
    val networkType: String?,
    val networkValidated: Boolean?,
    val networkMetered: Boolean?,
    val captivePortal: Boolean?,
    val vpnTransportActive: Boolean,
    val dnsServers: List<String>,
    val httpsSamples: List<HttpsProbeSample>,
    val httpsFailureRatePercent: Double?,
    val httpsLatencyVariationMs: Long?,
    val findings: List<DiagnosticFinding>,
    val checkedAtEpochMillis: Long
)

object DiagnosticAnalyzer {
    fun latencyVariation(samples: List<HttpsProbeSample>): Long? {
        val values = samples.mapNotNull { it.latencyMs }
        if (values.size < 2) return null
        val differences = values.zipWithNext { left, right ->
            kotlin.math.abs(right - left)
        }
        return differences.average().toLong()
    }

    fun failureRate(samples: List<HttpsProbeSample>): Double? {
        if (samples.isEmpty()) return null
        val failures = samples.count { it.latencyMs == null }
        return failures.toDouble() * 100.0 / samples.size.toDouble()
    }

    fun findings(
        quick: DiagnosticSnapshot,
        vpnTransportActive: Boolean,
        httpsFailureRatePercent: Double?
    ): List<DiagnosticFinding> {
        val result = mutableListOf<DiagnosticFinding>()

        if (quick.internetAvailable == false) {
            result += DiagnosticFinding(
                "ANDROID_NETWORK_NOT_VALIDATED", "ERROR", "HIGH",
                "Android не подтвердил доступ к интернету для выбранной сети."
            )
        }
        if (quick.dnsLatencyMs == null) {
            result += DiagnosticFinding(
                "DNS_LOOKUP_FAILED", "ERROR", "HIGH",
                "Не удалось разрешить контрольное доменное имя через системный DNS."
            )
        }
        if (quick.dnsLatencyMs != null && quick.httpsLatencyMs == null) {
            result += DiagnosticFinding(
                "HTTPS_REACHABILITY_FAILED", "WARNING", "MEDIUM",
                "DNS работает, но HTTPS-проверка контрольного сайта не завершилась."
            )
        }
        if (quick.filteringSuspected == true) {
            result += DiagnosticFinding(
                "DESTINATION_SPECIFIC_FAILURES", "WARNING", "LOW",
                "Часть целевых сайтов недоступна при доступном контрольном сайте. Это не доказывает блокировку оператором."
            )
        }
        if (quick.ipSourcesAgree == false) {
            result += DiagnosticFinding(
                "PUBLIC_IP_SOURCES_DISAGREE", "WARNING", "MEDIUM",
                "Независимые сервисы определения публичного IP вернули разные адреса."
            )
        }
        if (vpnTransportActive && quick.httpsLatencyMs != null) {
            result += DiagnosticFinding(
                "VPN_DATA_PATH_RESPONDED", "INFO", "HIGH",
                "HTTPS-запрос, привязанный к Android VPN Network, получил ответ."
            )
        }
        if (httpsFailureRatePercent != null && httpsFailureRatePercent > 0.0) {
            result += DiagnosticFinding(
                "HTTPS_PROBE_FAILURES", "WARNING", "MEDIUM",
                "Некоторые повторные HTTPS-проверки завершились ошибкой или тайм-аутом. Это не ICMP packet loss."
            )
        }
        result += DiagnosticFinding(
            "DNS_LEAK_NOT_FULLY_PROVEN", "INFO", "HIGH",
            "Список DNS-серверов Android сам по себе не доказывает отсутствие DNS-утечки. Для окончательного вывода нужен внешний DNS leak test."
        )
        if (result.isEmpty()) {
            result += DiagnosticFinding(
                "BASIC_CONNECTIVITY_OK", "INFO", "HIGH",
                "Базовые DNS и HTTPS проверки завершились успешно."
            )
        }
        return result
    }
}
