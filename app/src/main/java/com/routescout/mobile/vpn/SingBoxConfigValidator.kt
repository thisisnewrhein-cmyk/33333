// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import org.json.JSONArray
import org.json.JSONObject

data class SingBoxProfileSummary(
    val normalizedJson: String,
    val protocols: Set<VpnProtocol>,
    val primaryEndpoint: String?,
    val usesFullDeviceRoute: Boolean
)

/**
 * Accepts only device-VPN configs: a TUN inbound, a proxy final outbound,
 * full IPv4/IPv6 routes, DNS hijacking, and protected upstream interface detection.
 */
object SingBoxConfigValidator {
    private val protocolByOutboundType = mapOf(
        "shadowsocks" to VpnProtocol.SHADOWSOCKS,
        "vmess" to VpnProtocol.VMESS,
        "vless" to VpnProtocol.VLESS,
        "trojan" to VpnProtocol.TROJAN,
        "hysteria2" to VpnProtocol.HYSTERIA2,
        "tuic" to VpnProtocol.TUIC,
        "anytls" to VpnProtocol.ANYTLS
    )

    fun validateAndNormalize(profile: String, routeScoutPackageName: String? = null): SingBoxProfileSummary {
        require(profile.isNotBlank()) { "Файл профиля пустой" }
        val root = try {
            JSONObject(profile)
        } catch (_: Exception) {
            throw IllegalArgumentException("Нужен корректный JSON-профиль sing-box")
        }

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("В профиле отсутствует массив inbounds")
        val tun = (0 until inbounds.length())
            .mapNotNull { inbounds.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "tun" }
            ?: throw IllegalArgumentException("В профиле нет TUN inbound: это не системный VPN-профиль")

        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В профиле отсутствует массив outbounds")
        require(outbounds.length() > 0) { "В профиле нет исходящих подключений" }

        val protocols = linkedSetOf<VpnProtocol>()
        val byTag = linkedMapOf<String, JSONObject>()
        val remoteOutbounds = mutableListOf<JSONObject>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val type = outbound.optString("type").lowercase()
            protocolByOutboundType[type]?.let(protocols::add)
            val tag = outbound.optString("tag")
            if (tag.isNotBlank()) byTag[tag] = outbound
            if (type in protocolByOutboundType.keys) remoteOutbounds += outbound
        }
        require(protocols.isNotEmpty()) {
            "Не найден поддерживаемый протокол: Shadowsocks, VMess, VLESS, Trojan, Hysteria2, TUIC или AnyTLS"
        }

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val finalTag = route.optString("final").takeIf { it.isNotBlank() }
        val finalOutbound = if (finalTag != null) {
            byTag[finalTag] ?: throw IllegalArgumentException("route.final ссылается на неизвестный outbound")
        } else {
            outbounds.optJSONObject(0)
                ?: throw IllegalArgumentException("Первый outbound профиля повреждён")
        }
        val finalType = finalOutbound.optString("type").lowercase()
        require(finalType !in setOf("direct", "block", "dns")) {
            "Финальный маршрут ведёт в $finalType, а не через VPN-прокси"
        }
        require(finalType in protocolByOutboundType.keys || finalType in setOf("selector", "urltest")) {
            "Финальный outbound $finalType не поддерживается RouteScout"
        }
        if (finalType in setOf("selector", "urltest")) {
            val candidates = finalOutbound.optJSONArray("outbounds")
                ?: throw IllegalArgumentException("Финальный selector/urltest не содержит список outbound")
            val candidateTypes = (0 until candidates.length()).mapNotNull { index ->
                byTag[candidates.optString(index)]?.optString("type")?.lowercase()
            }
            require(candidateTypes.any { it in protocolByOutboundType.keys }) {
                "Финальный selector/urltest не содержит поддерживаемого VPN-протокола"
            }
            require(candidateTypes.none { it in setOf("direct", "block", "dns") }) {
                "Финальный selector/urltest содержит прямой или блокирующий маршрут"
            }
        }

