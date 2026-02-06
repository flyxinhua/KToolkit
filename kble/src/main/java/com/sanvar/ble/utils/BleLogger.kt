package com.sanvar.ble.utils

import android.util.Log

object BleLogger {
    private const val TAG = "BleSdk"
    private var enabled = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (enabled) Log.i(TAG, message)
    }

    fun w(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }
}