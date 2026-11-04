package com.routescout.mobile.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes tunnel lifecycle changes so RouteScout never intentionally starts
 * one engine while another registered engine may still own Android's VPN slot.
 */
class ConnectionCoordinator(engines: Collection<VpnEngine>) {
    private val enginesById: Map<VpnEngineId, VpnEngine> =
        engines.associateBy { it.id }.also { registry ->
            require(registry.size == engines.size) { "VPN engine IDs must be unique" }
        }

    private val mutex = Mutex()
    private var activeEngine: VpnEngine? = null

    suspend fun connect(engineId: VpnEngineId, profile: String): VpnEngineStatus = mutex.withLock {
        require(profile.isNotBlank()) { "VPN profile must not be empty" }

        val engine = enginesById[engineId]
            ?: throw IllegalArgumentException("VPN engine is not registered: $engineId")

        // If any engine cannot confirm shutdown, fail closed and do not start another.
        disconnectAllLocked()

        try {
            val result = engine.connect(profile)
            if (result.engineId != engineId) {
                throw IllegalStateException("VPN engine returned a status for a different engine")
            }
            if (!result.isConnected) {
                throw EngineConnectionException(result)
            }

            activeEngine = engine
            result
        } catch (cancelled: CancellationException) {
            activeEngine = null
            withContext(NonCancellable) {
                runCatching { engine.disconnect() }
            }
            throw cancelled
        } catch (error: Exception) {
            activeEngine = null
            try {
                engine.disconnect()
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectAllLocked()
    }

    suspend fun currentStatus(): VpnEngineStatus = mutex.withLock {
        activeEngine?.status() ?: VpnEngineStatus.stopped()
    }

    private suspend fun disconnectAllLocked() {
        val failures = mutableListOf<Exception>()

        for (engine in enginesById.values) {
            try {
                engine.disconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += error
            }
        }

        if (failures.isNotEmpty()) {
            val failure = IllegalStateException(
                "Could not confirm shutdown of every VPN engine; refusing to start another tunnel"
            )
            failures.forEach(failure::addSuppressed)
            throw failure
        }

        activeEngine = null
    }
}

class EngineConnectionException(
    val engineStatus: VpnEngineStatus
) : IllegalStateException(
    engineStatus.failureReason ?: "VPN engine did not verify the remote connection"
)
