package com.routescout.mobile.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

sealed interface ServerHealthResult {
    data class TcpReachable(val latencyMs: Long) : ServerHealthResult
    data class TcpUnreachable(val reason: String) : ServerHealthResult
    data object UdpRequiresHandshake : ServerHealthResult
    data object InvalidProfile : ServerHealthResult
}

/**
 * A conservative preflight check. TCP endpoints can be tested with a socket connect.
 * UDP cannot be declared reachable without a real OpenVPN handshake, so it is reported
 * as unverified rather than being assigned a fabricated latency.
 */
class ServerHealthChecker {
    suspend fun check(server: FreeVpnServer): ServerHealthResult = withContext(Dispatchers.IO) {
        val config = try {
            String(Base64.getMimeDecoder().decode(server.openVpnConfigBase64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return@withContext ServerHealthResult.InvalidProfile
        }

        val remote = config.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 3 && it[0].equals("remote", ignoreCase = true) }
            ?: return@withContext ServerHealthResult.InvalidProfile

        val host = remote[1]
        val port = remote[2].toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return@withContext ServerHealthResult.InvalidProfile

        val transport = config.lineSequence()
            .firstOrNull { it.trim().startsWith("proto ", ignoreCase = true) }
            ?.trim()
            ?.substringAfter(' ')
            ?.lowercase()
            ?: "udp"

        if (!transport.contains("tcp")) {
            return@withContext ServerHealthResult.UdpRequiresHandshake
        }

        val startedAt = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            ServerHealthResult.TcpReachable(elapsedMs)
        } catch (error: Exception) {
            ServerHealthResult.TcpUnreachable(error.message ?: "TCP connection failed")
        }
    }

    companion object {
        private const val TCP_TIMEOUT_MS = 2_500
    }
}
