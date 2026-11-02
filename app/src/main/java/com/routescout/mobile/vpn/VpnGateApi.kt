package com.routescout.mobile.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class VpnGateApi(
    private val apiUrl: String = OFFICIAL_API_URL
) {
    suspend fun fetchServers(): List<FreeVpnServer> = withContext(Dispatchers.IO) {
        val connection = URL(apiUrl).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/csv, text/plain;q=0.9, */*;q=0.8")
        connection.setRequestProperty("User-Agent", "RouteScoutMobile/0.2.0")

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("VPN Gate returned HTTP $statusCode")
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val servers = VpnGateCsvParser.parse(response)
            if (servers.isEmpty()) {
                throw IOException("VPN Gate returned no compatible OpenVPN relays")
            }
            servers
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val OFFICIAL_API_URL = "https://www.vpngate.net/api/iphone/"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}

interface FreeServerCatalog {
    suspend fun refresh(): List<FreeVpnServer>
}

class VpnGateRepository(
    private val api: VpnGateApi = VpnGateApi()
) : FreeServerCatalog {
    override suspend fun refresh(): List<FreeVpnServer> = api.fetchServers()
}
