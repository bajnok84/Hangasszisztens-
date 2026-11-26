package com.sajat.hangasszisztens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

class BluetoothAutoReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_OPEN_FROM_BLUETOOTH =
            "com.sajat.hangasszisztens.OPEN_FROM_BLUETOOTH"
        private const val CHANNEL_ID = "neo_bluetooth"
        private const val NOTIFICATION_ID = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        } ?: return

        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("NEO", "A Bluetooth-eszköz nem ellenőrizhető engedély nélkül")
            return
        }

        val isAudioDevice = try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (error: SecurityException) {
            android.util.Log.w("NEO", "A Bluetooth-eszköz típusa nem olvasható", error)
            false
        }
        if (!isAudioDevice) return

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.i(
                "NEO",
                "Bluetooth-kapcsolat észlelve, de az értesítés engedélye hiányzik"
            )
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Bluetooth-kapcsolat",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val openAssistant = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_FROM_BLUETOOTH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEO: Bluetooth-kapcsolat")
            .setContentText("Koppints az asszisztens indításához.")
            .setContentIntent(openAssistant)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            android.util.Log.w("NEO", "A Bluetooth-indítási értesítés nem jeleníthető meg", error)
        }
    }
}
