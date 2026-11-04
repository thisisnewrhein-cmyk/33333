package com.routescout.mobile.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAccessAnalyzerTest {
    @Test
    fun flagsPossibleFilteringWhenControlWorksAndSeveralForeignSitesFail() {
        val result = SiteAccessAnalyzer.analyze(
            listOf(
                site("example.com", SiteProbeFailure.NONE),
                site("github.com", SiteProbeFailure.HTTPS),
                site("youtube.com", SiteProbeFailure.DNS)
            )
        )
        assertTrue(result.filteringSuspected)
    }

    @Test
    fun doesNotAssumeFilteringWhenControlAlsoFails() {
        val result = SiteAccessAnalyzer.analyze(
            listOf(
                site("example.com", SiteProbeFailure.DNS),
                site("github.com", SiteProbeFailure.HTTPS),
                site("youtube.com", SiteProbeFailure.DNS)
            )
        )
        assertFalse(result.filteringSuspected)
    }

    @Test
    fun doesNotFlagFilteringWhenAllForeignSitesRespond() {
        val result = SiteAccessAnalyzer.analyze(
            listOf(
                site("example.com", SiteProbeFailure.NONE),
                site("github.com", SiteProbeFailure.NONE),
                site("youtube.com", SiteProbeFailure.NONE)
            )
        )
        assertFalse(result.filteringSuspected)
    }

    private fun site(host: String, failure: SiteProbeFailure) = SiteConnectivityResult(
        label = host,
        host = host,
        dnsLatencyMs = if (failure == SiteProbeFailure.DNS) null else 20L,
        httpsLatencyMs = if (failure == SiteProbeFailure.NONE) 80L else null,
        failure = failure
    )
}
