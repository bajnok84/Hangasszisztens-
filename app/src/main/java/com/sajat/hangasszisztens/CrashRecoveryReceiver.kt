package com.sajat.hangasszisztens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

class CrashRecoveryReceiver : BroadcastReceiver() {
    companion object {
        private const val CHANNEL_ID = "neo_crash_recovery"
        private const val NOTIFICATION_ID = 3
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("NEO", "Az összeomlás utáni értesítéshez nincs engedély")
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEO helyreállítás",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("A NEO váratlanul leállt")
                    .setContentText("Nyisd meg a vezérlőpultot, és indítsd újra az asszisztenst.")
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        } catch (error: SecurityException) {
            android.util.Log.w("NEO", "A helyreállítási értesítés nem jeleníthető meg", error)
        }
    }
}
