package com.routescout.mobile.vpn

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object VpnCatalogWorkScheduler {
    private const val PERIODIC_WORK_NAME = "routescout-vpn-catalog-refresh"
    private const val INITIAL_WORK_NAME = "routescout-vpn-catalog-initial-refresh"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val manager = WorkManager.getInstance(appContext)

        val initialRequest = OneTimeWorkRequestBuilder<VpnCatalogRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(INITIAL_WORK_NAME, ExistingWorkPolicy.KEEP, initialRequest)

        val periodicRequest = PeriodicWorkRequestBuilder<VpnCatalogRefreshWorker>(
            15,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }
}
