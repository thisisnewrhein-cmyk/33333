package com.routescout.mobile.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes all RouteScout tunnel lifecycle operations.
 * Unknown tunnel state is unsafe: a new engine is not started until shutdown is verified.
 */
class ConnectionCoordinator(engines: Collection<VpnEngine>) {
    private val enginesById: Map<VpnEngineId, VpnEngine> =
        engines.associateBy { it.id }.also { registry ->
            require(engines.isNotEmpty()) { "At least one VPN engine must be registered" }
            require(registry.size == engines.size) { "VPN engine IDs must be unique" }
        }

    private val mutex = Mutex()

    suspend fun connect(engineId: VpnEngineId, profile: String): VpnEngineStatus = mutex.withLock {
        require(profile.isNotBlank()) { "VPN profile must not be empty" }
        val engine = enginesById[engineId]
            ?: throw IllegalArgumentException("VPN engine is not registered: $engineId")

        disconnectAllAndVerifyLocked()

        try {
            val startResult = engine.connect(profile)
            validateEngineStatus(engineId, startResult)
            if (!startResult.isConnected) throw EngineConnectionException(startResult)

            // Re-read live state; a connect() result can become stale immediately.
            val observed = engine.status()
            validateEngineStatus(engineId, observed)
            if (!observed.isConnected) throw EngineConnectionException(observed)
            observed
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    disconnectAndVerify(engine)
                } catch (cleanupFailure: Exception) {
                    cancelled.addSuppressed(cleanupFailure)
                }
            }
            throw cancelled
        } catch (error: Exception) {
            try {
                disconnectAndVerify(engine)
            } catch (cleanupFailure: Exception) {
                error.addSuppressed(cleanupFailure)
            }
            throw error
        }
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectAllAndVerifyLocked()
    }

    suspend fun currentStatus(): VpnEngineStatus = mutex.withLock {
        val observed = enginesById.map { (id, engine) ->
            val status = engine.status()
            validateEngineStatus(id, status)
            status
        }
        val running = observed.filter { it.isRunning }
        when (running.size) {
            0 -> VpnEngineStatus.stopped()
            1 -> running.single()
            else -> throw IllegalStateException(
                "Multiple VPN engines report an active tunnel; connection state is unsafe"
            )
        }
    }

    private suspend fun disconnectAllAndVerifyLocked() {
        for ((id, engine) in enginesById) {
            val before = try {
                engine.status()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            val mayBeRunning = before == null || before.engineId != id || before.isRunning
            if (mayBeRunning) {
                try {
                    engine.disconnect()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed stop call is acceptable only if the live state below
                    // proves that the VPN interface has actually been released.
                }
            }
        }

        val remaining = enginesById.map { (id, engine) ->
            val status = engine.status()
            validateEngineStatus(id, status)
            status
        }.filter { it.isRunning }

        if (remaining.isNotEmpty()) {
            throw IllegalStateException(
                "A VPN engine may still own Android's VPN interface; refusing to start another tunnel"
            )
        }
    }

    private suspend fun disconnectAndVerify(engine: VpnEngine) {
        val before = try {
            engine.status()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        if (before == null || before.engineId != engine.id || before.isRunning) {
            try {
                engine.disconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Verify below; a stopped live state is sufficient evidence of release.
            }
        }

        val observed = engine.status()
        validateEngineStatus(engine.id, observed)
        if (observed.isRunning) {
            throw IllegalStateException(
                "VPN engine still reports an active tunnel after disconnect()"
            )
        }
    }

    private fun validateEngineStatus(expectedId: VpnEngineId, status: VpnEngineStatus) {
        if (status.engineId != expectedId) {
            throw IllegalStateException(
                "VPN engine identity mismatch: expected " + expectedId + ", received " + status.engineId
            )
        }
    }
}

class EngineConnectionException(
    val engineStatus: VpnEngineStatus
) : IllegalStateException(
    engineStatus.failureReason ?: "VPN engine did not verify the remote connection"
)
