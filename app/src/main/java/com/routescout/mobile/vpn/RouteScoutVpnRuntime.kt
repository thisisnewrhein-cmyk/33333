// SPDX-License-Identifier: GPL-3.0-only

package com.routescout.mobile.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RouteScoutVpnRuntimeState(
    val isStarting: Boolean = false,
    val isRunning: Boolean = false,
    val isVerified: Boolean = false,
    val failureCode: String? = null,
    val lastVerifiedAtEpochMillis: Long? = null
)

/**
 * In-process state plus a conservative persisted marker. If the process is recreated
 * while Android still exposes a VPN network, the adapter reports an unknown active tunnel.
 */
object RouteScoutVpnRuntime {
    private const val PREFS = "routescout_vpn_runtime"
    private const val KEY_MAY_BE_ACTIVE = "singbox_may_be_active"

    private val mutableState = MutableStateFlow(RouteScoutVpnRuntimeState())
    val state: StateFlow<RouteScoutVpnRuntimeState> = mutableState

    @Synchronized
    fun markStarting(context: Context) {
        preferences(context).edit().putBoolean(KEY_MAY_BE_ACTIVE, true).apply()
        mutableState.value = RouteScoutVpnRuntimeState(isStarting = true)
    }

    @Synchronized
    fun markRunning(context: Context) {
        preferences(context).edit().putBoolean(KEY_MAY_BE_ACTIVE, true).apply()
        mutableState.value = RouteScoutVpnRuntimeState(isRunning = true)
    }

    @Synchronized
    fun markVerification(context: Context, verified: Boolean) {
        val current = mutableState.value
        mutableState.value = current.copy(
            isStarting = false,
            isVerified = verified,
            failureCode = if (verified) null else "TUNNEL_DATA_PATH_UNVERIFIED",
            lastVerifiedAtEpochMillis = if (verified) System.currentTimeMillis() else null
        )
    }

    @Synchronized
    fun markFailure(context: Context, code: String) {
        preferences(context).edit().putBoolean(KEY_MAY_BE_ACTIVE, false).apply()
        mutableState.value = RouteScoutVpnRuntimeState(failureCode = code)
    }

    @Synchronized
    fun markStopped(context: Context) {
        preferences(context).edit().putBoolean(KEY_MAY_BE_ACTIVE, false).apply()
        mutableState.value = RouteScoutVpnRuntimeState()
    }

    fun mayBeActive(context: Context): Boolean =
        preferences(context).getBoolean(KEY_MAY_BE_ACTIVE, false)

    @Synchronized
    fun clearStaleMarker(context: Context) {
        preferences(context).edit().putBoolean(KEY_MAY_BE_ACTIVE, false).apply()
        if (!mutableState.value.isRunning) mutableState.value = RouteScoutVpnRuntimeState()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
