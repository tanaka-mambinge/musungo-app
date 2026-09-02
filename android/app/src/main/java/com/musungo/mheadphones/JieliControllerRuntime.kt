package com.musungo.mheadphones

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import com.jieli.bluetooth.bean.BluetoothOption
import com.jieli.bluetooth.constant.BluetoothConstant
import com.jieli.bluetooth.impl.rcsp.RCSPController

/**
 * Process-wide access to the Jieli controller shared by the UI, widget, and
 * background service.
 *
 * The controller is deliberately initialized with the application context so
 * it is not tied to the React Native activity lifecycle.
 */
object JieliControllerRuntime {
    const val DEVICE_ADDRESS = "EB:77:75:7D:7B:42"

    @Synchronized
    fun getController(context: Context): RCSPController {
        if (!RCSPController.isInit()) {
            RCSPController.init(context.applicationContext, createBluetoothOption())
        }
        return RCSPController.getInstance()
    }

    fun findBondedDevice(context: Context): BluetoothDevice? {
        if (!hasBluetoothPermission(context)) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            adapter.bondedDevices.firstOrNull { device ->
                device.address.equals(DEVICE_ADDRESS, ignoreCase = true)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    fun currentDevice(controller: RCSPController): BluetoothDevice? =
        controller.usingDevice ?: controller.connectedDeviceList.firstOrNull()

    fun hasBluetoothPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    fun connectKnownDevice(context: Context, controller: RCSPController): Boolean {
        val device = findBondedDevice(context) ?: return false
        return try {
            controller.connectDevice(device, BluetoothConstant.PROTOCOL_TYPE_BLE)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun createBluetoothOption(): BluetoothOption =
        BluetoothOption.createDefaultOption()
            .setUseMultiDevice(true)
            .setPriority(BluetoothOption.PREFER_BLE)
            .setMandatoryUseBLE(true)
            .setMtu(BluetoothConstant.BLE_MTU_MAX)
            .setUseDeviceAuth(true)
            .setBleScanMode(2)
}
