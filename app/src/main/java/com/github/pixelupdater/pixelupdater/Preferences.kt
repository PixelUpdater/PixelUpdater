/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.github.pixelupdater.pixelupdater.updater.UpdaterThread
import java.net.URL

class Preferences(context: Context) {
    companion object {
        const val CATEGORY_CERTIFICATES = "certificates"
        const val CATEGORY_DEBUG = "debug"

        const val PREF_CHECK_FOR_UPDATES = "check_for_updates"
        const val PREF_NOTIFICATION_FREQUENCY = "notification_frequency"
        const val PREF_AUTOMATIC_CHECK = "automatic_check"
        const val PREF_UNMETERED_ONLY = "unmetered_only"
        const val PREF_BATTERY_NOT_LOW = "battery_not_low"
        const val PREF_SKIP_POSTINSTALL = "skip_postinstall"
        const val PREF_MAGISK_PATCH = "magisk_patch"
        const val PREF_VBMETA_PATCH = "vbmeta_patch"
        const val PREF_AUTOMATIC_SWITCH_SLOT = "automatic_switch_slot"
        const val PREF_AUTOMATIC_REBOOT = "automatic_reboot"
        const val PREF_ANDROID_VERSION = "android_version"
        const val PREF_FINGERPRINT = "fingerprint"
        const val PREF_BOOT_SLOT = "boot_slot"
        const val PREF_BOOTLOADER_STATUS = "bootloader_status"
        const val PREF_MAGISK_STATUS = "magisk_status"
        const val PREF_VBMETA_STATUS = "vbmeta_status"
        const val PREF_NO_CERTIFICATES = "no_certificates"
        const val PREF_VERSION = "version"
        const val PREF_OPEN_LOG_DIR = "open_log_dir"
        const val PREF_OTA_URL = "ota_url"
        const val PREF_ALLOW_REINSTALL = "allow_reinstall"
        const val PREF_REVERT_COMPLETED = "revert_completed"
        const val PREF_VERITY_ONLY = "verity_only"

        // Not associated with a UI preference
        private const val PREF_OTA_CACHE = "ota_cache"
        private const val PREF_TARGET_OTA = "target_ota"
        private const val PREF_PAYLOAD_PROPERTIES_CACHE = "payload_properties_cache"
        private const val PREF_ALERT_CACHE = "alert_cache"
        private const val PREF_HAS_ROOT = "has_root"
        private const val PREF_LAST_VBMETA_STATE = "last_vbmeta_state"
        private const val PREF_MISMATCH_ALLOWED = "mismatch_allowed"
        private const val PREF_UPDATE_NOTIFIED = "update_notified"
        private const val PREF_DEBUG_MODE = "debug_mode"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    // TODO: Use Jetpack DataStore instead of SharedPreferences?
    var otaCache: String
        get() = prefs.getString(PREF_OTA_CACHE, "")!!
        set(cache) = prefs.edit { putString(PREF_OTA_CACHE, cache) }

    // TODO: Clear this somewhere?
    var targetOta: String
        get() = prefs.getString(PREF_TARGET_OTA, "")!!
        set(target) = prefs.edit { putString(PREF_TARGET_OTA, target) }

    // TODO: Clear this somewhere?
    var payloadPropertiesCache: String
        get() = prefs.getString(PREF_PAYLOAD_PROPERTIES_CACHE, "")!!
        set(cache) = prefs.edit { putString(PREF_PAYLOAD_PROPERTIES_CACHE, cache) }

    // TODO: Clear this somewhere?
    var alertCache: String
        get() = prefs.getString(PREF_ALERT_CACHE, "")!!
        set(cache) = prefs.edit { putString(PREF_ALERT_CACHE, cache) }

    /** Whether root has been granted. */
    var hasRoot: Boolean
        get() = prefs.getBoolean(PREF_HAS_ROOT, true)
        set(value) = prefs.edit { putBoolean(PREF_HAS_ROOT, value) }

    /** Last known vbmeta state. */
    var lastVbmetaState: Int
        get() = prefs.getInt(PREF_LAST_VBMETA_STATE, 0)
        set(value) = prefs.edit { putInt(PREF_LAST_VBMETA_STATE, value) }

    /** Whether a Magisk or Vbmeta configuration mismatch has been allowed. */
    var mismatchAllowed: Boolean
        get() = prefs.getBoolean(PREF_MISMATCH_ALLOWED, false)
        set(value) = prefs.edit { putBoolean(PREF_MISMATCH_ALLOWED, value) }

    /** Whether the user has previously been notified of an update. */
    var updateNotified: Boolean
        get() = prefs.getBoolean(PREF_UPDATE_NOTIFIED, false)
        set(value) = prefs.edit { putBoolean(PREF_UPDATE_NOTIFIED, value) }

    /** Whether to check for updates periodically. */
    var automaticCheck: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_CHECK, true)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_CHECK, enabled) }

    /** How frequently to send notifications about pending updates. */
    var notificationFrequency: String
        get() = prefs.getString(PREF_NOTIFICATION_FREQUENCY, "daily")!!
        set(enabled) = prefs.edit { putString(PREF_NOTIFICATION_FREQUENCY, enabled) }

    /** Whether to only allow running when connected to an unmetered network. */
    var requireUnmetered: Boolean
        get() = prefs.getBoolean(PREF_UNMETERED_ONLY, true)
        set(enabled) = prefs.edit { putBoolean(PREF_UNMETERED_ONLY, enabled) }

    /** Whether to only allow running when battery is above the critical threshold. */
    var requireBatteryNotLow: Boolean
        get() = prefs.getBoolean(PREF_BATTERY_NOT_LOW, true)
        set(enabled) = prefs.edit { putBoolean(PREF_BATTERY_NOT_LOW, enabled) }

    /** Whether to skip optional post-install scripts in the OTA. */
    var skipPostInstall: Boolean
        get() = prefs.getBoolean(PREF_SKIP_POSTINSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_SKIP_POSTINSTALL, enabled) }

    /** Whether to magisk patch the installed OTA. */
    var magiskPatch: Boolean
        get() = prefs.getBoolean(PREF_MAGISK_PATCH, true)
        set(enabled) = prefs.edit { putBoolean(PREF_MAGISK_PATCH, enabled) }

    /** Whether to disable verity and verification in the installed OTA. */
    var vbmetaPatch: Boolean
        get() = prefs.getBoolean(PREF_VBMETA_PATCH, false)
        set(enabled) = prefs.edit { putBoolean(PREF_VBMETA_PATCH, enabled) }

    /** Whether to treat an equal fingerprint as an update. */
    var allowReinstall: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_REINSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_REINSTALL, enabled) }

    /** URL of OTA update. */
    var otaUrl: URL?
        get() = prefs.getString(PREF_OTA_URL, null)?.let { URL(it) }
        set(url) = prefs.edit {
            if (url == null) {
                remove(PREF_OTA_URL)
            } else {
                putString(PREF_OTA_URL, url.toString())
            }
        }

    /** Whether to force switch slot on update. */
    var automaticSwitchSlot: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_SWITCH_SLOT, false)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_SWITCH_SLOT, enabled) }

    /** Whether to automatically reboot on successful update. */
    var automaticReboot: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_REBOOT, false)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_REBOOT, enabled) }

    /** Whether to disable only verity in the installed OTA. */
    var verityOnly: Boolean
        get() = prefs.getBoolean(PREF_VERITY_ONLY, false)
        set(enabled) = prefs.edit { putBoolean(PREF_VERITY_ONLY, enabled) }

    init {
        if (!prefs.contains(PREF_VBMETA_PATCH) && !prefs.contains(PREF_VERITY_ONLY)) {
            val flags = UpdaterThread.getVbmetaFlags(active = true)
            vbmetaPatch = flags != 0.toByte()
            verityOnly = flags == UpdaterThread.DISABLE_VERITY_FLAG
        }
    }
}
