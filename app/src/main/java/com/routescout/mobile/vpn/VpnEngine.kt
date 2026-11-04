package com.routescout.mobile.vpn

enum class VpnEngineId {
    WIREGUARD,
    SING_BOX,
    OPENVPN
}

enum class VpnProtocol {
    WIREGUARD,
    OPENVPN,
    SHADOWSOCKS,
    VMESS,
    VLESS,
    TROJAN,
    HYSTERIA2,
    TUIC,
    ANYTLS
}

/**
 * A deliberately small, engine-neutral view of a tunnel.
 *
 * isRunning describes the local Android tunnel state. isVerified describes
 * evidence that the remote peer has actually responded. These are not interchangeable.
 */
data class VpnEngineStatus(
    val engineId: VpnEngineId?,
    val isRunning: Boolean,
    val isVerified: Boolean,
    val receivedBytes: Long = 0L,
    val transmittedBytes: Long = 0L,
    val latestHandshakeEpochMillis: Long? = null,
    val failureReason: String? = null
) {
    val isConnected: Boolean
        get() = isRunning && isVerified

    companion object {
        fun stopped(): VpnEngineStatus = VpnEngineStatus(
            engineId = null,
            isRunning = false,
            isVerified = false
        )
    }
}

/**
 * Implementations own their protocol-specific profile parsing and verification.
 * Profiles and private keys must never be logged by an engine implementation.
 */
interface VpnEngine {
    val id: VpnEngineId
    val supportedProtocols: Set<VpnProtocol>

    suspend fun connect(profile: String): VpnEngineStatus

    /**
     * Must be safe to call when already stopped and must release the Android VPN
     * interface before returning. The coordinator independently verifies status().
     */
    suspend fun disconnect()

    /**
     * isRunning must reflect ownership of the live VPN interface, not just a
     * worker process or a service object that has not established its TUN.
     */
    suspend fun status(): VpnEngineStatus
}
