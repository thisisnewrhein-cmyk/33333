package com.routescout.mobile.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class NetworkDiagnosticsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun runQuickCheck(network: Network? = null): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = network ?: connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        val internetAvailable = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        val dnsStart = SystemClock.elapsedRealtime()
        val dnsLatency = try {
            val addresses = if (network != null) network.getAllByName("example.com") else InetAddress.getAllByName("example.com")
            if (addresses.isNotEmpty()) SystemClock.elapsedRealtime() - dnsStart else null
        } catch (_: Exception) {
            null
        }

        val httpsStart = SystemClock.elapsedRealtime()
        val httpsLatency = try {
            val connection = openConnection("https://example.com/", network) as HttpsURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.useCaches = false
            val responseCode = connection.responseCode
            connection.disconnect()
            if (responseCode in 200..499) {
                SystemClock.elapsedRealtime() - httpsStart
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        // Probe foreign services against example.com as a neutral control.
        // A failed site is evidence of a reachability problem, not proof of ISP filtering.
        val siteChecks = coroutineScope {
            SITE_PROBE_TARGETS.map { target -> async { probeSite(target, network) } }.awaitAll()
        }
        val siteAssessment = SiteAccessAnalyzer.analyze(siteChecks)

        // Independent public-IP sources run concurrently. Their services see the
        // public IP making the request; no diagnostic report is uploaded.
        val ipResult = coroutineScope {
            val ipv4Request = async { fetchJsonIp(IPV4_CHECK_URL, network) }
            val ipv6Request = async { fetchTextIp(IPV6_CHECK_URL, network) }
            val metadataRequest = async { fetchIpMetadata(network) }
            PublicIpResult(
                ipv4FromIpify = ipv4Request.await()?.takeIf { it is Inet4Address },
                ipv6FromIpify = ipv6Request.await()?.takeIf { it is Inet6Address },
                metadata = metadataRequest.await()
            )
        }

        val metadataIp = ipResult.metadata?.ip
        val ipv4FromMetadata = metadataIp?.takeIf { it is Inet4Address }
        val ipv6FromMetadata = metadataIp?.takeIf { it is Inet6Address }

        val ipSourcesAgree = when {
            ipResult.ipv4FromIpify != null && ipv4FromMetadata != null ->
                ipResult.ipv4FromIpify.hostAddress == ipv4FromMetadata.hostAddress
            ipResult.ipv6FromIpify != null && ipv6FromMetadata != null ->
                ipResult.ipv6FromIpify.hostAddress == ipv6FromMetadata.hostAddress
            else -> null
        }

        val publicIpv4 = ipResult.ipv4FromIpify?.hostAddress ?: ipv4FromMetadata?.hostAddress
        val publicIpv6 = ipResult.ipv6FromIpify?.hostAddress ?: ipv6FromMetadata?.hostAddress
        val publicIp = if (ipSourcesAgree == false) null else publicIpv4 ?: publicIpv6

        val message = when {
            !internetAvailable -> "Android не подтвердил доступ к интернету"
            dnsLatency == null -> "Не удалось выполнить DNS-запрос"
            httpsLatency == null -> "Не удалось выполнить HTTPS-проверку"
            else -> null
        }

        DiagnosticSnapshot(
            internetAvailable = internetAvailable,
            dnsLatencyMs = dnsLatency,
            httpsLatencyMs = httpsLatency,
            checkedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            error = message,
            publicIp = publicIp,
            publicIpv4 = publicIpv4,
            publicIpv6 = publicIpv6,
            countryName = ipResult.metadata?.countryName,
            countryCode = ipResult.metadata?.countryCode,
            asn = ipResult.metadata?.asn,
            ipSourcesAgree = ipSourcesAgree,
            siteChecks = siteChecks,
            siteAccessSummary = siteAssessment.summary,
            filteringSuspected = siteAssessment.filteringSuspected
        )
    }


    /**
     * Small explicit probe bound to the supplied Android VPN Network.
     * This verifies the VPN data path rather than the process default route.
     */
    suspend fun probeHttpsOn(network: Network): Boolean = withContext(Dispatchers.IO) {
        val connection = try {
            openConnection("https://example.com/", network) as HttpsURLConnection
        } catch (_: Exception) {
            return@withContext false
        }
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.requestMethod = "GET"
        connection.useCaches = false
        try {
            connection.responseCode in 200..499
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, network: Network?) =
        if (network != null) network.openConnection(URL(url)) else URL(url).openConnection()

    private fun probeSite(target: SiteProbeTarget, network: Network?): SiteConnectivityResult {
        val dnsStartedAt = SystemClock.elapsedRealtime()
        val dnsLatency = try {
            if (network != null) network.getAllByName(target.host) else InetAddress.getAllByName(target.host)
            SystemClock.elapsedRealtime() - dnsStartedAt
        } catch (error: Exception) {
            return SiteConnectivityResult(
                label = target.label,
                host = target.host,
                failure = SiteProbeFailure.DNS,
                error = "DNS: " + error.javaClass.simpleName
            )
        }

        val connection = try {
            openConnection(target.url, network) as HttpsURLConnection
        } catch (error: Exception) {
            return SiteConnectivityResult(
                label = target.label,
                host = target.host,
                dnsLatencyMs = dnsLatency,
                failure = SiteProbeFailure.HTTPS,
                error = "HTTPS: " + error.javaClass.simpleName
            )
        }
        connection.connectTimeout = SITE_PROBE_TIMEOUT_MS
        connection.readTimeout = SITE_PROBE_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "RouteScoutMobile/0.7.0")

        val httpsStartedAt = SystemClock.elapsedRealtime()
        return try {
            val statusCode = connection.responseCode
            SiteConnectivityResult(
                label = target.label,
                host = target.host,
                dnsLatencyMs = dnsLatency,
                httpsLatencyMs = SystemClock.elapsedRealtime() - httpsStartedAt,
                httpStatusCode = statusCode
            )
        } catch (_: SSLHandshakeException) {
            SiteConnectivityResult(
                label = target.label,
                host = target.host,
                dnsLatencyMs = dnsLatency,
                failure = SiteProbeFailure.HTTPS,
                error = "TLS handshake failed"
            )
        } catch (_: SocketTimeoutException) {
            SiteConnectivityResult(
                label = target.label,
                host = target.host,
                dnsLatencyMs = dnsLatency,
                failure = SiteProbeFailure.HTTPS,
                error = "HTTPS timeout"
            )
        } catch (error: Exception) {
            SiteConnectivityResult(
                label = target.label,
                host = target.host,
                dnsLatencyMs = dnsLatency,
                failure = SiteProbeFailure.HTTPS,
                error = "HTTPS: " + error.javaClass.simpleName
            )
        } finally {
            connection.disconnect()
        }
    }

    private data class SiteProbeTarget(
        val label: String,
        val host: String,
        val url: String
    )

    private fun fetchJsonIp(url: String, network: Network?): InetAddress? {
        val body = fetchHttpsBody(url, network) ?: return null
        val rawIp = try {
            JSONObject(body).optString("ip").takeUnless { it.isBlank() || it == "null" }
        } catch (_: Exception) {
            null
        }
        return normalizePublicIp(rawIp)
    }

    private fun fetchTextIp(url: String, network: Network?): InetAddress? =
        normalizePublicIp(fetchHttpsBody(url, network)?.trim())

    private fun fetchIpMetadata(network: Network?): IpMetadata? {
        val body = fetchHttpsBody(IP_METADATA_URL, network) ?: return null
        return try {
            val json = JSONObject(body)
            val rawIp = json.optString("ip").takeUnless { it.isBlank() || it == "null" }
            IpMetadata(
                ip = normalizePublicIp(rawIp),
                countryName = json.optString("country_name")
                    .takeUnless { it.isBlank() || it == "null" },
                countryCode = json.optString("country_code")
                    .takeIf { it.length == 2 && it.all(Char::isLetter) }
                    ?.uppercase(Locale.ROOT),
                asn = json.optString("asn").takeUnless { it.isBlank() || it == "null" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchHttpsBody(url: String, network: Network?): String? {
        val connection = try {
            openConnection(url, network) as HttpsURLConnection
        } catch (_: Exception) {
            return null
        }
        connection.connectTimeout = IP_CHECK_TIMEOUT_MS
        connection.readTimeout = IP_CHECK_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json, text/plain")
        connection.setRequestProperty("User-Agent", "RouteScoutMobile/0.5.0")

        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizePublicIp(value: String?): InetAddress? {
        val candidate = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val address = try {
            if (candidate.contains(":")) {
                if (!candidate.matches(Regex("[0-9a-fA-F:.]+"))) return null
                InetAddress.getByName(candidate)
            } else {
                val octets = candidate.split(".")
                if (octets.size != 4 || octets.any {
                        val part = it.toIntOrNull()
                        part == null || part !in 0..255 || it.isBlank()
                    }
                ) return null
                InetAddress.getByName(candidate)
            }
        } catch (_: Exception) {
            return null
        }

        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return null

        return address
    }

    private data class IpMetadata(
        val ip: InetAddress?,
        val countryName: String?,
        val countryCode: String?,
        val asn: String?
    )

    private data class PublicIpResult(
        val ipv4FromIpify: InetAddress?,
        val ipv6FromIpify: InetAddress?,
        val metadata: IpMetadata?
    )

    companion object {
        private const val IPV4_CHECK_URL = "https://api.ipify.org?format=json"
        private const val IPV6_CHECK_URL = "https://api6.ipify.org"
        private const val IP_METADATA_URL = "https://ipapi.co/json/"
        private const val IP_CHECK_TIMEOUT_MS = 3_500
        private const val SITE_PROBE_TIMEOUT_MS = 3_500
        private val SITE_PROBE_TARGETS = listOf(
            SiteProbeTarget("Контрольный сайт", "example.com", "https://example.com/"),
            SiteProbeTarget("Google", "www.google.com", "https://www.google.com/generate_204"),
            SiteProbeTarget("YouTube", "www.youtube.com", "https://www.youtube.com/generate_204"),
            SiteProbeTarget("GitHub", "github.com", "https://github.com/"),
            SiteProbeTarget("OpenAI", "openai.com", "https://openai.com/"),
            SiteProbeTarget("Discord", "discord.com", "https://discord.com/"),
            SiteProbeTarget("Telegram", "telegram.org", "https://telegram.org/"),
            SiteProbeTarget("Wikipedia", "wikipedia.org", "https://wikipedia.org/")
        )
    }
}
