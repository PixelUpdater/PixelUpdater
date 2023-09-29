/*
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.wrapper

import android.annotation.SuppressLint

object SystemPropertiesProxy {
    @SuppressLint("PrivateApi")
    private val CLS = Class.forName("android.os.SystemProperties")

    private val METHOD_GET = CLS.getDeclaredMethod("get", String::class.java)

    fun get(key: String): String {
        return METHOD_GET.invoke(null, key) as String
    }
}
