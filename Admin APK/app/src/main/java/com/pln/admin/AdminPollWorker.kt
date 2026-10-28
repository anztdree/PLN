package com.pln.admin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Pemeriksa latar belakang — notifikasi status bar walau aplikasi tertutup. */
class AdminPollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        AdminPoller.poll(applicationContext, notify = true)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AdminPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "admin-poll",
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
