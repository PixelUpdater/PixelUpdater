/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.updater

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.PowerManager
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.content.IntentCompat
import com.github.pixelupdater.pixelupdater.Notifications
import com.github.pixelupdater.pixelupdater.Notifications.Companion.ID_INDEXED
import com.github.pixelupdater.pixelupdater.Preferences
import com.github.pixelupdater.pixelupdater.R
import com.github.pixelupdater.pixelupdater.extension.toSingleLineString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UpdaterService : Service(), UpdaterThread.UpdaterThreadListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Preferences
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifications: Notifications

    private var updaterThread: UpdaterThread? = null
    private var updaterAction: UpdaterThread.Action? = null
    private var silenceForPeriodic = false
    private var progressState: ProgressState? = null
    private var prevResult: UpdaterThread.Result? = null

    override fun onCreate() {
        super.onCreate()

        prefs = Preferences(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        notifications = Notifications(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received intent: $intent")

        try {
            when (val action = intent?.action) {
                ACTION_START -> {
                    // We're launched by startForegroundService(). Android requires the foreground
                    // notification to be shown, even if stopService() is called before this method
                    // returns.
                    updateForegroundNotification(false)
                    startUpdate(intent)
                }
                ACTION_FAIL -> {
                    updateForegroundNotification(true)

                    val extraAction = IntentCompat.getParcelableExtra(
                        intent, EXTRA_ACTION, UpdaterThread.Action::class.java)
                    notifications.dismissNotifications()

                    val messageResId = if (extraAction == UpdaterThread.Action.INSTALL) {
                        if (prefs.requireUnmetered && prefs.requireBatteryNotLow) {
                            R.string.notification_job_failed_both_message
                        } else if (prefs.requireUnmetered) {
                            R.string.notification_job_failed_network_message
                        } else {
                            R.string.notification_job_failed_battery_message
                        }
                    } else {
                        R.string.notification_job_failed_battery_message
                    }

                    notifyAlert(UpdaterThread.UpdateFailed(
                        getString(messageResId),
                        extraAction
                    ))
                }
                ACTION_PAUSE, ACTION_RESUME -> {
                    updaterThread?.isPaused = action == ACTION_PAUSE
                    updateForegroundNotification(true)
                }
                ACTION_CANCEL -> {
                    updaterThread?.cancel()
                    prevResult = null
                }
                ACTION_REBOOT -> {
                    getSystemService(PowerManager::class.java).reboot(null)
                }
                ACTION_SWITCH_SLOT -> {
                    updateForegroundNotification(true)
                    startUpdate(intent)
                }
                ACTION_SCHEDULE -> {
                    val extraAction = IntentCompat.getParcelableExtra(intent, EXTRA_ACTION, UpdaterThread.Action::class.java)!!
                    val target = intent.extras?.getString("target")
                    if (extraAction == UpdaterThread.Action.INSTALL && target != null) {
                        prefs.targetOta = target
                    }

                    UpdaterJob.scheduleImmediate(this, extraAction)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle intent: $intent", e)
            notifyAlert(UpdaterThread.UpdateFailed(e.toSingleLineString()))
        }

        tryStop()
        return START_NOT_STICKY
    }

    private fun startUpdate(intent: Intent) {
        if (updaterThread == null) {
            Log.d(TAG, "Creating new updater thread")

            // IntentCompat is required due to an Android 13 bug that's only fixed in 14+
            // https://issuetracker.google.com/issues/274185314
            val network = IntentCompat.getParcelableExtra(
                intent, EXTRA_NETWORK, Network::class.java)
            val action = IntentCompat.getParcelableExtra(
                intent, EXTRA_ACTION, UpdaterThread.Action::class.java)!!
            val silent = intent.getBooleanExtra(EXTRA_SILENT, false)

            // Clear all stale alert notifications when initiated by the user. For the periodic job,
            // we want to leave the existing notification visible so that it can be updated with the
            // new status without re-alerting the user when onlySendOnce is true.
            if (!silent) {
                notifications.dismissNotifications()
            }

            if (action != UpdaterThread.Action.REVERT && action != UpdaterThread.Action.NO_ROOT && network == null) {
                if (!silent) {
                    notifyAlert(UpdaterThread.NetworkUnavailable)
                }
                return
            }

            if (action == UpdaterThread.Action.CHECK) {
                val pendingSwitchSlot = when (prevResult) {
                    is UpdaterThread.UpdateFailed -> (prevResult as UpdaterThread.UpdateFailed).action == UpdaterThread.Action.SWITCH_SLOT
                    is UpdaterThread.CheckSkipped -> (prevResult as UpdaterThread.CheckSkipped).action == UpdaterThread.Action.SWITCH_SLOT
                    else -> prevResult == UpdaterThread.UpdateNeedSwitchSlot
                }
                val pendingReboot = when (prevResult) {
                    is UpdaterThread.CheckSkipped -> (prevResult as UpdaterThread.CheckSkipped).action == UpdaterThread.Action.REBOOT
                    else -> prevResult == UpdaterThread.UpdateSucceeded || prevResult == UpdaterThread.UpdateNeedReboot
                }
                if (pendingSwitchSlot || pendingReboot) {
                    if (!silent) {
                        val messageResId = if (pendingSwitchSlot) {
                            R.string.notification_check_skipped_needs_switch_slot_message
                        } else {
                            R.string.notification_check_skipped_needs_reboot_message
                        }
                        val pendingAction = if (pendingSwitchSlot) {
                            UpdaterThread.Action.SWITCH_SLOT
                        } else {
                            UpdaterThread.Action.REBOOT
                        }

                        notifyAlert(UpdaterThread.CheckSkipped(
                            getString(messageResId),
                            pendingAction
                        ))
                    }
                    return
                }
            }

            updateForegroundNotification(true)

            updaterThread = UpdaterThread(this, network, action, this).apply {
                start()
            }

            updaterAction = action
            silenceForPeriodic = silent

            Log.d(TAG, "More silent behavior for periodic job: $silenceForPeriodic")
        }
    }

    private fun tryStop() {
        Log.d(TAG, "Trying to stop service if possible")

        if (updaterThread == null) {
            Log.d(TAG, "Updater thread completed; stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @UiThread
    private fun threadExited() {
        updaterThread = null
        updaterAction = null
        silenceForPeriodic = false
        progressState = null
        tryStop()
    }

    private fun createActionIntent(action: String): Intent =
        Intent(this, this::class.java).apply {
            this.action = action
        }

    @UiThread
    private fun updateForegroundNotification(showImmediately: Boolean) {
        val state = progressState
        Log.d(TAG, "Updating foreground notification for state: $state")

        val titleResId = when (state?.type) {
            UpdaterThread.ProgressType.INIT, null -> R.string.notification_state_init
            UpdaterThread.ProgressType.CHECK -> R.string.notification_state_check
            UpdaterThread.ProgressType.UPDATE -> R.string.notification_state_install
            UpdaterThread.ProgressType.VERIFY -> R.string.notification_state_verify
            UpdaterThread.ProgressType.FINALIZE -> R.string.notification_state_finalize
        }
        val actionResIds = mutableListOf<Int>()
        val actionIntents = mutableListOf<Intent>()

        updaterThread?.let { thread ->
            if (thread.isPaused) {
                actionResIds.add(R.string.notification_action_resume)
                actionIntents.add(createActionIntent(ACTION_RESUME))
            } else {
                actionResIds.add(R.string.notification_action_pause)
                actionIntents.add(createActionIntent(ACTION_PAUSE))
            }
            actionResIds.add(R.string.notification_action_cancel)
            actionIntents.add(createActionIntent(ACTION_CANCEL))
        }

        val notification = notifications.createPersistentNotification(
            titleResId,
            null,
            R.drawable.ic_notifications,
            actionResIds.zip(actionIntents),
            state?.current,
            state?.max,
            showImmediately,
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        startForeground(Notifications.ID_PERSISTENT, notification, type)
    }

    @UiThread
    private fun notifyAlert(result: UpdaterThread.Result) {
        val channel: String
        val onlyAlertOnce: Boolean
        val titleResId: Int
        val message: String?
        val showInstall: Boolean
        val showRetry: Boolean
        val showReboot: Boolean
        val showSwitchSlot: Boolean
        val showRevert: Boolean
        var id: Int? = null

        when (result) {
            is UpdaterThread.UpdateAvailable -> {
                channel = Notifications.CHANNEL_ID_CHECK
                // Only bug the user once while the notification is still shown
                onlyAlertOnce = true
                titleResId = R.string.notification_update_available
                message = result.version
                showInstall = true
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
                id = ID_INDEXED + result.index
            }
            UpdaterThread.UpdateUnnecessary -> {
                if (silenceForPeriodic) {
                    // No need to harass the user with "OS is already up to date" notifications when
                    // running from the periodic job.
                    return
                }

                channel = Notifications.CHANNEL_ID_CHECK
                onlyAlertOnce = false
                titleResId = R.string.notification_update_unnecessary
                message = null
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.UpdateSucceeded, UpdaterThread.UpdateNeedReboot -> {
                channel = Notifications.CHANNEL_ID_SUCCESS
                // Only bug the user once while the notification is still shown
                onlyAlertOnce = result is UpdaterThread.UpdateNeedReboot
                titleResId = R.string.notification_update_succeeded
                message = null
                showInstall = false
                showRetry = false
                showReboot = true
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.UpdateNeedSwitchSlot -> {
                channel = Notifications.CHANNEL_ID_SUCCESS
                // Only bug the user once while the notification is still shown
                onlyAlertOnce = result is UpdaterThread.UpdateNeedSwitchSlot
                titleResId = R.string.notification_update_needs_switch_slot
                message = null
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = true
                showRevert = false
            }
            UpdaterThread.UpdateReverted -> {
                channel = Notifications.CHANNEL_ID_SUCCESS
                onlyAlertOnce = false
                titleResId = R.string.notification_update_reverted
                message = null
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.UpdateCancelled -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = false
                titleResId = R.string.notification_update_cancelled
                message = null
                showInstall = false
                showRetry = true
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            is UpdaterThread.UpdateFailed -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = false
                titleResId = R.string.notification_update_failed
                message = result.errorMsg
                showInstall = result.action == UpdaterThread.Action.INSTALL
                showRetry = result.action == null
                showReboot = false
                showSwitchSlot = result.action == UpdaterThread.Action.SWITCH_SLOT
                showRevert = false
            }
            is UpdaterThread.CheckSkipped -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = false
                titleResId = R.string.notification_check_skipped_title
                message = result.errorMsg
                showInstall = false
                showRetry = false
                showReboot = result.action == UpdaterThread.Action.REBOOT
                showSwitchSlot = result.action == UpdaterThread.Action.SWITCH_SLOT
                showRevert = false
            }
            is UpdaterThread.UpdatePatchFailed -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = false
                titleResId = R.string.notification_update_patch_failed
                message = result.errorMsg
                showInstall = false
                showRetry = true
                showReboot = false
                showSwitchSlot = false
                showRevert = true
            }
            UpdaterThread.UpdateMismatch, UpdaterThread.UpdateMismatchMagisk, UpdaterThread.UpdateMismatchVbmeta, UpdaterThread.UpdateMismatchRootUnavailable -> {
                if (silenceForPeriodic) {
                    return
                }
                prefs.mismatchAllowed = true

                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = true
                titleResId = R.string.notification_update_mismatch_title
                message = when (result) {
                    UpdaterThread.UpdateMismatch -> {
                        getString(R.string.notification_update_mismatch_message)
                    }

                    UpdaterThread.UpdateMismatchMagisk -> {
                        getString(R.string.notification_update_mismatch_magisk_message)
                    }

                    UpdaterThread.UpdateMismatchVbmeta -> {
                        getString(R.string.notification_update_mismatch_vbmeta_message)
                    }

                    UpdaterThread.UpdateMismatchRootUnavailable -> {
                        getString(R.string.notification_update_mismatch_root_unavailable_message)
                    }
                    else -> getString(R.string.notification_update_mismatch_message)
                }
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.RootUnavailable -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = true
                titleResId = R.string.notification_update_root_unavailable_title
                message = getString(R.string.notification_update_root_unavailable_message)
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.NetworkUnavailable -> {
                channel = Notifications.CHANNEL_ID_FAILURE
                onlyAlertOnce = true
                titleResId = R.string.notification_update_root_unavailable_title
                message = getString(R.string.notification_update_root_unavailable_message)
                showInstall = false
                showRetry = false
                showReboot = false
                showSwitchSlot = false
                showRevert = false
            }
            UpdaterThread.RootUnnecessary -> {
                notifications.dismissNotifications()
                threadExited()
                return
            }
        }

        val actionResIds = mutableListOf<Int>()
        val actionIntents = mutableListOf<Intent>()

        if (showInstall) {
            actionResIds.add(R.string.notification_action_install)
            if (result is UpdaterThread.UpdateFailed) {
                // TODO: Forward target?
                actionIntents.add(createScheduleIntent(this, UpdaterThread.Action.INSTALL))
            } else {
                actionIntents.add(createScheduleIntent(this, UpdaterThread.Action.INSTALL, message))

                val alerts = mutableListOf<IndexedAlert>()
                if (prefs.alertCache.isNotEmpty()) {
                    alerts.addAll(Json.decodeFromString<List<IndexedAlert>>(prefs.alertCache))
                }
                alerts.add(IndexedAlert(message!!, id!!))
                val cache = Json.encodeToString<List<IndexedAlert>>(alerts)
                prefs.alertCache = cache
            }
        }
        if (showRetry) {
            actionResIds.add(R.string.notification_action_retry)
            // Go through job scheduler because we might need a new network
            updaterAction?.let { action ->
                actionIntents.add(createScheduleIntent(this, action))
            }
        }
        if (showReboot) {
            actionResIds.add(R.string.notification_action_reboot)
            actionIntents.add(createActionIntent(ACTION_REBOOT))
        }
        if (showSwitchSlot) {
            actionResIds.add(R.string.notification_action_switch_slot)
            actionIntents.add(createScheduleIntent(this, UpdaterThread.Action.SWITCH_SLOT))
        }
        if (showRevert) {
            actionResIds.add(R.string.notification_action_revert)
            actionIntents.add(createScheduleIntent(this, UpdaterThread.Action.REVERT))
        }

        notifications.sendAlertNotification(
            channel,
            onlyAlertOnce,
            titleResId,
            R.drawable.ic_notifications,
            message,
            actionResIds.zip(actionIntents),
            id,
        )
    }

    @UiThread
    private fun notifySummary() {
        notifications.sendSummaryNotification()
    }

    override fun onUpdateResult(thread: UpdaterThread, result: UpdaterThread.Result) {
        handler.post {
            require(thread === updaterThread) { "Bad thread ($thread != $updaterThread)" }
            notifyAlert(result)
            threadExited()
        }
    }

    override fun onUpdateResults(thread: UpdaterThread, results: List<UpdaterThread.Result>) {
        handler.post {
            require(thread === updaterThread) { "Bad thread ($thread != $updaterThread)" }
            for (result in results) {
                notifyAlert(result)
            }
            notifySummary()
            threadExited()
        }
    }

    override fun onUpdateProgress(
        thread: UpdaterThread,
        type: UpdaterThread.ProgressType,
        current: Int,
        max: Int
    ) {
        handler.post {
            require(thread === updaterThread) { "Bad thread ($thread != $updaterThread)" }
            progressState = ProgressState(type, current, max)
            updateForegroundNotification(true)
        }
    }

    private data class ProgressState(
        val type: UpdaterThread.ProgressType,
        val current: Int,
        val max: Int,
    )

    @Serializable
    data class IndexedAlert(
        val version: String,
        val index: Int
    )

    companion object {
        private val TAG = UpdaterService::class.java.simpleName

        private val ACTION_START = "${UpdaterService::class.java.canonicalName}.start"
        private val ACTION_FAIL = "${UpdaterService::class.java.canonicalName}.fail"
        private val ACTION_PAUSE = "${UpdaterService::class.java.canonicalName}.pause"
        private val ACTION_RESUME = "${UpdaterService::class.java.canonicalName}.resume"
        private val ACTION_CANCEL = "${UpdaterService::class.java.canonicalName}.cancel"
        private val ACTION_REBOOT = "${UpdaterService::class.java.canonicalName}.reboot"
        private val ACTION_SWITCH_SLOT = "${UpdaterService::class.java.canonicalName}.switch_slot"
        private val ACTION_SCHEDULE = "${UpdaterService::class.java.canonicalName}.schedule"

        private const val EXTRA_NETWORK = "network"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_SILENT = "silent"

        fun createStartIntent(
            context: Context,
            network: Network?,
            action: UpdaterThread.Action,
            silent: Boolean,
        ) = Intent(context, UpdaterService::class.java).apply {
            this.action = ACTION_START

            putExtra(EXTRA_NETWORK, network)

            val parcelableAction: Parcelable = action
            putExtra(EXTRA_ACTION, parcelableAction)
            putExtra(EXTRA_SILENT, silent)
        }

        fun createFailIntent(
            context: Context,
            action: UpdaterThread.Action,
        ) = Intent(context, UpdaterService::class.java).apply {
            this.action = ACTION_FAIL

            val parcelableAction: Parcelable = action
            putExtra(EXTRA_ACTION, parcelableAction)
        }

        private fun createScheduleIntent(
            context: Context,
            action: UpdaterThread.Action,
            target: String? = null,
        ) = Intent(context, UpdaterService::class.java).apply {
            this.action = ACTION_SCHEDULE

            val parcelableAction: Parcelable = action
            putExtra(EXTRA_ACTION, parcelableAction)
            putExtra(EXTRA_TARGET, target)
        }
    }
}