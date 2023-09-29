/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

package com.github.pixelupdater.pixelupdater.wrapper

import android.annotation.SuppressLint
import android.os.IBinder

object ServiceManagerProxy {
    @SuppressLint("PrivateApi")
    private val CLS = Class.forName("android.os.ServiceManager")

    @SuppressLint("SoonBlockedPrivateApi")
    private val METHOD_GET_SERVICE_OR_THROW =
        CLS.getDeclaredMethod("getServiceOrThrow", String::class.java)

    fun getServiceOrThrow(name: String): IBinder {
        return METHOD_GET_SERVICE_OR_THROW.invoke(null, name) as IBinder
    }
}
