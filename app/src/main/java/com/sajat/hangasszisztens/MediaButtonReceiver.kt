package com.sajat.hangasszisztens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        } ?: return

        val supportedKey = event.keyCode in setOf(
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE
        )
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || !supportedKey) return

        val requiredPermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (requiredPermissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ||
            !Settings.canDrawOverlays(context)
        ) {
            android.util.Log.w("J.A.R.V.I.S.", "Headset-gomb figyelmen kívül hagyva: hiányzó engedély")
            return
        }

        val serviceIntent = Intent(context, FloatingAssistantService::class.java)
            .setAction(FloatingAssistantService.ACTION_START_LISTENING)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
    }
}
