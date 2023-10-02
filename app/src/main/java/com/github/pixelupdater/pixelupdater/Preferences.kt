/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Preferences(context: Context) {
    companion object {
        const val CATEGORY_CERTIFICATES = "certificates"
        const val CATEGORY_DEBUG = "debug"

        const val PREF_CHECK_FOR_UPDATES = "check_for_updates"
        const val PREF_AUTOMATIC_CHECK = "automatic_check"
        const val PREF_AUTOMATIC_INSTALL = "automatic_install"
        const val PREF_UNMETERED_ONLY = "unmetered_only"
        const val PREF_BATTERY_NOT_LOW = "battery_not_low"
        const val PREF_SKIP_POSTINSTALL = "skip_postinstall"
        const val PREF_ANDROID_VERSION = "android_version"
        const val PREF_FINGERPRINT = "fingerprint"
        const val PREF_BOOT_SLOT = "boot_slot"
        const val PREF_BOOTLOADER_STATUS = "bootloader_status"
        const val PREF_NO_CERTIFICATES = "no_certificates"
        const val PREF_VERSION = "version"
        const val PREF_OPEN_LOG_DIR = "open_log_dir"
        const val PREF_ALLOW_REINSTALL = "allow_reinstall"
        const val PREF_REVERT_COMPLETED = "revert_completed"

        // Not associated with a UI preference
        private const val PREF_OTA_CACHE = "ota_cache"
        private const val PREF_TARGET_OTA = "target_ota"
        private const val PREF_PAYLOAD_PROPERTIES_CACHE = "payload_properties_cache"
        private const val PREF_ALERT_CACHE = "alert_cache"
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

    // TODO: Clear this somewhere
    var targetOta: String
        get() = prefs.getString(PREF_TARGET_OTA, "")!!
        set(target) = prefs.edit { putString(PREF_TARGET_OTA, target) }

    // TODO: Clear this somewhere
    var payloadPropertiesCache: String
        get() = prefs.getString(PREF_PAYLOAD_PROPERTIES_CACHE, "")!!
        set(cache) = prefs.edit { putString(PREF_PAYLOAD_PROPERTIES_CACHE, cache) }

    // TODO: Clear this somewhere
    var alertCache: String
        get() = prefs.getString(PREF_ALERT_CACHE, "")!!
        set(cache) = prefs.edit { putString(PREF_ALERT_CACHE, cache) }

    /** Whether to check for updates periodically. */
    var automaticCheck: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_CHECK, true)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_CHECK, enabled) }

    /** Whether to install updates in the periodic job or just check for them. */
    var automaticInstall: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_INSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_INSTALL, enabled) }

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

    /** Whether to treat an equal fingerprint as an update. */
    var allowReinstall: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_REINSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_REINSTALL, enabled) }
}
