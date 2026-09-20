package ca.heeney.t3usage

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Background refresh: a persisted 15-minute periodic job plus one-off jobs for taps and updates. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            UsageFetcher.refresh(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val PERIODIC_ID = 1
        private const val NOW_ID = 2
        private const val SOON_ID = 3
        private const val PERIOD_MS = 15 * 60_000L

        private fun scheduler(context: Context): JobScheduler =
            context.getSystemService(JobScheduler::class.java)

        private fun builder(context: Context, id: Int): JobInfo.Builder =
            JobInfo.Builder(id, ComponentName(context, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

        fun ensurePeriodic(context: Context) = safely(context) { scheduler ->
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return@safely
            scheduler.schedule(
                builder(context, PERIODIC_ID)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancelAll(context: Context) = safely(context) { it.cancelAll() }

        /** Fetch as soon as possible (user tap); falls back to a regular job if expedited quota is exhausted. */
        fun fetchNow(context: Context) = safely(context) { scheduler ->
            val expedited = builder(context, NOW_ID).setExpedited(true).build()
            if (scheduler.schedule(expedited) != JobScheduler.RESULT_SUCCESS) {
                scheduler.schedule(builder(context, NOW_ID).build())
            }
        }

        /** Fetch after a delay, e.g. to pick up the refresh T3 Code itself triggers when opened. */
        fun fetchSoon(context: Context, delayMs: Long) = safely(context) { scheduler ->
            scheduler.schedule(
                builder(context, SOON_ID)
                    .setMinimumLatency(delayMs)
                    .build(),
            )
        }

        /** Scheduling failures (quota, platform quirks) must never take the widget provider down. */
        private inline fun safely(context: Context, block: (JobScheduler) -> Unit) {
            try {
                block(scheduler(context))
            } catch (error: RuntimeException) {
                UsageStore(context).saveFailure("Scheduling: ${error.message}", System.currentTimeMillis())
            }
        }
    }
}
