package com.mehdiacho.yap.overlay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Enable/disable state + overlay-permission helpers for the floating mic bubble. */
object FloatingBubble {
    private const val PREF = "yap"
    private const val KEY = "floating_bubble"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun enable(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        context.startService(Intent(context, FloatingMicService::class.java))
    }

    fun disable(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
        context.stopService(Intent(context, FloatingMicService::class.java))
    }

    /** Re-show the bubble on app launch if the user left it on. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context) && hasPermission(context)) {
            context.startService(Intent(context, FloatingMicService::class.java))
        }
    }
}
