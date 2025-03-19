/*
 * SPDX-FileCopyrightText: 2025 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.pixelupdater.pixelupdater.wrapper

import java.lang.reflect.InvocationTargetException

/** Class for accessing hidden SystemProperties API. */
object SystemPropertiesProxy {
    private val classSystemProperties by lazy {
        Class.forName("android.os.SystemProperties")
    }

    private val methodGet by lazy {
        classSystemProperties.getDeclaredMethod("get", String::class.java)
    }

    private val methodGetWithDefault by lazy {
        classSystemProperties.getDeclaredMethod("get", String::class.java, String::class.java)
    }

    private val methodGetInt by lazy {
        classSystemProperties.getDeclaredMethod("getInt", String::class.java, Int::class.java)
    }

    private val methodGetBoolean by lazy {
        classSystemProperties.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
    }

    /**
     * Get the value for the given key.
     *
     * @return an empty string if the key isn't found
     */
    fun get(key: String): String {
        try {
            return methodGet.invoke(null, key) as String
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.targetException)
        }
    }

    /**
     * Get the value for the given key.
     *
     * @return the default if the key isn't found
     */
    fun get(key: String, def: String): String {
        try {
            return methodGetWithDefault.invoke(null, key, def) as String
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.targetException)
        }
    }

    /**
     * Get the value for the given key, and return as an integer.
     *
     * @return the default if the key isn't found, or if the value isn't an integer
     */
    fun getInt(key: String, def: Int): Int {
        try {
            return methodGetInt.invoke(null, key, def) as Int
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.targetException)
        }
    }

    /**
     * Get the value for the given key, and return as a boolean.
     *
     * Values 'n', 'no', '0', 'false' or 'off' are considered false.
     * Values 'y', 'yes', '1', 'true' or 'on' are considered true.
     * If the key does not exist, or has any other value, then the default result is returned.
     *
     * @return the default if the key isn't found, or if the value isn't a boolean
     */
    fun getBoolean(key: String, def: Boolean): Boolean {
        try {
            return methodGetBoolean.invoke(null, key, def) as Boolean
        } catch (e: IllegalAccessException) {
            throw RuntimeException(e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException(e.targetException)
        }
    }
}
