package com.routescout.mobile.vpn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class VpnCatalogRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val servers = VpnGateRepository().refresh()
            val checker = ServerHealthChecker()
            val checks = coroutineScope {
                ServerSelectionEngine.rankedServers(servers)
                    .take(MAX_HEALTH_CHECKS)
                    .map { server -> async { server.key to checker.check(server) } }
                    .awaitAll()
                    .toMap()
            }
            VpnCatalogCache(applicationContext).save(
                servers = servers,
                healthResults = checks,
                updatedAtMs = System.currentTimeMillis()
            )
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_HEALTH_CHECKS = 12
        const val MAX_RETRIES = 3
    }
}
