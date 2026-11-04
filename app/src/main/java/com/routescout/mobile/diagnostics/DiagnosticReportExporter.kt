// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.routescout.mobile.vpn.VpnEngineStatus
import com.routescout.mobile.vpn.VpnEngineId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticReportPrivacy {
    fun maskIp(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.contains(':')) {
            val groups = value.split(':').filter { it.isNotBlank() }
            return if (groups.isEmpty()) "IPv6 (masked)" else groups.take(3).joinToString(":") + ":…"
        }
        val parts = value.split('.')
        return if (parts.size == 4) parts.take(3).joinToString(".") + ".x" else "IP (masked)"
    }

    fun safeFailureCode(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val value = reason.lowercase()
        return when {
            "handshake" in value -> "HANDSHAKE_UNVERIFIED"
            "permission" in value -> "VPN_PERMISSION"
            "interface" in value -> "VPN_INTERFACE"
            "timeout" in value -> "TIMEOUT"
            "dns" in value -> "DNS_FAILURE"
            "https" in value || "traffic" in value || "data_path" in value -> "DATA_PATH_UNVERIFIED"
            else -> "ENGINE_ERROR"
        }
    }
}

/**
 * Builds a shareable JSON report. Profile contents, private keys, passwords, UUIDs,
 * tokens, SSIDs, BSSIDs, serial numbers and Android IDs are never exported.
 */
class DiagnosticReportExporter(context: Context) {
    private val appContext = context.applicationContext

    fun createReport(
        full: FullDiagnosticSnapshot,
        vpnStatus: VpnEngineStatus,
        profileEngine: String,
        profileEndpoint: String?,
        profileImported: Boolean,
        includeSensitiveNetworkData: Boolean
    ): File {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val root = JSONObject()
            .put("report_schema_version", 1)
            .put("generated_at_utc", isoTimestamp(full.checkedAtEpochMillis))
            .put("app", JSONObject()
                .put("package", appContext.packageName)
                .put("version_name", packageInfo.versionName ?: "unknown")
                .put("version_code", packageInfo.versionCode)
            )
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_release", Build.VERSION.RELEASE)
                .put("android_sdk", Build.VERSION.SDK_INT)
            )
            .put("privacy", JSONObject()
                .put("include_exact_network_data", includeSensitiveNetworkData)
                .put("profile_secrets_included", false)
                .put("ssid_bssid_location_or_device_ids_included", false)
            )
            .put("profile", JSONObject()
                .put("imported", profileImported)
                .put("engine", profileEngine)
                .put(
                    "endpoint",
                    if (includeSensitiveNetworkData) profileEndpoint else null
                )
            )
            .put("vpn", JSONObject()
                .put("engine", vpnStatus.engineId?.name)
                .put("running", vpnStatus.isRunning)
                .put("verified", vpnStatus.isConnected)
                .put("traffic_counters_available", vpnStatus.engineId == VpnEngineId.WIREGUARD)
                .put("received_bytes", if (vpnStatus.engineId == VpnEngineId.WIREGUARD) vpnStatus.receivedBytes else null)
                .put("transmitted_bytes", if (vpnStatus.engineId == VpnEngineId.WIREGUARD) vpnStatus.transmittedBytes else null)
                .put(
                    "latest_handshake_age_ms",
                    vpnStatus.latestHandshakeEpochMillis?.let {
                        (System.currentTimeMillis() - it).coerceAtLeast(0L)
                    }
                )
                .put("failure_code", DiagnosticReportPrivacy.safeFailureCode(vpnStatus.failureReason))
            )
            .put("network", JSONObject()
                .put("type", full.networkType)
                .put("validated", full.networkValidated)
                .put("metered", full.networkMetered)
                .put("captive_portal", full.captivePortal)
                .put("vpn_transport_active", full.vpnTransportActive)
                .put(
                    "dns_servers",
                    JSONArray(full.dnsServers.map { address ->
                        if (includeSensitiveNetworkData) address
                        else DiagnosticReportPrivacy.maskIp(address)
                    })
                )
            )
            .put("quick_check", JSONObject()
                .put("internet_available", full.quick.internetAvailable)
                .put("dns_latency_ms", full.quick.dnsLatencyMs)
                .put("https_latency_ms", full.quick.httpsLatencyMs)
                .put(
                    "public_ip",
                    if (includeSensitiveNetworkData) full.quick.publicIp
                    else DiagnosticReportPrivacy.maskIp(full.quick.publicIp)
                )
                .put(
                    "public_ipv4",
                    if (includeSensitiveNetworkData) full.quick.publicIpv4
                    else DiagnosticReportPrivacy.maskIp(full.quick.publicIpv4)
                )
                .put(
                    "public_ipv6",
                    if (includeSensitiveNetworkData) full.quick.publicIpv6
                    else DiagnosticReportPrivacy.maskIp(full.quick.publicIpv6)
                )
                .put("country_code", full.quick.countryCode)
                .put("asn", full.quick.asn)
                .put("ip_sources_agree", full.quick.ipSourcesAgree)
                .put("site_access_summary", full.quick.siteAccessSummary)
                .put("filtering_suspected_heuristic", full.quick.filteringSuspected)
            )
            .put("https_probe", JSONObject()
                .put("attempt_count", full.httpsSamples.size)
                .put("failure_rate_percent", full.httpsFailureRatePercent)
                .put("latency_variation_ms", full.httpsLatencyVariationMs)
                .put("measurement", "Repeated HTTPS response timing; not ICMP packet loss or a formal speed test")
            )
            .put("limitations", JSONArray()
                .put("DNS server addresses and successful lookups do not independently prove absence of DNS leaks.")
                .put("HTTPS probe failures are not ICMP packet loss.")
                .put("A matching public IP before and after VPN is not, by itself, proof of a leak.")
                .put("Filtering suspicion is heuristic and does not identify the responsible network operator.")
                .put("No VPN profile text, private key, password, UUID, token, or raw native log is included.")
            )

        val sites = JSONArray()
        full.quick.siteChecks.forEach { site ->
            sites.put(
                JSONObject()
                    .put("label", site.label)
                    .put("host", site.host)
                    .put("dns_latency_ms", site.dnsLatencyMs)
                    .put("https_latency_ms", site.httpsLatencyMs)
                    .put("http_status", site.httpStatusCode)
                    .put("failure_type", site.failure?.name)
                    .put("error_class", null)
            )
        }
        root.put("site_checks", sites)

        val samples = JSONArray()
        full.httpsSamples.forEach { sample ->
            samples.put(
                JSONObject()
                    .put("target", sample.target)
                    .put("attempt", sample.attempt)
                    .put("latency_ms", sample.latencyMs)
                    .put("failure_class", sample.failureClass)
            )
        }
        root.put("https_samples", samples)

        val findings = JSONArray()
        full.findings.forEach { finding ->
            findings.put(
                JSONObject()
                    .put("code", finding.code)
                    .put("severity", finding.severity)
                    .put("confidence", finding.confidence)
                    .put("message", finding.message)
            )
        }
        root.put("findings", findings)

        val directory = File(appContext.cacheDir, "diagnostic-reports")
        check(directory.exists() || directory.mkdirs()) { "Cannot create diagnostic report directory" }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "routescout-diagnostic-$timestamp.json")
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    fun createShareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(
                appContext.contentResolver,
                "RouteScout diagnostic report",
                uri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isoTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
}
