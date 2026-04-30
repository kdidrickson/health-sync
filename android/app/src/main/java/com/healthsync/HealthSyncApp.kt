package com.healthsync

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.healthsync.data.SyncWorker
import com.healthsync.util.PreferencesHelper
import java.util.concurrent.TimeUnit

class HealthSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleSync()
    }

    private fun scheduleSync() {
        val workManager = WorkManager.getInstance(this)
        val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 24-hour recurring sync; KEEP prevents duplicate jobs on reinstall.
        val periodicRequest = PeriodicWorkRequest.Builder(
            SyncWorker::class.java,
            24, TimeUnit.HOURS,
        )
            .setConstraints(networkConstraint)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest,
        )

        // One-time immediate sync on first ever launch.
        if (!PreferencesHelper.isWorkEnqueued(this)) {
            PreferencesHelper.setWorkEnqueued(this, true)
            val immediateRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .build()
            workManager.enqueue(immediateRequest)
        }
    }
}
