package app.viberadio.podcasts

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.viberadio.VibeRadioApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Checks every podcast feed in the background, every few hours, only on an unmetered network
 * (Wi-Fi). Plain [JobScheduler], like the nightly export. Survives reboots. Stopped by the system
 * when the network goes; feeds already fetched are kept and the next run carries on.
 */
class FeedRefreshJob : JobService() {

    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as VibeRadioApp).container
        running = container.appScope.launch {
            runCatching {
                container.podcastRepository.loaded.first { it }
                container.podcastRepository.refresh(maxAgeMs = MIN_AGE_MS)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Carry on at the next period.
        return true
    }

    companion object {
        private const val JOB_ID = 0x6665_6564 // "feed"
        private const val PERIOD_MS = 3L * 60 * 60 * 1000

        /** A show fetched within the last hour (on resume, say) isn't fetched again. */
        private const val MIN_AGE_MS = 60L * 60 * 1000

        /** Schedules the job unless it already is. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, FeedRefreshJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
