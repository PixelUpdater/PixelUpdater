/*
 * SPDX-FileCopyrightText: 2022-2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

package com.github.pixelupdater.pixelupdater

import android.app.Application
import android.util.Log
import com.github.pixelupdater.pixelupdater.updater.UpdaterJob
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import java.io.File

class MainApplication : Application() {
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

    companion object {
        private val TAG = MainApplication::class.java.simpleName

        init {
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR))
        }
    }
}
