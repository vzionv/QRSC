package com.example.qrsc

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

object DebugLog {
    fun d(context: Context, tag: String, message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(tag, message)
        }
    }

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
