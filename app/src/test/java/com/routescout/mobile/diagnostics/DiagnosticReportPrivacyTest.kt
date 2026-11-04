// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticReportPrivacyTest {
    @Test
    fun masksIpv4WithoutRemovingNetworkPrefix() {
        assertEquals("203.0.113.x", DiagnosticReportPrivacy.maskIp("203.0.113.42"))
    }

    @Test
    fun masksIpv6AfterFirstThreeGroups() {
        assertEquals("2001:db8:abcd:…", DiagnosticReportPrivacy.maskIp("2001:db8:abcd:1234:5678:90ab:cdef:1234"))
    }

    @Test
    fun emptyAddressStaysAbsent() {
        assertNull(DiagnosticReportPrivacy.maskIp(null))
        assertNull(DiagnosticReportPrivacy.maskIp(""))
    }

    @Test
    fun mapsEngineMessagesToStableNonSensitiveCodes() {
        assertEquals(
            "HANDSHAKE_UNVERIFIED",
            DiagnosticReportPrivacy.safeFailureCode("WireGuard handshake not confirmed")
        )
        assertEquals(
            "DATA_PATH_UNVERIFIED",
            DiagnosticReportPrivacy.safeFailureCode("No HTTPS traffic observed")
        )
        assertNull(DiagnosticReportPrivacy.safeFailureCode(null))
    }
}

class DiagnosticAnalyzerTest {
    @Test
    fun calculatesHttpsFailureRateWithoutCallingItPacketLoss() {
        val samples = listOf(
            HttpsProbeSample("example.com", 1, 100L),
            HttpsProbeSample("example.com", 2, null, "SocketTimeoutException"),
            HttpsProbeSample("example.com", 3, 120L),
            HttpsProbeSample("example.com", 4, 130L)
        )
        assertEquals(25.0, DiagnosticAnalyzer.failureRate(samples)!!, 0.001)
    }

    @Test
    fun calculatesMeanAdjacentLatencyVariation() {
        val samples = listOf(
            HttpsProbeSample("example.com", 1, 100L),
            HttpsProbeSample("example.com", 2, 130L),
            HttpsProbeSample("example.com", 3, 110L)
        )
        assertEquals(25L, DiagnosticAnalyzer.latencyVariation(samples))
    }

    @Test
    fun reportsBasicConnectivityAndStillDisclosesDnsLeakLimitation() {
        val snapshot = DiagnosticSnapshot(
            internetAvailable = true,
            dnsLatencyMs = 12L,
            httpsLatencyMs = 45L,
            filteringSuspected = false,
            ipSourcesAgree = true
        )
        val codes = DiagnosticAnalyzer.findings(snapshot, false, 0.0).map { it.code }
        assertTrue(codes.contains("BASIC_CONNECTIVITY_OK"))
        assertTrue(codes.contains("DNS_LEAK_NOT_FULLY_PROVEN"))
    }

    @Test
    fun doesNotReportConnectivityOkWhenBaselineDataIsMissing() {
        val snapshot = DiagnosticSnapshot()
        val codes = DiagnosticAnalyzer.findings(snapshot, false, null).map { it.code }
        assertTrue(codes.contains("DNS_LOOKUP_FAILED"))
        assertFalse(codes.contains("BASIC_CONNECTIVITY_OK"))
    }

    @Test
    fun noVariationIsReturnedForOneSuccessfulSample() {
        assertNull(
            DiagnosticAnalyzer.latencyVariation(
                listOf(HttpsProbeSample("example.com", 1, 100L))
            )
        )
    }
}
