package com.musungo.mheadphones

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes widget monitoring when Android reports a Bluetooth link reconnect. */
class MusungoBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device != null && !device.address.equals(JieliControllerRuntime.DEVICE_ADDRESS, ignoreCase = true)) return
        if (MusungoWidget.hasWidgets(context)) {
            MusungoDeviceService.startMonitoring(context)
        }
    }
}
