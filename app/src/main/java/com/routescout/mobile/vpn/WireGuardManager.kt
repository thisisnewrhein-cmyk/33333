package com.routescout.mobile.vpn

import android.content.Context
import android.os.SystemClock
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

data class WireGuardTunnelSnapshot(
    val isUp: Boolean,
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val latestHandshakeEpochMillis: Long?
) {
    fun hasRecentHandshake(
        nowEpochMillis: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 180_000L
    ): Boolean {
        val handshake = latestHandshakeEpochMillis ?: return false
        val age = nowEpochMillis - handshake
        return handshake > 0L && age in -30_000L..maxAgeMs
    }

    fun hasTrafficEvidenceSince(previous: WireGuardTunnelSnapshot): Boolean {
        val previousHandshake = previous.latestHandshakeEpochMillis ?: 0L
        val currentHandshake = latestHandshakeEpochMillis ?: 0L
        return currentHandshake > previousHandshake || receivedBytes > previous.receivedBytes
    }
}

class WireGuardManager(context: Context) {
    private val appContext = context.applicationContext
    private val contextPackageName = appContext.packageName
    private val backend = GoBackend(appContext)
    private val tunnel = RouteScoutTunnel()

    /**
     * Validates the profile before the coordinator tears down an existing tunnel.
     * RouteScout must be included in the VPN route so its HTTPS probe can verify
     * that packets actually traversed the tunnel.
     */
    suspend fun validateConfig(configText: String) = withContext(Dispatchers.IO) {
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
        val interfaceConfig = config.getInterface()
        val packageName = contextPackageName

        if (packageName in interfaceConfig.getExcludedApplications()) {
            throw IllegalArgumentException(
                "RouteScout исключён из VPN-профиля. Удали приложение из списка исключений, чтобы проверить туннель."
            )
        }

        val includedApplications = interfaceConfig.getIncludedApplications()
        if (includedApplications.isNotEmpty() && packageName !in includedApplications) {
            throw IllegalArgumentException(
                "В VPN-профиле разрешены только отдельные приложения, но RouteScout не включён в этот список."
            )
        }
    }

    suspend fun connect(configText: String) = withContext(Dispatchers.IO) {
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }

    suspend fun isConnected(): Boolean = getSnapshot().isUp

    suspend fun getSnapshot(): WireGuardTunnelSnapshot = withContext(Dispatchers.IO) {
        readSnapshot()
    }

    /**
     * Waits for evidence from the remote peer after a traffic probe.
     * State.UP alone is deliberately not treated as a successful connection.
     */
    suspend fun waitForHandshake(
        previous: WireGuardTunnelSnapshot,
        timeoutMs: Long = 12_000L
    ): WireGuardTunnelSnapshot = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        var current = readSnapshot()

        while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            if (
                current.isUp &&
                current.hasRecentHandshake() &&
                current.hasTrafficEvidenceSince(previous)
            ) {
                return@withContext current
            }

            delay(400L)
            current = readSnapshot()
        }

        current
    }

    private fun readSnapshot(): WireGuardTunnelSnapshot {
        val isUp = backend.getState(tunnel) == Tunnel.State.UP
        val statistics = backend.getStatistics(tunnel)
        val latestHandshake = statistics.peers()
            .mapNotNull { peerKey ->
                statistics.peer(peerKey)?.latestHandshakeEpochMillis()
                    ?.takeIf { it > 0L }
            }
            .maxOrNull()

        return WireGuardTunnelSnapshot(
            isUp = isUp,
            receivedBytes = statistics.totalRx(),
            transmittedBytes = statistics.totalTx(),
            latestHandshakeEpochMillis = latestHandshake
        )
    }

    private class RouteScoutTunnel : Tunnel {
        override fun getName(): String = "RouteScout"

        override fun onStateChange(newState: Tunnel.State) {
            // The backend owns the authoritative tunnel state.
        }
    }
}
