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

        coordinator.connect(VpnEngineId.SING_BOX, """{"outbounds":[]}""")

        assertFalse(wireGuard.running)
        assertTrue(singBox.running)
        assertTrue(wireGuard.events.indexOf("connect") < wireGuard.events.lastIndexOf("disconnect"))
        assertTrue(singBox.events.contains("connect"))
    }

    @Test
    fun unverifiedTunnelIsRejectedAndStopped() = runBlocking {
        val engine = FakeEngine(
            id = VpnEngineId.WIREGUARD,
            connectVerified = false,
            liveVerified = false
        )
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
    fun liveStatusIsRecheckedAfterConnectReturns() = runBlocking {
        val engine = FakeEngine(
            id = VpnEngineId.WIREGUARD,
            connectVerified = true,
            liveVerified = false
        )
        val coordinator = ConnectionCoordinator(listOf(engine))

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
    fun failedShutdownPreventsAnotherEngineFromStarting() = runBlocking {
        val wireGuard = FakeEngine(VpnEngineId.WIREGUARD)
        val singBox = FakeEngine(VpnEngineId.SING_BOX)
        val coordinator = ConnectionCoordinator(listOf(wireGuard, singBox))

        coordinator.connect(VpnEngineId.WIREGUARD, "wireguard-profile")
        wireGuard.failDisconnect = true

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.SING_BOX, """{"outbounds":[]}""")
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(wireGuard.running)
        assertFalse(singBox.events.contains("connect"))
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

    @Test
    fun duplicateEngineIdsAreRejected() {
        var rejected = false
        try {
            ConnectionCoordinator(
                listOf(
                    FakeEngine(VpnEngineId.WIREGUARD),
                    FakeEngine(VpnEngineId.WIREGUARD)
                )
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun blankProfilesAreRejectedBeforeStartingAnEngine() = runBlocking {
        val engine = FakeEngine(VpnEngineId.WIREGUARD)
        val coordinator = ConnectionCoordinator(listOf(engine))

        var rejected = false
        try {
            coordinator.connect(VpnEngineId.WIREGUARD, "  ")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertFalse(engine.events.contains("connect"))
    }

    private class FakeEngine(
        override val id: VpnEngineId,
        private val connectVerified: Boolean = true,
        private val liveVerified: Boolean = connectVerified
    ) : VpnEngine {
        override val supportedProtocols: Set<VpnProtocol> = emptySet()
        val events = mutableListOf<String>()
        var running = false
        var failDisconnect = false

        override suspend fun connect(profile: String): VpnEngineStatus {
            events += "connect"
            running = true
            return status(connectVerified)
        }

        override suspend fun disconnect() {
            events += "disconnect"
            if (failDisconnect) throw IllegalStateException("test shutdown failure")
            running = false
        }

        override suspend fun status(): VpnEngineStatus = status(liveVerified)

        private fun status(verified: Boolean): VpnEngineStatus = VpnEngineStatus(
            engineId = id,
            isRunning = running,
            isVerified = running && verified,
            receivedBytes = if (running && verified) 10L else 0L
        )
    }
}
