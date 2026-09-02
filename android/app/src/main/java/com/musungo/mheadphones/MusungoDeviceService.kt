package com.musungo.mheadphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jieli.bluetooth.bean.base.BaseError
import com.jieli.bluetooth.bean.base.VoiceMode
import com.jieli.bluetooth.bean.response.ADVInfoResponse
import com.jieli.bluetooth.impl.rcsp.RCSPController
import com.jieli.bluetooth.interfaces.rcsp.callback.BTRcspEventCallback
import com.jieli.bluetooth.interfaces.rcsp.callback.OnRcspActionCallback

class MusungoDeviceService : Service() {
    companion object {
        const val ACTION_START_MONITOR = "com.musungo.mheadphones.START_MONITOR"
        const val ACTION_CYCLE_NOISE = "com.musungo.mheadphones.CYCLE_NOISE"

        private const val CHANNEL_ID = "musungo_device_monitor"
        private const val NOTIFICATION_ID = 7701
        private const val BATTERY_POLL_INTERVAL_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        fun startMonitoring(context: android.content.Context) {
            start(context, ACTION_START_MONITOR)
        }

        fun cycleNoise(context: android.content.Context) {
            start(context, ACTION_CYCLE_NOISE)
        }

        fun stopMonitoring(context: android.content.Context) {
            context.stopService(Intent(context, MusungoDeviceService::class.java))
        }

        private fun start(context: android.content.Context, action: String) {
            val intent = Intent(context, MusungoDeviceService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                Log.e("MusungoService", "Unable to start background monitoring", error)
                MusungoWidget.updateState(context, "disconnected", null, null)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: RCSPController? = null
    private var activeDevice: BluetoothDevice? = null
    private var connected = false
    private var pendingCycle = false
    private var connectTimeout: Runnable? = null
    private var batteryPoll: Runnable? = null

    private val rcspCallback = object : BTRcspEventCallback() {
        override fun onConnection(device: BluetoothDevice, state: Int) {
            mainHandler.post {
                when {
                    state == BluetoothProfile.STATE_CONNECTED -> {
                        val activeController = controller
                        if (activeController?.isDeviceConnected(device) == true || activeController?.isDeviceConnected() == true) {
                            handleConnected(activeController, device)
                        }
                    }
                    state == BluetoothProfile.STATE_CONNECTING || state == BluetoothProfile.STATE_DISCONNECTING -> {
                        if (!connected) {
                            activeDevice = device
                            MusungoWidget.updateState(this@MusungoDeviceService, "connecting", null, null)
                            updateNotification("Connecting to your earbuds")
                        }
                    }
                    state == BluetoothProfile.STATE_DISCONNECTED -> {
                        if (activeDevice == null || activeDevice?.address.equals(device.address, ignoreCase = true)) {
                            handleDisconnected()
                        }
                    }
                }
            }
        }

        override fun onDeviceSettingsInfo(device: BluetoothDevice, mask: Int, info: ADVInfoResponse) {
            mainHandler.post {
                if (connected && isActiveDevice(device)) updateBattery(info)
            }
        }

        override fun onCurrentVoiceMode(device: BluetoothDevice, mode: VoiceMode) {
            mainHandler.post {
                if (connected && isActiveDevice(device)) MusungoWidget.updateNoiseMode(this@MusungoDeviceService, mode.mode)
            }
        }

        override fun onError(error: BaseError) {
            Log.d("MusungoService", "Jieli error: $error")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MusungoService", "Service created")
        createNotificationChannel()
        promoteToForeground("Connecting to your earbuds")
        if (JieliControllerRuntime.hasBluetoothPermission(this)) {
            controller = JieliControllerRuntime.getController(this)
            controller?.addBTRcspEventCallback(rcspCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MusungoService", "Start command action=${intent?.action} startId=$startId")
        if (!MusungoWidget.hasWidgets(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CYCLE_NOISE) pendingCycle = true
        ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        connectTimeout?.let(mainHandler::removeCallbacks)
        batteryPoll?.let(mainHandler::removeCallbacks)
        controller?.removeBTRcspEventCallback(rcspCallback)
        activeDevice = null
        connected = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureConnected() {
        if (!MusungoWidget.hasWidgets(this)) {
            stopSelf()
            return
        }
        if (!JieliControllerRuntime.hasBluetoothPermission(this)) {
            MusungoWidget.updateState(this, "disconnected", null, null)
            stopSelf()
            return
        }

        val activeController = controller ?: JieliControllerRuntime.getController(this).also { controller = it }
        val device = JieliControllerRuntime.currentDevice(activeController)
        Log.d(
            "MusungoService",
            "Ensure connection connected=${activeController.isDeviceConnected()} device=${device?.address} pendingCycle=$pendingCycle",
        )
        if (activeController.isDeviceConnected() && device != null) {
            handleConnected(activeController, device)
            if (pendingCycle) sendNextNoiseMode(device)
            return
        }

        connected = false
        activeDevice = JieliControllerRuntime.findBondedDevice(this)
        MusungoWidget.updateState(this, "connecting", null, null)
        updateNotification("Connecting to your earbuds")
        val connectStarted = JieliControllerRuntime.connectKnownDevice(this, activeController)
        if (!connectStarted) {
            Log.d("MusungoService", "No bonded ZenVibe device available for background connection")
            pendingCycle = false
            MusungoWidget.updateState(this, "disconnected", null, null)
            stopSelf()
            return
        }

        connectTimeout?.let(mainHandler::removeCallbacks)
        val timeout = Runnable {
            if (!connected) {
                Log.d("MusungoService", "Background connection timed out")
                pendingCycle = false
                MusungoWidget.updateState(this, "disconnected", null, null)
                stopSelf()
            }
        }
        connectTimeout = timeout
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private fun handleConnected(activeController: RCSPController, device: BluetoothDevice) {
        Log.d("MusungoService", "Connected device=${device.address} pendingCycle=$pendingCycle")
        if (connected && isActiveDevice(device)) {
            if (pendingCycle) sendNextNoiseMode(device)
            return
        }

        connected = true
        activeDevice = device
        connectTimeout?.let(mainHandler::removeCallbacks)
        connectTimeout = null
        updateNotification("Monitoring your earbuds")
        MusungoWidget.updateDeviceName(this, safeDeviceName(device))
        MusungoWidget.updateState(this, "connected", null, null)
        queryBattery(activeController, device)
        queryCurrentVoiceMode(activeController, device)
        startBatteryPolling(activeController, device)
        if (pendingCycle) sendNextNoiseMode(device)
    }

    private fun handleDisconnected() {
        if (!connected && activeDevice == null) return
        connected = false
        activeDevice = null
        batteryPoll?.let(mainHandler::removeCallbacks)
        batteryPoll = null
        pendingCycle = false
        MusungoWidget.updateState(this, "disconnected", null, null)
        stopSelf()
    }

    private fun startBatteryPolling(activeController: RCSPController, device: BluetoothDevice) {
        batteryPoll?.let(mainHandler::removeCallbacks)
        val poll = object : Runnable {
            override fun run() {
                if (!connected || !isActiveDevice(device) || activeController.isDeviceConnected(device).not()) {
                    batteryPoll = null
                    return
                }
                queryBattery(activeController, device)
                mainHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
            }
        }
        batteryPoll = poll
        mainHandler.postDelayed(poll, BATTERY_POLL_INTERVAL_MS)
    }

    private fun queryBattery(activeController: RCSPController, device: BluetoothDevice) {
        activeController.getDeviceSettingsInfo(device, -1, object : OnRcspActionCallback<ADVInfoResponse> {
            override fun onSuccess(ignoredDevice: BluetoothDevice, info: ADVInfoResponse) {
                mainHandler.post {
                    if (connected && isActiveDevice(device)) updateBattery(info)
                }
            }

            override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                Log.d("MusungoService", "Battery query failed: $error")
            }
        })
    }

    private fun queryCurrentVoiceMode(activeController: RCSPController, device: BluetoothDevice) {
        activeController.getCurrentVoiceMode(device, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignoredDevice: BluetoothDevice, ignoredValue: Boolean) = Unit

            override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                Log.d("MusungoService", "Voice mode query failed: $error")
            }
        })
    }

    private fun sendNextNoiseMode(device: BluetoothDevice) {
        val activeController = controller ?: return
        if (!connected || !activeController.isDeviceConnected(device)) {
            Log.d("MusungoService", "Skipping noise command connected=$connected device=${device.address}")
            return
        }
        pendingCycle = false
        val prefs = getSharedPreferences(MusungoWidget.PREFS, MODE_PRIVATE)
        val currentMode = prefs.getInt(MusungoWidget.NOISE_MODE, VoiceMode.VOICE_MODE_CLOSE)
        val nextMode = (currentMode + 1) % 3
        Log.d("MusungoService", "Sending noise mode $currentMode -> $nextMode")
        activeController.setCurrentVoiceMode(device, VoiceMode().setMode(nextMode), object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignoredDevice: BluetoothDevice, ignoredResult: Boolean) {
                Log.d("MusungoService", "Noise mode command succeeded mode=$nextMode")
                queryCurrentVoiceMode(activeController, device)
                queryBattery(activeController, device)
            }

            override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                Log.d("MusungoService", "Noise mode command failed: $error")
            }
        })
    }

    private fun updateBattery(info: ADVInfoResponse) {
        val name = info.deviceName?.takeIf { it.isNotBlank() } ?: activeDevice?.let(::safeDeviceName) ?: "Earbuds"
        MusungoWidget.updateDeviceName(this, name)
        MusungoWidget.updateState(
            this,
            "connected",
            info.leftDeviceQuantity.takeIf { it > 0 },
            info.rightDeviceQuantity.takeIf { it > 0 },
            info.isLeftCharging,
            info.isRightCharging,
        )
    }

    private fun isActiveDevice(device: BluetoothDevice): Boolean =
        activeDevice?.address?.equals(device.address, ignoreCase = true) == true

    private fun safeDeviceName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: "Earbuds"
    } catch (_: SecurityException) {
        "Earbuds"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Earbud monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the earbud widget updated while connected"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground(message: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(message), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(message))
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Musungo Headphones")
            .setContentText(message)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
