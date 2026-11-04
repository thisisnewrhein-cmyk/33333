package com.routescout.mobile.vpn

/**
 * Adapts the existing WireGuard backend to the engine-neutral lifecycle contract.
 * trafficProbe must generate real traffic through Android's current VPN route.
 */
class WireGuardEngineAdapter(
    private val manager: WireGuardManager,
    private val trafficProbe: suspend () -> Unit
) : VpnEngine {
    override val id: VpnEngineId = VpnEngineId.WIREGUARD

    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.WIREGUARD)

    override suspend fun connect(profile: String): VpnEngineStatus {
        val previous = manager.getSnapshot()
        manager.connect(profile)
        trafficProbe()

        val current = manager.waitForHandshake(previous)
        val verified = current.isUp &&
            current.hasRecentHandshake() &&
            current.hasTrafficEvidenceSince(previous)

        return current.toEngineStatus(verified)
    }

    override suspend fun disconnect() {
        manager.disconnect()
    }

    override suspend fun status(): VpnEngineStatus {
        val current = manager.getSnapshot()
        val verified = current.isUp &&
            current.hasRecentHandshake() &&
            current.receivedBytes > 0L

        return current.toEngineStatus(verified)
    }

    private fun WireGuardTunnelSnapshot.toEngineStatus(verified: Boolean): VpnEngineStatus =
        VpnEngineStatus(
            engineId = VpnEngineId.WIREGUARD,
            isRunning = isUp,
            isVerified = verified,
            receivedBytes = receivedBytes,
            transmittedBytes = transmittedBytes,
            latestHandshakeEpochMillis = latestHandshakeEpochMillis,
            failureReason = when {
                !isUp -> "WireGuard tunnel is not running"
                !verified -> "WireGuard tunnel is up, but a recent peer handshake and received traffic were not confirmed"
                else -> null
            }
        )
}
