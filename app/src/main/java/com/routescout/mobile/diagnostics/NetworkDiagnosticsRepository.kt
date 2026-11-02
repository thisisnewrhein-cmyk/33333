package com.routescout.mobile.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class NetworkDiagnosticsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun runQuickCheck(): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        val internetAvailable = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

        val dnsStart = SystemClock.elapsedRealtime()
        val dnsLatency = try {
            val addresses = InetAddress.getAllByName("example.com")
            if (addresses.isNotEmpty()) SystemClock.elapsedRealtime() - dnsStart else null
        } catch (_: Exception) {
            null
        }

        val httpsStart = SystemClock.elapsedRealtime()
        val httpsLatency = try {
            val connection = URL("https://example.com/").openConnection() as HttpsURLConnection
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

        // Independent public-IP sources run concurrently. Their services see the
        // public IP making the request; no diagnostic report is uploaded.
        val ipResult = coroutineScope {
            val ipv4Request = async { fetchJsonIp(IPV4_CHECK_URL) }
            val ipv6Request = async { fetchTextIp(IPV6_CHECK_URL) }
            val metadataRequest = async { fetchIpMetadata() }
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
            ipSourcesAgree = ipSourcesAgree
        )
    }

    private fun fetchJsonIp(url: String): InetAddress? {
        val body = fetchHttpsBody(url) ?: return null
        val rawIp = try {
            JSONObject(body).optString("ip").takeUnless { it.isBlank() || it == "null" }
        } catch (_: Exception) {
            null
        }
        return normalizePublicIp(rawIp)
    }

    private fun fetchTextIp(url: String): InetAddress? =
        normalizePublicIp(fetchHttpsBody(url)?.trim())

    private fun fetchIpMetadata(): IpMetadata? {
        val body = fetchHttpsBody(IP_METADATA_URL) ?: return null
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

    private fun fetchHttpsBody(url: String): String? {
        val connection = try {
            URL(url).openConnection() as HttpsURLConnection
        } catch (_: Exception) {
            return null
        }
        connection.connectTimeout = IP_CHECK_TIMEOUT_MS
        connection.readTimeout = IP_CHECK_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json, text/plain")
        connection.setRequestProperty("User-Agent", "RouteScoutMobile/0.3.0")

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
    }
}
