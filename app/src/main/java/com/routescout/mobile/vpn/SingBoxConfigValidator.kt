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

    fun validateAndNormalize(profile: String): SingBoxProfileSummary {
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
        require(exclusions == null || !(0 until exclusions.length()).any {
            exclusions.optString(it) in setOf("0.0.0.0/0", "::/0")
        }) {
            "Профиль исключает весь IPv4 или IPv6 трафик из туннеля"
        }

        tun.put("auto_route", true)
        require(!tun.optString("dns_mode").equals("disabled", ignoreCase = true)) {
            "DNS hijack отключён; профиль не пройдёт базовую защиту DNS"
        }
        if (!tun.has("dns_mode")) tun.put("dns_mode", "hijack")

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
        return if (port in 1..65535) "\$host:\$port" else host
    }
}
