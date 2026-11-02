package com.routescout.mobile.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardTunnelSnapshotTest {

    @Test
    fun tunnelUpWithoutHandshakeIsNotVerified() {
        val snapshot = WireGuardTunnelSnapshot(
            isUp = true,
            receivedBytes = 0L,
            transmittedBytes = 0L,
            latestHandshakeEpochMillis = null
        )

        assertFalse(snapshot.hasRecentHandshake(nowEpochMillis = 10_000L))
        assertFalse(snapshot.hasTrafficEvidenceSince(snapshot))
    }

    @Test
    fun freshHandshakeCountsAsEvidence() {
        val previous = snapshot(handshake = 1_000L)
        val current = snapshot(handshake = 2_000L)

        assertTrue(current.hasRecentHandshake(nowEpochMillis = 2_500L))
        assertTrue(current.hasTrafficEvidenceSince(previous))
    }

    @Test
    fun receivedTrafficCountsAsEvidenceWhenHandshakeTimestampIsUnchanged() {
        val previous = snapshot(handshake = 1_000L, received = 100L)
        val current = snapshot(handshake = 1_000L, received = 180L)

        assertTrue(current.hasTrafficEvidenceSince(previous))
    }

    @Test
    fun staleOrImplausiblyFutureHandshakeIsRejected() {
        val stale = snapshot(handshake = 1_000L)
        val future = snapshot(handshake = 50_000L)

        assertFalse(stale.hasRecentHandshake(nowEpochMillis = 500_000L))
        assertFalse(future.hasRecentHandshake(nowEpochMillis = 10_000L))
    }

    private fun snapshot(
        handshake: Long,
        received: Long = 0L
    ) = WireGuardTunnelSnapshot(
        isUp = true,
        receivedBytes = received,
        transmittedBytes = 0L,
        latestHandshakeEpochMillis = handshake
    )
}
