package com.routescout.mobile.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WireGuardManager(context: Context) {
    private val backend = GoBackend(context.applicationContext)
    private val tunnel = RouteScoutTunnel()

    suspend fun connect(configText: String) = withContext(Dispatchers.IO) {
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)))
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        backend.getState(tunnel) == Tunnel.State.UP
    }

    private class RouteScoutTunnel : Tunnel {
        override fun getName(): String = "RouteScout"

        override fun onStateChange(newState: Tunnel.State) {
            // The backend owns the authoritative tunnel state.
        }
    }
}
