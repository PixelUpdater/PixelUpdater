/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.settings

import android.service.oemlock.IOemLockService
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pixelupdater.pixelupdater.extension.toSingleLineString
import com.github.pixelupdater.pixelupdater.updater.OtaPaths
import com.github.pixelupdater.pixelupdater.updater.UpdaterThread
import com.github.pixelupdater.pixelupdater.wrapper.ServiceManagerProxy
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate

class SettingsViewModel : ViewModel() {
    private val _certs = MutableStateFlow<List<X509Certificate>>(emptyList())
    val certs: StateFlow<List<X509Certificate>> = _certs

    private val _bootloaderStatus = MutableStateFlow<BootloaderStatus?>(null)
    val bootloaderStatus: StateFlow<BootloaderStatus?> = _bootloaderStatus

    private val _magiskStatus = MutableStateFlow<MagiskStatus?>(null)
    val magiskStatus: StateFlow<MagiskStatus?> = _magiskStatus

    private val _vbmetaStatus = MutableStateFlow<VbmetaStatus?>(null)
    val vbmetaStatus: StateFlow<VbmetaStatus?> = _vbmetaStatus

    init {
        loadCertificates()
    }

    private fun loadCertificates() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val certs = try {
                    OtaPaths.otaCerts
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load certificates")
                    emptyList()
                }

                _certs.update { certs }
            }
        }
    }

    fun refreshBootloaderStatus() {
        val status = try {
            val service = IOemLockService.Stub.asInterface(
                ServiceManagerProxy.getServiceOrThrow("oem_lock"))

            BootloaderStatus.Success(
                service.isDeviceOemUnlocked,
                service.isOemUnlockAllowedByCarrier,
                service.isOemUnlockAllowedByUser,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query bootloader status", e)
            BootloaderStatus.Failure(e.toSingleLineString())
        }

        _bootloaderStatus.update { status }
    }

    fun refreshMagiskStatus() {
        val status = try {
            val versionString = Shell.cmd("magisk -v").exec().out.first().split(":".toRegex()).first()
            val versionCode = Shell.cmd("magisk -V").exec().out.first()
            MagiskStatus.Success(
                true,
                "$versionString ($versionCode)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query magisk status", e)
            MagiskStatus.Failure(e.toSingleLineString())
        }

        _magiskStatus.update { status }
    }

    fun refreshVbmetaStatus() {
        val status = try {
            val flags = UpdaterThread.getVbmetaFlags()!!.toInt()
            VbmetaStatus.Success(
                VbmetaStatus.PatchState.values()[flags]
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query vbmeta status", e)
            VbmetaStatus.Failure(e.toSingleLineString())
        }

        _vbmetaStatus.update { status }
    }

    fun setVbmetaStatus(status: VbmetaStatus) = _vbmetaStatus.update { status }

    sealed interface BootloaderStatus {
        data class Success(
            val unlocked: Boolean,
            val allowedByCarrier: Boolean,
            val allowedByUser: Boolean,
        ) : BootloaderStatus

        data class Failure(val errorMsg: String) : BootloaderStatus
    }

    sealed interface MagiskStatus {
        data class Success(
            val installed: Boolean,
            val version: String?,
        ) : MagiskStatus

        data class Failure(val errorMsg: String) : MagiskStatus
    }

    sealed interface VbmetaStatus {
        data class Success(
            val patch: PatchState,
        ) : VbmetaStatus

        data class Failure(val errorMsg: String) : VbmetaStatus


        enum class PatchState {
            Enabled,
            VerityDisabled,
            VerificationDisabled,
            Disabled,
        }
    }

    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName
    }
}