        val routeRules = route.optJSONArray("rules")
        val routeRuleScopeKeys = listOf(
            "domain", "domain_suffix", "domain_keyword", "domain_regex",
            "ip_cidr", "ip_is_private", "port", "port_range", "network",
            "protocol", "source_ip_cidr", "source_port", "process_name",
            "process_path", "package_name", "rule_set", "inbound", "user",
            "wifi_ssid", "network_type"
        )
        if (routeRules != null) {
            for (index in 0 until routeRules.length()) {
                val rule = routeRules.optJSONObject(index) ?: continue
                val outboundTag = rule.optString("outbound")
                val outboundType = byTag[outboundTag]?.optString("type")?.lowercase()
                    ?: outboundTag.lowercase()
                if (outboundType == "direct") {
                    val hasScope = routeRuleScopeKeys.any { key ->
                        if (!rule.has(key)) {
                            false
                        } else {
                            when (val value = rule.opt(key)) {
                                null, JSONObject.NULL -> false
                                is JSONArray -> value.length() > 0
                                is String -> value.isNotBlank()
                                else -> true
                            }
                        }
                    }
                    require(hasScope) {
                        "Безусловное правило direct обходит VPN-маршрут"
                    }
                }
            }
        }

        // Prevent the tunnel's own upstream sockets from being routed back into TUN.
        route.put("auto_detect_interface", true)

        // Full-device mode: preserve exclusions, but always route both address families into TUN.
        val addresses = tun.optJSONArray("address") ?: JSONArray().also { tun.put("address", it) }
        if (!containsAddressFamily(addresses, ipv4 = true)) addresses.put("172.19.0.1/30")
        if (!containsAddressFamily(addresses, ipv4 = false)) addresses.put("fdfe:dcba:9876::1/126")

        val routeAddresses = tun.optJSONArray("route_address")
            ?: JSONArray().also { tun.put("route_address", it) }
        if (!containsCidr(routeAddresses, "0.0.0.0/0")) routeAddresses.put("0.0.0.0/0")
        if (!containsCidr(routeAddresses, "::/0")) routeAddresses.put("::/0")

        val exclusions = tun.optJSONArray("route_exclude_address")
        val excludedCidrs = exclusions?.let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        }.orEmpty()
        val excludesAllIpv4 = "0.0.0.0/0" in excludedCidrs ||
            ("0.0.0.0/1" in excludedCidrs && "128.0.0.0/1" in excludedCidrs)
        val excludesAllIpv6 = "::/0" in excludedCidrs ||
            ("::/1" in excludedCidrs && "8000::/1" in excludedCidrs)
        require(!excludesAllIpv4 && !excludesAllIpv6) {
            "Профиль исключает всё адресное пространство IPv4 или IPv6 из туннеля"
        }

        val includePackages = tun.optJSONArray("include_package")
        val excludePackages = tun.optJSONArray("exclude_package")
        require(
            includePackages == null || includePackages.length() == 0 ||
                excludePackages == null || excludePackages.length() == 0
        ) {
            "Android не позволяет одновременно включить allowlist и blocklist приложений в одном VPN-профиле"
        }
        if (!routeScoutPackageName.isNullOrBlank()) {
            val excludesRouteScout = excludePackages != null &&
                (0 until excludePackages.length()).any {
                    excludePackages.optString(it) == routeScoutPackageName
                }
            require(!excludesRouteScout) {
                "Нельзя исключить само приложение RouteScout: оно выполняет проверку VPN-туннеля"
            }
            if (includePackages != null && includePackages.length() > 0) {
                val alreadyIncluded = (0 until includePackages.length()).any {
                    includePackages.optString(it) == routeScoutPackageName
                }
                if (!alreadyIncluded) includePackages.put(routeScoutPackageName)
            }
        }

        tun.put("auto_route", true)
        require(!tun.optString("dns_mode").equals("disabled", ignoreCase = true)) {
            "DNS hijack отключён; профиль не пройдёт базовую защиту DNS"
        }
        tun.put("dns_mode", "hijack")

        val endpoint = extractEndpoint(finalOutbound)
            ?: remoteOutbounds.firstOrNull()?.let(::extractEndpoint)

        return SingBoxProfileSummary(
            normalizedJson = root.toString(2),
            protocols = protocols,
            primaryEndpoint = endpoint,
            usesFullDeviceRoute = true
        )
    }

    private fun containsAddressFamily(addresses: JSONArray, ipv4: Boolean): Boolean =
        (0 until addresses.length()).any { index ->
            val value = addresses.optString(index)
            if (ipv4) !value.contains(":") else value.contains(":")
        }

    private fun containsCidr(addresses: JSONArray, cidr: String): Boolean =
        (0 until addresses.length()).any { addresses.optString(it) == cidr }

    private fun extractEndpoint(outbound: JSONObject): String? {
        val host = outbound.optString("server").takeIf { it.isNotBlank() } ?: return null
        val port = outbound.optInt("server_port", 0)
        return if (port in 1..65535) "$host:$port" else host
    }
}
