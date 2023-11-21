/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2022-2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

package com.github.pixelupdater.pixelupdater.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.get
import androidx.preference.size
import com.github.pixelupdater.pixelupdater.BuildConfig
import com.github.pixelupdater.pixelupdater.Notifications
import com.github.pixelupdater.pixelupdater.Permissions
import com.github.pixelupdater.pixelupdater.Preferences
import com.github.pixelupdater.pixelupdater.R
import com.github.pixelupdater.pixelupdater.dialog.OtaUrlDialogFragment
import com.github.pixelupdater.pixelupdater.updater.OtaPaths
import com.github.pixelupdater.pixelupdater.updater.UpdaterJob
import com.github.pixelupdater.pixelupdater.updater.UpdaterThread
import com.github.pixelupdater.pixelupdater.view.LongClickablePreference
import com.github.pixelupdater.pixelupdater.view.OnPreferenceLongClickListener
import com.github.pixelupdater.pixelupdater.wrapper.SystemPropertiesProxy
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey


class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    OnPreferenceLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener,
    Preference.OnPreferenceChangeListener {
    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryCertificates: PreferenceCategory
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefCheckForUpdates: Preference
    private lateinit var prefAndroidVersion: Preference
    private lateinit var prefFingerprint: Preference
    private lateinit var prefBootSlot: Preference
    private lateinit var prefBootloaderStatus: Preference
    private lateinit var prefMagiskStatus: Preference
    private lateinit var prefVbmetaStatus: Preference
    private lateinit var prefNoCertificates: Preference
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefOpenLogDir: Preference
    private lateinit var prefRevertCompleted: Preference
    private lateinit var prefOtaUrl: Preference
    private lateinit var prefMagiskPatch: SwitchPreferenceCompat
    private lateinit var prefVbmetaPatch: SwitchPreferenceCompat
    private lateinit var prefAutomaticReboot: SwitchPreferenceCompat
    private lateinit var prefVerityOnly: SwitchPreferenceCompat

    private lateinit var snackbar: Snackbar

    private lateinit var scheduledAction: UpdaterThread.Action

    private val requestPermissionRequired =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.all { it.key !in Permissions.REQUIRED || it.value }) {
                performAction()
            } else {
                startActivity(Permissions.getAppInfoIntent(requireContext()))
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)

        val context = requireContext()

        prefs = Preferences(context)

        categoryCertificates = findPreference(Preferences.CATEGORY_CERTIFICATES)!!
        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        prefCheckForUpdates = findPreference(Preferences.PREF_CHECK_FOR_UPDATES)!!
        prefCheckForUpdates.onPreferenceClickListener = this

        prefAndroidVersion = findPreference(Preferences.PREF_ANDROID_VERSION)!!
        prefAndroidVersion.summary = Build.VERSION.RELEASE

        prefFingerprint = findPreference(Preferences.PREF_FINGERPRINT)!!
        prefFingerprint.summary = Build.FINGERPRINT

        prefBootSlot = findPreference(Preferences.PREF_BOOT_SLOT)!!
        prefBootSlot.summary = SystemPropertiesProxy.get("ro.boot.slot_suffix")
            .removePrefix("_").uppercase()

        prefBootloaderStatus = findPreference(Preferences.PREF_BOOTLOADER_STATUS)!!

        prefMagiskStatus = findPreference(Preferences.PREF_MAGISK_STATUS)!!

        prefVbmetaStatus = findPreference(Preferences.PREF_VBMETA_STATUS)!!

        prefNoCertificates = findPreference(Preferences.PREF_NO_CERTIFICATES)!!
        prefNoCertificates.summary = getString(
            R.string.pref_no_certificates_desc, OtaPaths.OTACERTS_ZIP)

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this

        prefOpenLogDir = findPreference(Preferences.PREF_OPEN_LOG_DIR)!!
        prefOpenLogDir.onPreferenceClickListener = this

        prefRevertCompleted = findPreference(Preferences.PREF_REVERT_COMPLETED)!!
        prefRevertCompleted.onPreferenceClickListener = this

        prefOtaUrl = findPreference(Preferences.PREF_OTA_URL)!!
        prefOtaUrl.onPreferenceClickListener = this

        prefMagiskPatch = findPreference(Preferences.PREF_MAGISK_PATCH)!!

        prefVbmetaPatch = findPreference(Preferences.PREF_VBMETA_PATCH)!!
        prefVbmetaPatch.onPreferenceChangeListener = this

        prefAutomaticReboot = findPreference(Preferences.PREF_AUTOMATIC_REBOOT)!!

        prefVerityOnly = findPreference(Preferences.PREF_VERITY_ONLY)!!
        prefVerityOnly.onPreferenceChangeListener = this

        if (UpdaterThread.getVbmetaFlags(active = true) == 0.toByte()) {
            prefVbmetaPatch.isChecked = false
            prefVbmetaPatch.isEnabled = false
        }

        refreshOtaUrl()
        refreshVersion()
        refreshDebugPrefs()

        Shell.getShell()
        if (Shell.isAppGrantedRoot()!!) {
            prefs.hasRoot = true
        } else {
            prefs.hasRoot = false
            if (prefs.magiskPatch || prefs.vbmetaPatch || prefs.verityOnly) {
                prefs.magiskPatch = false
                prefs.vbmetaPatch = false
                prefs.verityOnly = false
                prefs.mismatchAllowed = false
                prefMagiskPatch.isChecked = false
                prefVbmetaPatch.isChecked = false
                prefVerityOnly.isChecked = false
                UpdaterJob.scheduleImmediate(context, UpdaterThread.Action.NO_ROOT)
            }
            prefMagiskPatch.isEnabled = false
            prefVbmetaPatch.isEnabled = false
            prefVerityOnly.isEnabled = false
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.certs.collect {
                    addCertPreferences(it)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bootloaderStatus.collect {
                    if (it != null) {
                        updateBootloaderStatus(it)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.magiskStatus.collect {
                    if (it != null) {
                        updateMagiskStatus(it)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vbmetaStatus.collect {
                    if (it != null) {
                        updateVbmetaStatus(it)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        snackbar = Snackbar.make(view, R.string.snackbar_notifications_disabled, Snackbar.LENGTH_INDEFINITE)
        val context = requireContext()
        if (!Permissions.haveRequired(context) || !Notifications.areEnabled(context)) {
            scheduledAction = UpdaterThread.Action.CHECK
            performAction()
        }
    }

    override fun onStart() {
        super.onStart()

        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)

        // Make sure we refresh this every time the user switches back to the app
        refreshSnackbar()
        viewModel.refreshBootloaderStatus()
        viewModel.refreshMagiskStatus()
        if (prefs.hasRoot) {
            viewModel.refreshVbmetaStatus()
        } else {
            viewModel.setVbmetaStatus(SettingsViewModel.VbmetaStatus.Failure("Root is unavailable"))
        }
    }

    override fun onStop() {
        super.onStop()

        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun refreshSnackbar() {
        val context = requireContext()
        if (!Permissions.haveRequired(context) || !Notifications.areEnabled(context)) {
            snackbar.show()
        } else {
            snackbar.dismiss()
        }
    }

    private fun refreshOtaUrl() {
        prefOtaUrl.summary = prefs.otaUrl?.toString()
            ?: getString(R.string.pref_ota_url_desc_none)
    }

    private fun refreshVersion() {
        val suffix = if (prefs.isDebugMode) {
            "+debugmode"
        } else {
            ""
        }
        prefVersion.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}${suffix})"
    }

    private fun refreshDebugPrefs() {
        categoryDebug.isVisible = prefs.isDebugMode
    }

    private fun updateBootloaderStatus(status: SettingsViewModel.BootloaderStatus) {
        prefBootloaderStatus.summary = buildString {
            when (status) {
                is SettingsViewModel.BootloaderStatus.Success -> {
                    if (status.unlocked) {
                        append(getString(R.string.pref_bootloader_status_unlocked))
                    } else {
                        append(getString(R.string.pref_bootloader_status_locked))
                    }
                    append('\n')
                    if (status.allowedByCarrier) {
                        append(getString(R.string.pref_bootloader_status_oemlock_carrier_allowed))
                    } else {
                        append(getString(R.string.pref_bootloader_status_oemlock_carrier_blocked))
                    }
                    append('\n')
                    if (status.allowedByUser) {
                        append(getString(R.string.pref_bootloader_status_oemlock_user_allowed))
                    } else {
                        append(getString(R.string.pref_bootloader_status_oemlock_user_blocked))
                    }
                }
                is SettingsViewModel.BootloaderStatus.Failure -> {
                    append(getString(R.string.pref_bootloader_status_unknown))
                    append('\n')
                    append(status.errorMsg)
                }
            }
        }
    }

    private fun updateMagiskStatus(status: SettingsViewModel.MagiskStatus) {
        prefMagiskStatus.summary = SpannableStringBuilder().apply {
            when (status) {
                is SettingsViewModel.MagiskStatus.Success -> {
                    if (status.installed) {
                        append(getString(R.string.pref_magisk_status_installed))
                        append(" [${status.version}]")
                    } else {
                        append(getString(R.string.pref_magisk_status_not_installed))
                    }
                }
                is SettingsViewModel.MagiskStatus.Failure -> {
                    append(getString(R.string.pref_magisk_status_unknown))
                    append('\n')
                    append(status.errorMsg)
                }
            }
            append('\n')
            @StringRes
            val statusRes: Int
            val error: Boolean
            if (prefMagiskPatch.isChecked) {
                statusRes = R.string.pref_magisk_ota_status_installed
                error = status !is SettingsViewModel.MagiskStatus.Success || !status.installed
            } else {
                statusRes = R.string.pref_magisk_ota_status_not_installed
                error = status !is SettingsViewModel.MagiskStatus.Success || status.installed
            }
            append(buildColoredSpannable(statusRes, error))
        }
    }

    private fun updateVbmetaStatus(status: SettingsViewModel.VbmetaStatus) {
        prefVbmetaStatus.summary = SpannableStringBuilder().apply {
            when (status) {
                is SettingsViewModel.VbmetaStatus.Success -> {
                    when (status.patch) {
                        SettingsViewModel.VbmetaStatus.PatchState.Enabled -> {
                            append(getString(R.string.pref_vbmeta_status_enabled))
                        }
                        SettingsViewModel.VbmetaStatus.PatchState.VerityDisabled -> {
                            append(getString(R.string.pref_vbmeta_status_verity_disabled))
                        }
                        SettingsViewModel.VbmetaStatus.PatchState.VerificationDisabled -> {
                            append(getString(R.string.pref_vbmeta_status_verification_disabled))
                        }
                        SettingsViewModel.VbmetaStatus.PatchState.Disabled -> {
                            append(getString(R.string.pref_vbmeta_status_disabled))
                        }
                        else -> append(getString(R.string.pref_vbmeta_status_verification_unexpected))
                    }
                }
                is SettingsViewModel.VbmetaStatus.Failure -> {
                    append(getString(R.string.pref_vbmeta_status_unknown))
                    append('\n')
                    append(status.errorMsg)
                }
            }
            append('\n')
            @StringRes
            val statusRes: Int
            val error: Boolean
            if (prefVbmetaPatch.isChecked) {
                if (prefVerityOnly.isChecked) {
                    statusRes = R.string.pref_vbmeta_ota_status_verity_disabled
                    error = status !is SettingsViewModel.VbmetaStatus.Success || status.patch != SettingsViewModel.VbmetaStatus.PatchState.VerityDisabled
                } else {
                    if (status is SettingsViewModel.VbmetaStatus.Success) {
                        println("status.patch: ${status.patch}")
                    }
                    statusRes = R.string.pref_vbmeta_ota_status_disabled
                    error = status !is SettingsViewModel.VbmetaStatus.Success || status.patch != SettingsViewModel.VbmetaStatus.PatchState.Disabled
                }
            } else {
                statusRes = R.string.pref_vbmeta_ota_status_enabled
                error = status !is SettingsViewModel.VbmetaStatus.Success || status.patch != SettingsViewModel.VbmetaStatus.PatchState.Enabled
            }
            append(buildColoredSpannable(statusRes, error))
        }
    }

    private fun buildColoredSpannable(@StringRes statusRes: Int, error: Boolean): SpannableStringBuilder {
        val coloredSpannable = SpannableStringBuilder(getString(statusRes))
        @AttrRes
        val colorRes = if (error) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorOnSurface
        }
        @ColorInt
        val colorId = MaterialColors.getColor(requireView(), colorRes)
        val opacity = if (colorRes == com.google.android.material.R.attr.colorError) 192 else 128
        val foreground = ForegroundColorSpan(ColorUtils.setAlphaComponent(colorId, opacity))
        coloredSpannable.setSpan(foreground, 0, coloredSpannable.length, 0)
        return coloredSpannable
    }

    private fun performAction() {
        val context = requireContext()

        if (Permissions.haveRequired(context)) {
            if (Notifications.areEnabled(context)) {
                UpdaterJob.scheduleImmediate(requireContext(), scheduledAction)
                refreshSnackbar()
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                startActivity(intent)
            }
        } else {
            requestPermissionRequired.launch(Permissions.REQUIRED)
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            prefCheckForUpdates -> {
                scheduledAction = UpdaterThread.Action.CHECK
                performAction()
                return true
            }
            prefOtaUrl -> {
                OtaUrlDialogFragment().show(parentFragmentManager.beginTransaction(),
                    OtaUrlDialogFragment.TAG)
                return true
            }
            prefVersion -> {
                val uri = Uri.parse(BuildConfig.PROJECT_URL_AT_COMMIT)
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            prefOpenLogDir -> {
                val externalDir = Environment.getExternalStorageDirectory()
                val filesDir = requireContext().getExternalFilesDir(null)!!
                val relPath = filesDir.relativeTo(externalDir)
                val uri = DocumentsContract.buildDocumentUri(
                    DOCUMENTSUI_AUTHORITY, "primary:$relPath")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                }
                startActivity(intent)
                return true
            }
            prefRevertCompleted -> {
                scheduledAction = UpdaterThread.Action.REVERT
                performAction()
                return true
            }
        }

        return false
    }

    override fun onPreferenceLongClick(preference: Preference): Boolean {
        when (preference) {
            prefVersion -> {
                prefs.isDebugMode = !prefs.isDebugMode
                refreshVersion()
                refreshDebugPrefs()
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference) {
            prefVbmetaPatch, prefVerityOnly -> {
                val newBoolean = newValue as Boolean
                val status = viewModel.vbmetaStatus.value
                val showAlert = if (status == null) {
                    true
                } else if (status !is SettingsViewModel.VbmetaStatus.Success) {
                    true
                } else if (preference == prefVerityOnly) {
                    if (newBoolean && status.patch != SettingsViewModel.VbmetaStatus.PatchState.VerityDisabled) {
                        true
                    } else {
                        !newBoolean && status.patch != SettingsViewModel.VbmetaStatus.PatchState.Disabled
                    }
                } else {
                    !newBoolean && status.patch != SettingsViewModel.VbmetaStatus.PatchState.Enabled
                }
                if (!showAlert) {
                    return true
                }
                val switchPreference = preference as SwitchPreferenceCompat
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(getString(R.string.dialog_vbmeta_title))
                    setMessage(getString(R.string.dialog_vbmeta_message))
                    setPositiveButton(R.string.dialog_action_ok) { _, _ -> switchPreference.isChecked = !switchPreference.isChecked }
                    setNegativeButton(R.string.dialog_action_cancel, null)
                }.show()
            }
        }

        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Preferences.PREF_OTA_URL -> {
                refreshOtaUrl()
            }
            Preferences.PREF_UNMETERED_ONLY, Preferences.PREF_BATTERY_NOT_LOW -> {
                UpdaterJob.schedulePeriodic(requireContext(), true)
            }
            Preferences.PREF_AUTOMATIC_SWITCH -> {
                prefAutomaticReboot.isChecked = false
            }
            Preferences.PREF_MAGISK_PATCH, Preferences.PREF_VBMETA_PATCH, Preferences.PREF_VERITY_ONLY -> {
                if (key == Preferences.PREF_MAGISK_PATCH) {
                    if (viewModel.magiskStatus.value != null) {
                        updateMagiskStatus(viewModel.magiskStatus.value!!)
                    }
                } else {
                    if (key == Preferences.PREF_VBMETA_PATCH) {
                        prefVerityOnly.isChecked = false
                    }
                    if (viewModel.vbmetaStatus.value != null) {
                        updateVbmetaStatus(viewModel.vbmetaStatus.value!!)
                    }
                }
                prefs.mismatchAllowed = false
            }
            Preferences.PREF_ALLOW_REINSTALL -> {
                if (prefs.allowReinstall) {
                    scheduledAction = UpdaterThread.Action.CHECK
                    performAction()
                } else {
                    prefs.updateNotified = false
                }
            }
        }
    }

    private fun addCertPreferences(certs: List<X509Certificate>) {
        val context = requireContext()

        prefNoCertificates.isVisible = certs.isEmpty()

        for (i in (0 until categoryCertificates.size).reversed()) {
            val p = categoryCertificates[i]

            if (p.key.startsWith(PREF_CERT_PREFIX)) {
                categoryCertificates.removePreference(p)
            }
        }

        for ((i, cert) in certs.withIndex()) {
            val p = Preference(context).apply {
                key = PREF_CERT_PREFIX + i
                isPersistent = false
                title = getString(R.string.pref_certificate_name, (i + 1).toString())
                summary = buildString {
                    append(getString(R.string.pref_certificate_desc_subject,
                        cert.subjectDN.toString()))
                    append('\n')

                    append(getString(R.string.pref_certificate_desc_serial,
                        cert.serialNumber.toString(16)))
                    append('\n')

                    append(getString(R.string.pref_certificate_desc_type, cert.typeName))
                }
                isIconSpaceReserved = false
            }

            categoryCertificates.addPreference(p)
        }
    }

    companion object {
        private const val DOCUMENTSUI_AUTHORITY = "com.android.externalstorage.documents"

        private const val PREF_CERT_PREFIX = "certificate_"

        private val PublicKey.keyLength: Int
            get() {
                when (this) {
                    is ECPublicKey -> params?.order?.bitLength()?.let { return it }
                    is RSAPublicKey -> return modulus.bitLength()
                    is DSAPublicKey -> return if (params != null) {
                        params.p.bitLength()
                    } else {
                        y.bitLength()
                    }
                }

                return -1
            }

        private val Certificate.typeName: String
            get() = buildString {
                append(publicKey.algorithm)
                val keyLength = publicKey.keyLength
                if (keyLength >= 0) {
                    append(' ')
                    append(keyLength)
                }
            }
    }
}
