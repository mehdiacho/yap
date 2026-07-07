package com.mehdiacho.yap.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mehdiacho.yap.MainActivity

/**
 * Heads-up notifications (design · external surfaces): refine-complete. Only posted when
 * the app isn't in the foreground — if you're watching the note polish itself, no ping.
 */
object Notifier {
    private const val CHANNEL_ID = "yap_refine_done"

    /** Set by MainActivity onStart/onStop so background-only notifications stay quiet in-app. */
    @Volatile var appInForeground = false

    fun refineDone(context: Context, sessionId: String, title: String) {
        if (appInForeground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Note polished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val open = PendingIntent.getActivity(
            context, sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.mehdiacho.yap.R.drawable.ic_tile_mic)
            .setContentTitle("Your note is polished ✨")
            .setContentText("\"$title\" is refined.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        mgr.notify(sessionId.hashCode(), notif)
    }
}
