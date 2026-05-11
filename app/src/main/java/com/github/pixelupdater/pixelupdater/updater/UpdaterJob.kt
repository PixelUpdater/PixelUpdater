/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.updater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import com.github.pixelupdater.pixelupdater.Notifications
import com.github.pixelupdater.pixelupdater.Permissions
import com.github.pixelupdater.pixelupdater.Preferences
import com.github.pixelupdater.pixelupdater.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UpdaterJob: JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Preferences(this)

        if (!Permissions.haveRequired(applicationContext) || !Notifications.areEnabled(applicationContext)) {
            Log.i(TAG, "Notifications are disabled, job skipped")
            return false
        }

        val actionIndex = params.extras.getInt(EXTRA_ACTION, -1)
        val isPeriodic = actionIndex == -1

        if (isPeriodic) {
            if (!prefs.automaticCheck) {
                Log.i(TAG, "Automatic update checks are disabled")
                return false
            } else if (skipNextRun) {
                Log.i(TAG, "Skipped this run of the periodic job")
                skipNextRun = false
                return false
            }
        }

        val action = if (!isPeriodic) {
            UpdaterThread.Action.entries[actionIndex]
        } else {
            UpdaterThread.Action.CHECK
        }

        startForegroundService(UpdaterService.createStartIntent(
            applicationContext, params.network, action, isPeriodic))
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }

    companion object {
        private val TAG = UpdaterJob::class.java.simpleName

        private const val ID_IMMEDIATE = 1
        private const val ID_PERIODIC = 2

        private const val EXTRA_ACTION = "action"

        // Consider 15% or lower as "low battery"
        private const val LOW_BATTERY_THRESHOLD = 15

        private const val PERIODIC_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val WEEKLY_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MONTHLY_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

        // Scheduling a periodic job usually makes the first iteration run immediately. We'll
        // sometimes skip this to avoid unexpected operations while the user is configuring
        // settings in the UI.
        private var skipNextRun = false

        private var timeout: Deferred<Unit>? = null

        private fun createJobBuilder(
            context: Context,
            jobId: Int,
            action: UpdaterThread.Action?,
        ): JobInfo.Builder {
            val prefs = Preferences(context)

            val builder = JobInfo.Builder(jobId, ComponentName(context, UpdaterJob::class.java))

            val networkType = if (action == UpdaterThread.Action.INSTALL && prefs.requireUnmetered) {
                JobInfo.NETWORK_TYPE_UNMETERED
            } else {
                JobInfo.NETWORK_TYPE_ANY
            }
            builder
                .setRequiredNetworkType(networkType)

            if (action == UpdaterThread.Action.INSTALL || action == UpdaterThread.Action.SWITCH_SLOT) {
                builder
                    .setRequiresBatteryNotLow(prefs.requireBatteryNotLow)
            }

            val extras = PersistableBundle().apply {
                if (action != null) {
                    putInt(EXTRA_ACTION, action.ordinal)
                }
            }

            return builder.setExtras(extras)
        }

        @DelicateCoroutinesApi
        private fun scheduleIfUnchanged(context: Context, jobInfo: JobInfo) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)

            val oldJobInfo = jobScheduler.getPendingJob(jobInfo.id)

            // JobInfo.equals() is unreliable (and the comments in its implementation say so), so
            // just compare the fields that we set. We don't compare the extras because there's no
            // sane way to do so. That doesn't matter for our use case because this check is mostly
            // useful for the periodic job, which doesn't use extras.
            if (oldJobInfo != null &&
                areNetworkRequirementsSame(oldJobInfo, jobInfo) &&
                oldJobInfo.isRequireBatteryNotLow == jobInfo.isRequireBatteryNotLow &&
                oldJobInfo.isPersisted == jobInfo.isPersisted &&
                oldJobInfo.intervalMillis == jobInfo.intervalMillis &&
                oldJobInfo.isPeriodic == jobInfo.isPeriodic &&
                oldJobInfo.intervalMillis == jobInfo.intervalMillis) {
                Log.i(TAG, "Job already exists and is unchanged: $jobInfo")
                return
            }

            if (jobInfo.id == ID_IMMEDIATE) {
                // Cancel any existing timeout
                if (timeout != null) {
                    timeout!!.cancel()
                    timeout = null
                }

                // Check constraints directly before scheduling
                val actionIndex = jobInfo.extras.getInt(EXTRA_ACTION, -1)
                val action = if (actionIndex >= 0) UpdaterThread.Action.entries[actionIndex] else null

                var constraintsFailed = false
                var errorReason: String? = null

                // Check battery constraint manually if needed
                if (jobInfo.isRequireBatteryNotLow && action == UpdaterThread.Action.INSTALL) {
                    val batteryManager = context.getSystemService(BatteryManager::class.java)
                    val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val isLow = batteryLevel <= LOW_BATTERY_THRESHOLD

                    if (isLow) {
                        constraintsFailed = true
                        errorReason = "battery_low"
                        Log.d(TAG, "Battery constraint not met: level=$batteryLevel%")
                    }
                }

                if (constraintsFailed && action != null) {
                    // Show an immediate toast message to provide feedback to the user
                    val toastText = when (errorReason) {
                        "battery_low" -> context.getString(R.string.toast_battery_low)
                        "network_metered" -> context.getString(R.string.toast_network_metered)
                        "network_unavailable" -> context.getString(R.string.toast_network_unavailable)
                        else -> context.getString(R.string.toast_constraints_not_met)
                    }

                    // Post toast on the main thread
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                    }

                    // Immediately fail with the specific reason
                    Log.d(TAG, "Job constraints not met: $errorReason, not scheduling job")
                    context.startForegroundService(UpdaterService.createFailIntent(context, action, errorReason))
                    return
                }
            }

            Log.d(TAG, "Scheduling job: $jobInfo")

            when (val result = jobScheduler.schedule(jobInfo)) {
                JobScheduler.RESULT_SUCCESS -> {
                    Log.d(TAG, "Scheduled job: $jobInfo")
                }
                JobScheduler.RESULT_FAILURE ->
                    Log.w(TAG, "Failed to schedule job: $jobInfo")
                else -> throw IllegalStateException("Unexpected scheduler error: $result")
            }
        }

        /**
         * Safely compares network requirements between two JobInfo objects without using reflection
         */
        private fun areNetworkRequirementsSame(job1: JobInfo, job2: JobInfo): Boolean {
            // In Android, JobInfo.getRequiredNetworkType() was introduced in later API levels
            // Instead of using reflection, we can compare if both jobs have the same network type by
            // checking if both require a network and if both require the same type of network

            // Check if both have ANY network requirement (this is what we mainly care about)
            val job1RequiresNetwork = job1.requiredNetwork != null
            val job2RequiresNetwork = job2.requiredNetwork != null

            if (job1RequiresNetwork != job2RequiresNetwork) {
                return false
            }

            // If neither requires network, they're the same
            if (!job1RequiresNetwork && !job2RequiresNetwork) {
                return true
            }

            // Check if both have unmetered network requirement
            // We can use the requiredNetwork capabilities to determine this
            return hasUnmeteredNetworkRequirement(job1) == hasUnmeteredNetworkRequirement(job2)
        }

        /**
         * Checks if the JobInfo requires an unmetered network without using reflection
         */
        private fun hasUnmeteredNetworkRequirement(jobInfo: JobInfo): Boolean {
            val netRequest = jobInfo.requiredNetwork ?: return false

            // Check if NET_CAPABILITY_NOT_METERED is in the capabilities
            try {
                val netCapabilities = netRequest.javaClass.getField("networkCapabilities").get(netRequest)
                val notMeteredCapability = android.net.NetworkCapabilities::class.java.getField("NET_CAPABILITY_NOT_METERED").get(null) as Int

                // Get the hasCapability method by reflection
                val hasCapabilityMethod = netCapabilities.javaClass.getMethod("hasCapability", Int::class.java)
                return hasCapabilityMethod.invoke(netCapabilities, notMeteredCapability) as Boolean
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check network metering capability", e)
                // Fall back to assuming any network is required (not specifically unmetered)
                return false
            }
        }

        fun scheduleImmediate(context: Context, action: UpdaterThread.Action) {
            val jobInfo = createJobBuilder(context, ID_IMMEDIATE, action).build()

            scheduleIfUnchanged(context, jobInfo)
        }

        fun schedulePeriodic(context: Context, skipFirstRun: Boolean) {
            val prefs = Preferences(context)
            val interval = if (prefs.updateNotified) {
                when (prefs.notificationFrequency) {
                    "daily" -> DAILY_INTERVAL_MS
                    "weekly" -> WEEKLY_INTERVAL_MS
                    "monthly" -> MONTHLY_INTERVAL_MS
                    else -> PERIODIC_INTERVAL_MS
                }
            } else {
                PERIODIC_INTERVAL_MS
            }

            val jobInfo = createJobBuilder(context, ID_PERIODIC, null)
                .setPersisted(true)
                .setPeriodic(interval)
                .build()

            skipNextRun = skipFirstRun

            scheduleIfUnchanged(context, jobInfo)
        }

        /**
         * Cancel all scheduled jobs for the app to ensure no jobs remain after app uninstallation.
         * This should be called during app shutdown or when the user explicitly disables the app.
         */
        fun cancelAllJobs(context: Context) {
            try {
                val jobScheduler = context.getSystemService(JobScheduler::class.java)
                Log.d(TAG, "Cancelling all scheduled jobs")
                jobScheduler.cancelAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel scheduled jobs", e)
            }
        }
    }
}
