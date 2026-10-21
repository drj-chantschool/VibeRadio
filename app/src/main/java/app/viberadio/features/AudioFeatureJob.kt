package app.viberadio.features

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.viberadio.VibeRadioApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Analyses songs that are missing audio feature blocks while the phone is charging and idle
 * (Similarity plan, Phase 3). Stopped by the system when either ends; what was done is kept and
 * the next run carries on. Plain [JobScheduler], like the nightly export. Survives reboots.
 */
class AudioFeatureJob : JobService() {

    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as VibeRadioApp).container
        running = container.appScope.launch {
            runCatching { container.analyseSongs() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Carry on at the next charging, idle window.
        return true
    }

    companion object {
        private const val JOB_ID = 0x6665_6174 // "feat"
        private const val PERIOD_MS = 6L * 60 * 60 * 1000

        /** Schedules the job unless it already is. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, AudioFeatureJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
