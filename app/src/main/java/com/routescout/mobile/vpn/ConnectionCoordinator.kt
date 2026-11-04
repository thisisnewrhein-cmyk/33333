package com.routescout.mobile.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes tunnel lifecycle changes across all registered engines.
 * A successful disconnect() call is not proof that Android's VPN interface
 * has been released; every engine must also report isRunning=false.
 */
class ConnectionCoordinator(engines: Collection<VpnEngine>) {
    private val enginesById: Map<VpnEngineId, VpnEngine> =
        engines.associateBy { it.id }.also { registry ->
            require(registry.size == engines.size) { "VPN engine IDs must be unique" }
        }

    private val mutex = Mutex()

    suspend fun connect(engineId: VpnEngineId, profile: String): VpnEngineStatus = mutex.withLock {
        require(profile.isNotBlank()) { "VPN profile must not be empty" }
        val engine = enginesById[engineId]
            ?: throw IllegalArgumentException("VPN engine is not registered: $engineId")

        // Inspect all engines to catch tunnels left behind by service/process interruptions.
        disconnectAllAndVerifyLocked()

        try {
            engine.connect(profile)

            // Re-read live state; do not trust a possibly stale connect() result.
            val observed = engine.status()
            if (observed.engineId != null && observed.engineId != engineId) {
                throw IllegalStateException("VPN engine returned a status for a different engine")
            }
            if (!observed.isConnected) throw EngineConnectionException(observed)
            observed
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { disconnectAndVerify(engine) }
                    .exceptionOrNull()
                    ?.let(cancelled::addSuppressed)
            }
            throw cancelled
        } catch (error: Exception) {
            runCatching { disconnectAndVerify(engine) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectAllAndVerifyLocked()
    }

    /**
     * Reports live state for every engine. Query failures propagate instead of
     * being misreported as a stopped tunnel.
     */
    suspend fun currentStatus(): VpnEngineStatus = mutex.withLock {
        val observed = enginesById.map { (id, engine) ->
            val status = engine.status()
            if (status.engineId != null && status.engineId != id) {
                throw IllegalStateException("VPN engine returned a status for a different engine")
            }
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
        val failures = mutableListOf<Exception>()

        for ((id, engine) in enginesById) {
            try {
                disconnectAndVerify(engine)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += IllegalStateException("Could not confirm $id shutdown", error)
            }
        }

        if (failures.isNotEmpty()) {
            val failure = IllegalStateException(
                "At least one VPN engine may still own Android's VPN interface; refusing to start another tunnel"
            )
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun disconnectAndVerify(engine: VpnEngine) {
        var disconnectFailure: Exception? = null

        try {
            engine.disconnect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            disconnectFailure = error
        }

        val observed = try {
            engine.status()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (statusFailure: Exception) {
            disconnectFailure?.let(statusFailure::addSuppressed)
            throw IllegalStateException("Unable to verify VPN engine shutdown", statusFailure)
        }

        if (observed.isRunning) {
            val failure = IllegalStateException(
                "VPN engine still reports an active tunnel after disconnect()"
            )
            disconnectFailure?.let(failure::addSuppressed)
            throw failure
        }
    }
}

class EngineConnectionException(
    val engineStatus: VpnEngineStatus
) : IllegalStateException(
    engineStatus.failureReason ?: "VPN engine did not verify the remote connection"
)
