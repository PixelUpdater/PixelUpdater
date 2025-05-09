/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2022-2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

package com.github.pixelupdater.pixelupdater

import android.app.Application
import android.content.Context
import android.util.Log
import com.github.pixelupdater.pixelupdater.updater.UpdaterJob
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.DelicateCoroutinesApi
import java.io.File

class MainApplication : Application() {
    private val TAG = MainApplication::class.java.simpleName

    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR).setTimeout(10))
        }
    }

    override fun onCreate() {
        super.onCreate()

        val oldCrashHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val logcatFile = File(getExternalFilesDir(null), "crash.log")

                Log.e(TAG, "Saving logcat to $logcatFile due to uncaught exception in $t", e)

                ProcessBuilder("logcat", "-d", "*:V")
                    .redirectOutput(logcatFile)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } finally {
                oldCrashHandler?.uncaughtException(t, e)
            }
        }

        // Enable Material You colors
        DynamicColors.applyToActivitiesIfAvailable(this)

        Notifications(this).updateChannels()

        UpdaterJob.schedulePeriodic(this, false)
    }

    override fun onTerminate() {
        super.onTerminate()
        // This is only called in emulator, but added for completeness
        cleanupScheduledJobs()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Clean up jobs when app is being forcefully removed from memory
        if (level == TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_MODERATE) {
            cleanupScheduledJobs()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun cleanupScheduledJobs() {
        Log.d(TAG, "Application terminating, cleaning up scheduled jobs")
        try {
            UpdaterJob.cancelAllJobs(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up scheduled jobs", e)
        }
    }
}
