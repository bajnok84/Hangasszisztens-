package com.sajat.hangasszisztens

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

class BluetoothAutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !Settings.canDrawOverlays(context)
        ) {
            android.util.Log.w("NEO", "Automatikus indítás kihagyva: hiányzó mikrofon- vagy HUD-engedély")
            return
        }
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        } ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val isAudioDevice = try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (error: SecurityException) {
            android.util.Log.w("NEO", "A Bluetooth-eszköz típusa nem olvasható", error)
            false
        }
        if (!isAudioDevice) return

        val serviceIntent = Intent(context, FloatingAssistantService::class.java)
            .putExtra(FloatingAssistantService.EXTRA_AUTO_GREETING, true)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
    }
}
