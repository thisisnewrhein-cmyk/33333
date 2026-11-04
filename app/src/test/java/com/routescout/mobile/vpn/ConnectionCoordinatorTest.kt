package com.routescout.mobile.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCoordinatorTest {

    @Test
    fun switchingEnginesStopsThePreviousEngineBeforeStartingTheNext() = runBlocking {
        val wireGuard = FakeEngine(VpnEngineId.WIREGUARD)
        val singBox = FakeEngine(VpnEngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(listOf(wireGuard, singBox))

        coordinator.connect(VpnEngineId.WIREGUARD, "wireguard-profile")
        assertTrue(wireGuard.running)

        coordinator.connect(VpnEngineId.SING_BOX, "{\"outbounds\":[]}")

        assertFalse(wireGuard.running)
        assertTrue(singBox.running)
        assertTrue(wireGuard.events.indexOf("disconnect") < wireGuard.events.lastIndex)
        assertEquals("connect", singBox.events.last())
    }

    @Test
    fun unverifiedTunnelIsRejectedAndImmediatelyStopped() = runBlocking {
        val engine = FakeEngine(VpnEngineId.WIREGUARD, verified = false)
        val coordinator = ConnectionCoordinator(listOf(engine))

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.WIREGUARD, "profile")
        } catch (_: EngineConnectionException) {
            rejected = true
        }

        assertTrue(rejected)
        assertFalse(engine.running)
        assertFalse(coordinator.currentStatus().isConnected)
    }

    @Test
    fun failedShutdownPreventsAnotherEngineFromStarting() = runBlocking {
        val wireGuard = FakeEngine(VpnEngineId.WIREGUARD)
        val singBox = FakeEngine(VpnEngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(listOf(wireGuard, singBox))

        coordinator.connect(VpnEngineId.WIREGUARD, "wireguard-profile")
        wireGuard.failDisconnect = true

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.SING_BOX, "{\"outbounds\":[]}")
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertFalse(singBox.events.contains("connect"))
    }

    private class FakeEngine(
        override val id: VpnEngineId,
        private val verified: Boolean = true
    ) : VpnEngine {
        override val supportedProtocols: Set<VpnProtocol> = emptySet()
        val events = mutableListOf<String>()
        var running = false
        var failDisconnect = false

        override suspend fun connect(profile: String): VpnEngineStatus {
            events += "connect"
            running = true
            return VpnEngineStatus(
                engineId = id,
                isRunning = true,
                isVerified = verified,
                receivedBytes = if (verified) 10L else 0L
            )
        }

        override suspend fun disconnect() {
            events += "disconnect"
            if (failDisconnect) throw IllegalStateException("test shutdown failure")
            running = false
        }

        override suspend fun status(): VpnEngineStatus = VpnEngineStatus(
            engineId = id,
            isRunning = running,
            isVerified = running && verified
        )
    }
}
