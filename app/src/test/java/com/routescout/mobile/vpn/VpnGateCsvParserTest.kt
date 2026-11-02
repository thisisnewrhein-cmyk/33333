package com.routescout.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class VpnGateCsvParserTest {

    private val profile = """
        client
        proto tcp-client
        remote 203.0.113.10 443
        auth-user-pass
    """.trimIndent()

    private val encodedProfile = Base64.getEncoder()
        .encodeToString(profile.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesQuotedFieldsAndPreservesCommas() {
        val csv = """
            *vpn_servers
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            "relay, one",203.0.113.10,900,40,120000000,United States,US,5,3600,5,1000,2weeks,"operator, inc","message, with comma",$encodedProfile
        """.trimIndent()

        val server = VpnGateCsvParser.parse(csv).single()

        assertEquals("relay, one", server.hostName)
        assertEquals("203.0.113.10", server.ipAddress)
        assertEquals("United States", server.countryName)
        assertEquals("US", server.countryCode)
        assertEquals("operator, inc", server.operator)
        assertEquals("message, with comma", server.message)
        assertEquals(40, server.pingMs)
        assertEquals(120000000L, server.speedBitsPerSecond)
    }

    @Test
    fun rejectsRowsWithInvalidProfilesOrAddresses() {
        val invalidProfile = Base64.getEncoder().encodeToString("not a vpn profile".toByteArray())
        val csv = """
            *vpn_servers
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            relay,999.1.1.1,100,20,1000000,Test,TT,1,100,1,1,2weeks,operator,message,$encodedProfile
            relay2,203.0.113.11,100,20,1000000,Test,TT,1,100,1,1,2weeks,operator,message,$invalidProfile
        """.trimIndent()

        assertEquals(0, VpnGateCsvParser.parse(csv).size)
    }

    @Test
    fun bestServerUsesDirectoryScoreAndThenPing() {
        val slow = server("slow", "203.0.113.12", score = 100, ping = 90)
        val fast = server("fast", "203.0.113.13", score = 100, ping = 20)
        val lowerScore = server("lower", "203.0.113.14", score = 90, ping = 5)

        assertNotNull(ServerSelectionEngine.bestServer(listOf(slow, lowerScore, fast)))
        assertEquals("fast", ServerSelectionEngine.bestServer(listOf(slow, lowerScore, fast))?.hostName)
        assertNull(ServerSelectionEngine.bestServer(emptyList()))
    }

    private fun server(host: String, ip: String, score: Long, ping: Int) = FreeVpnServer(
        hostName = host,
        ipAddress = ip,
        score = score,
        pingMs = ping,
        speedBitsPerSecond = 10_000_000,
        countryName = "Test",
        countryCode = "TT",
        activeSessions = 1,
        uptimeSeconds = 100,
        totalUsers = 1,
        totalTrafficBytes = 1,
        logType = "2weeks",
        operator = "operator",
        message = "",
        openVpnConfigBase64 = encodedProfile
    )
}
