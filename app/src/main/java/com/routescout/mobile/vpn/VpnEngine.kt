// SPDX-License-Identifier: GPL-3.0-only

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
 * Engine-neutral snapshot of a VPN tunnel.
 * A local TUN interface being up is not proof that the remote peer is reachable.
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
 * Protocol-specific engines implement this contract.
 * Profile contents, passwords, and private keys must never be logged.
 */
interface VpnEngine {
    val id: VpnEngineId
    val supportedProtocols: Set<VpnProtocol>

    /** Returns only after protocol-specific connection verification. */
    suspend fun connect(profile: String): VpnEngineStatus

    /** Releases the engine's Android VPN interface. Must be idempotent. */
    suspend fun disconnect()

    /** Reads live engine state, not cached UI state. */
    suspend fun status(): VpnEngineStatus
}
