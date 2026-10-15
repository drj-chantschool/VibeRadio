package app.viberadio.diagnostics

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
 * Settings → Debug → "Export nightly": one export a day while the phone is charging and idle.
 * Plain [JobScheduler], so there's no extra dependency. Survives reboots.
 */
class NightlyExportJob : JobService() {

    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as VibeRadioApp).container
        running = container.appScope.launch {
            // Developer mode may have been turned off since this was scheduled.
            if (container.diagnosticsSettings.await().nightly) container.diagnostics.export()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Try again at the next idle window.
        return true
    }

    companion object {
        private const val JOB_ID = 0x7669_6265 // "vibe"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Schedules or cancels the job to match [on]. */
        fun sync(context: Context, on: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, NightlyExportJob::class.java))
                    .setPeriodic(DAY_MS)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
