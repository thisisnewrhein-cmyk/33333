// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigValidatorTest {
    private val validProfile = """
        {
          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "address": ["172.19.0.1/30"],
              "auto_route": true
            }
          ],
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy",
              "server": "edge.example.net",
              "server_port": 443,
              "uuid": "00000000-0000-0000-0000-000000000001"
            },
            {
              "type": "direct",
              "tag": "direct"
            }
          ],
          "route": {
            "final": "proxy"
          }
        }
    """.trimIndent()

    @Test
    fun addsDualStackFullRoutesAndDnsHijack() {
        val summary = SingBoxConfigValidator.validateAndNormalize(validProfile)
        val root = JSONObject(summary.normalizedJson)
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        val addresses = tun.getJSONArray("address")
        val routes = tun.getJSONArray("route_address")

        assertTrue(summary.protocols.contains(VpnProtocol.VLESS))
        assertEquals("edge.example.net:443", summary.primaryEndpoint)
        assertTrue(tun.getBoolean("auto_route"))
        assertEquals("hijack", tun.getString("dns_mode"))
        assertTrue((0 until addresses.length()).any { addresses.getString(it).contains(":") })
        assertTrue((0 until routes.length()).any { routes.getString(it) == "0.0.0.0/0" })
        assertTrue((0 until routes.length()).any { routes.getString(it) == "::/0" })
        assertTrue(root.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertTrue(summary.usesFullDeviceRoute)
    }

    @Test
    fun rejectsUnconditionalDirectRouteRule() {
        val profile = validProfile.replace(
            "\"route\": {",
            "\"route\": { \"rules\": [{\"outbound\": \"direct\"}],"
        )
        var rejected = false
        try {
            SingBoxConfigValidator.validateAndNormalize(profile)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsDirectFinalOutbound() {
        val profile = validProfile.replace("\"final\": \"proxy\"", "\"final\": \"direct\"")
        var rejected = false
        try {
            SingBoxConfigValidator.validateAndNormalize(profile)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsProxyOnlyProfileWithoutTun() {
        val profile = """
            {
              "outbounds": [
                {"type":"vless","tag":"proxy","server":"edge.example.net","server_port":443}
              ]
            }
        """.trimIndent()
        var rejected = false
        try {
            SingBoxConfigValidator.validateAndNormalize(profile)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun addsRouteScoutToAnImportedPackageAllowlistForTunnelVerification() {
        val profile = validProfile.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"include_package\": [\"com.example.browser\"]"
        )
        val summary = SingBoxConfigValidator.validateAndNormalize(
            profile,
            "com.routescout.mobile"
        )
        val tun = JSONObject(summary.normalizedJson).getJSONArray("inbounds").getJSONObject(0)
        val packages = tun.getJSONArray("include_package")
        assertTrue((0 until packages.length()).any {
            packages.getString(it) == "com.routescout.mobile"
        })
    }

    @Test
    fun rejectsExcludingRouteScoutFromTheVpnTunnel() {
        val profile = validProfile.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"exclude_package\": [\"com.routescout.mobile\"]"
        )
        var rejected = false
        try {
            SingBoxConfigValidator.validateAndNormalize(profile, "com.routescout.mobile")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rejectsAndroidIncompatibleSimultaneousPackageAllowAndDenyLists() {
        val profile = validProfile.replace(
            "\"auto_route\": true",
            "\"auto_route\": true, \"include_package\": [\"com.example.allowed\"], \"exclude_package\": [\"com.example.denied\"]"
        )
        var rejected = false
        try {
            SingBoxConfigValidator.validateAndNormalize(profile)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
