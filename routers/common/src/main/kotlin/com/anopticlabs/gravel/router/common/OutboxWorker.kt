package com.anopticlabs.gravel.router.common

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sends what the outbox holds, oldest first, once there is a network: a sent delivery leaves the
 * outbox, a refused one is set aside with its reason and reported to the user, one to retry stays,
 * and the run ends as a retry with WorkManager's backoff while any stays. A delivery still not
 * sent on the run's [MAX_RUNS]th try is set aside too, so one answer that is read as transient
 * but never changes cannot hold the line for good; the settings screen offers it again.
 */
class OutboxWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val app = applicationContext as? RouterApp ?: return Result.failure()
        val outbox = Outbox(applicationContext)
        var retry = false
        for ((file, delivery) in outbox.list()) {
            when (val result = send(app, delivery)) {
                SendResult.Sent -> outbox.remove(file)
                is SendResult.Refused -> setAside(outbox, file, result.reason)
                is SendResult.Retry -> if (runAttemptCount + 1 >= MAX_RUNS) {
                    setAside(outbox, file, "Not sent after $MAX_RUNS tries: ${result.reason}")
                } else {
                    Log.i(TAG, "a delivery waits: ${result.reason}")
                    retry = true
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private fun setAside(outbox: Outbox, file: java.io.File, reason: String) {
        Log.w(TAG, "a delivery was set aside: $reason")
        outbox.keepNotSent(file, reason)
        Failures.notifyRefused(applicationContext, reason)
    }

    // A sender that throws has met something it did not expect; the delivery is kept for another try.
    private fun send(app: RouterApp, delivery: Delivery): SendResult = try {
        app.sender.send(delivery)
    } catch (e: RuntimeException) {
        Log.w(TAG, "a send failed: ${e::class.simpleName}")
        SendResult.Retry(e::class.simpleName ?: "failure")
    }

    companion object {
        private const val TAG = "OutboxWorker"
        private const val WORK_NAME = "outbox"

        /** Runs of one request before what still waits is set aside: with the backoff below, about half a day. */
        const val MAX_RUNS = 12

        /** Asks for a run once there is a network; a run under way is followed by one more, so a delivery added meanwhile is not missed. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
