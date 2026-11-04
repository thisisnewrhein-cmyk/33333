// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.routescout.mobile.diagnostics.NetworkDiagnosticsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class SingBoxEngineAdapter(
    context: Context,
    private val configStore: SecureSingBoxConfigStore,
    private val diagnostics: NetworkDiagnosticsRepository
) : VpnEngine {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    override val id: VpnEngineId = VpnEngineId.SING_BOX

    override val supportedProtocols: Set<VpnProtocol> = setOf(
        VpnProtocol.SHADOWSOCKS,
        VpnProtocol.VMESS,
        VpnProtocol.VLESS,
        VpnProtocol.TROJAN,
        VpnProtocol.HYSTERIA2,
        VpnProtocol.TUIC,
        VpnProtocol.ANYTLS
    )

    override suspend fun connect(profile: String): VpnEngineStatus {
        val validated = SingBoxConfigValidator.validateAndNormalize(profile, appContext.packageName)
        configStore.save(validated.normalizedJson)

        RouteScoutVpnRuntime.markStarting(appContext)
        RouteScoutVpnService.start(appContext)

        val runtime = withTimeout(45_000L) {
            RouteScoutVpnRuntime.state.first { it.isRunning || it.failureCode != null }
        }
        if (!runtime.isRunning) {
            return VpnEngineStatus(
                engineId = id,
                isRunning = false,
                isVerified = false,
                failureReason = runtime.failureCode ?: "LIBBOX_START_FAILED"
            )
        }

        val vpnNetwork = waitForVpnNetwork()
        if (vpnNetwork == null) {
            return VpnEngineStatus(
                engineId = id,
                isRunning = true,
                isVerified = false,
                failureReason = "VPN_INTERFACE_NOT_VISIBLE"
            )
        }

        val probe = diagnostics.runQuickCheck(vpnNetwork)
        val verified = probe.httpsLatencyMs != null || probe.siteChecks.any { it.isReachable }
        RouteScoutVpnRuntime.markVerification(appContext, verified)

        return VpnEngineStatus(
            engineId = id,
            isRunning = true,
            isVerified = verified,
            failureReason = if (verified) null else "TUNNEL_DATA_PATH_UNVERIFIED"
        )
    }

    override suspend fun disconnect() {
        val state = RouteScoutVpnRuntime.state.value
        if (!state.isRunning && !state.isStarting &&
            !RouteScoutVpnRuntime.mayBeActive(appContext)
        ) return

        RouteScoutVpnService.stop(appContext)
        withTimeout(20_000L) {
            RouteScoutVpnRuntime.state.first {
                !it.isRunning && !it.isStarting &&
                    !RouteScoutVpnRuntime.mayBeActive(appContext)
            }
        }
    }

    override suspend fun status(): VpnEngineStatus {
        val runtime = RouteScoutVpnRuntime.state.value
        val vpnNetwork = findVpnNetwork()
        val mayBeActive = RouteScoutVpnRuntime.mayBeActive(appContext)

        if (!runtime.isRunning && !runtime.isStarting && mayBeActive && vpnNetwork == null) {
            RouteScoutVpnRuntime.clearStaleMarker(appContext)
            return VpnEngineStatus(
                engineId = id,
                isRunning = false,
                isVerified = false
            )
        }

        val isRunning = runtime.isRunning || runtime.isStarting ||
            (mayBeActive && vpnNetwork != null)
        val verified = isRunning &&
            vpnNetwork != null &&
            runtime.isVerified &&
            runtime.lastVerifiedAtEpochMillis?.let {
                System.currentTimeMillis() - it <= STATUS_VERIFICATION_MAX_AGE_MS
            } == true

        return VpnEngineStatus(
            engineId = id,
            isRunning = isRunning,
            isVerified = verified,
            failureReason = when {
                !isRunning -> runtime.failureCode
                vpnNetwork == null -> "VPN_INTERFACE_NOT_VISIBLE"
                !verified -> runtime.failureCode ?: "TUNNEL_DATA_PATH_UNVERIFIED"
                else -> null
            }
        )
    }

    private suspend fun waitForVpnNetwork(): Network? = withTimeoutOrNull(20_000L) {
        while (true) {
            findVpnNetwork()?.let { return@withTimeoutOrNull it }
            delay(250L)
        }
        null
    }

    private fun findVpnNetwork(): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    private companion object {
        const val STATUS_VERIFICATION_MAX_AGE_MS = 90_000L
    }
}
