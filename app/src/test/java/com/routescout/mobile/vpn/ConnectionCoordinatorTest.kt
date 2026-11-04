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
        assertTrue(wireGuard.events.indexOf("connect") < wireGuard.events.lastIndexOf("disconnect"))
        assertTrue(singBox.events.indexOf("disconnect") < singBox.events.lastIndexOf("connect"))
    }

    @Test
    fun unverifiedTunnelIsRejectedAndImmediatelyStopped() = runBlocking {
        val engine = FakeEngine(VpnEngineId.WIREGUARD, connectVerified = false)
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
        assertTrue(wireGuard.running)
        assertFalse(singBox.events.contains("connect"))
    }

    @Test
    fun disconnectReturningWithoutStoppingTunnelPreventsEngineSwitch() = runBlocking {
        val wireGuard = FakeEngine(VpnEngineId.WIREGUARD)
        val singBox = FakeEngine(VpnEngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(listOf(wireGuard, singBox))

        coordinator.connect(VpnEngineId.WIREGUARD, "wireguard-profile")
        wireGuard.disconnectLeavesRunning = true

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.SING_BOX, "{\"outbounds\":[]}")
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(wireGuard.running)
        assertFalse(singBox.events.contains("connect"))
    }

    @Test
    fun liveStatusIsRecheckedAfterConnectReturns() = runBlocking {
        val engine = FakeEngine(VpnEngineId.WIREGUARD)
        val coordinator = ConnectionCoordinator(listOf(engine))
        engine.statusVerified = false

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.WIREGUARD, "profile")
        } catch (_: EngineConnectionException) {
            rejected = true
        }

        assertTrue(rejected)
        assertFalse(engine.running)
    }

    @Test
    fun currentStatusFindsTunnelStartedOutsideCoordinator() = runBlocking {
        val engine = FakeEngine(VpnEngineId.WIREGUARD)
        val coordinator = ConnectionCoordinator(listOf(engine))
        engine.running = true

        val status = coordinator.currentStatus()

        assertTrue(status.isRunning)
        assertEquals(VpnEngineId.WIREGUARD, status.engineId)
    }

    @Test
    fun currentStatusRejectsMultipleActiveEngines() = runBlocking {
        val wireGuard = FakeEngine(VpnEngineId.WIREGUARD)
        val singBox = FakeEngine(VpnEngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(listOf(wireGuard, singBox))
        wireGuard.running = true
        singBox.running = true

        var rejected = false
        try {
            coordinator.currentStatus()
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    private class FakeEngine(
        override val id: VpnEngineId,
        private val connectVerified: Boolean = true
    ) : VpnEngine {
        override val supportedProtocols: Set<VpnProtocol> = emptySet()
        val events = mutableListOf<String>()
        var running = false
        var failDisconnect = false
        var disconnectLeavesRunning = false
        var statusVerified = connectVerified

        override suspend fun connect(profile: String): VpnEngineStatus {
            events += "connect"
            running = true
            return VpnEngineStatus(
                engineId = id,
                isRunning = true,
                isVerified = connectVerified,
                receivedBytes = if (connectVerified) 10L else 0L
            )
        }

        override suspend fun disconnect() {
            events += "disconnect"
            if (failDisconnect) throw IllegalStateException("test shutdown failure")
            if (!disconnectLeavesRunning) running = false
        }

        override suspend fun status(): VpnEngineStatus = VpnEngineStatus(
            engineId = id,
            isRunning = running,
            isVerified = running && statusVerified,
            receivedBytes = if (running && statusVerified) 10L else 0L
        )
    }
}
