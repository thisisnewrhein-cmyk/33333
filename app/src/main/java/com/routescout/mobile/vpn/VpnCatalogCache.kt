package com.routescout.mobile.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VpnCatalogCacheSnapshot(
    val servers: List<FreeVpnServer>,
    val healthResults: Map<String, ServerHealthResult>,
    val updatedAtMs: Long
)

class VpnCatalogCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): VpnCatalogCacheSnapshot? {
        val raw = preferences.getString(KEY_CATALOG, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val serverArray = root.optJSONArray("servers") ?: JSONArray()
            val servers = buildList {
                for (index in 0 until serverArray.length()) {
                    val item = serverArray.optJSONObject(index) ?: continue
                    try {
                        add(
                            FreeVpnServer(
                                hostName = item.optString("hostName"),
                                ipAddress = item.optString("ipAddress"),
                                score = item.optLong("score"),
                                pingMs = nullableInt(item, "pingMs"),
                                speedBitsPerSecond = nullableLong(item, "speedBitsPerSecond"),
                                countryName = item.optString("countryName", "Unknown country"),
                                countryCode = item.optString("countryCode", "??"),
                                activeSessions = item.optLong("activeSessions"),
                                uptimeSeconds = item.optLong("uptimeSeconds"),
                                totalUsers = item.optLong("totalUsers"),
                                totalTrafficBytes = item.optLong("totalTrafficBytes"),
                                logType = item.optString("logType"),
                                operator = item.optString("operator"),
                                message = item.optString("message"),
                                openVpnConfigBase64 = item.optString("openVpnConfigBase64")
                            )
                        )
                    } catch (_: Exception) {
                        // Ignore one damaged row and retain the rest of the cache.
                    }
                }
            }

            val health = mutableMapOf<String, ServerHealthResult>()
            val healthArray = root.optJSONArray("health") ?: JSONArray()
            for (index in 0 until healthArray.length()) {
                val item = healthArray.optJSONObject(index) ?: continue
                val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                val result = when (item.optString("type")) {
                    "tcp" -> ServerHealthResult.TcpReachable(item.optLong("latencyMs"))
                    "tcp_failed" -> ServerHealthResult.TcpUnreachable(item.optString("reason"))
                    "udp" -> ServerHealthResult.UdpRequiresHandshake
                    "invalid" -> ServerHealthResult.InvalidProfile
                    else -> null
                }
                if (result != null) health[key] = result
            }

            if (servers.isEmpty()) null else VpnCatalogCacheSnapshot(
                servers = servers,
                healthResults = health,
                updatedAtMs = root.optLong("updatedAtMs")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        servers: List<FreeVpnServer>,
        healthResults: Map<String, ServerHealthResult>,
        updatedAtMs: Long
    ) {
        val root = JSONObject().put("updatedAtMs", updatedAtMs)
        val serverArray = JSONArray()
        ServerSelectionEngine.rankedServers(servers).take(MAX_CACHED_SERVERS).forEach { server ->
            serverArray.put(
                JSONObject()
                    .put("hostName", server.hostName)
                    .put("ipAddress", server.ipAddress)
                    .put("score", server.score)
                    .put("pingMs", server.pingMs ?: JSONObject.NULL)
                    .put("speedBitsPerSecond", server.speedBitsPerSecond ?: JSONObject.NULL)
                    .put("countryName", server.countryName)
                    .put("countryCode", server.countryCode)
                    .put("activeSessions", server.activeSessions)
                    .put("uptimeSeconds", server.uptimeSeconds)
                    .put("totalUsers", server.totalUsers)
                    .put("totalTrafficBytes", server.totalTrafficBytes)
                    .put("logType", server.logType)
                    .put("operator", server.operator)
                    .put("message", server.message)
                    .put("openVpnConfigBase64", server.openVpnConfigBase64)
            )
        }
        root.put("servers", serverArray)

        val healthArray = JSONArray()
        healthResults.forEach { (key, result) ->
            val item = JSONObject().put("key", key)
            when (result) {
                is ServerHealthResult.TcpReachable ->
                    item.put("type", "tcp").put("latencyMs", result.latencyMs)
                is ServerHealthResult.TcpUnreachable ->
                    item.put("type", "tcp_failed").put("reason", result.reason.take(160))
                ServerHealthResult.UdpRequiresHandshake -> item.put("type", "udp")
                ServerHealthResult.InvalidProfile -> item.put("type", "invalid")
            }
            healthArray.put(item)
        }
        root.put("health", healthArray)
        preferences.edit().putString(KEY_CATALOG, root.toString()).commit()
    }

    private fun nullableInt(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.optInt(key)

    private fun nullableLong(json: JSONObject, key: String): Long? =
        if (!json.has(key) || json.isNull(key)) null else json.optLong(key)

    private companion object {
        const val PREFERENCES_NAME = "routescout_vpn_catalog"
        const val KEY_CATALOG = "catalog_snapshot"
        const val MAX_CACHED_SERVERS = 120
    }
}
