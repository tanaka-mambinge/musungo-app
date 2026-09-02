package com.musungo.mheadphones

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.jieli.bluetooth.bean.BluetoothOption
import com.jieli.bluetooth.bean.BleScanMessage
import com.jieli.bluetooth.bean.base.VoiceMode
import com.jieli.bluetooth.bean.base.BaseError
import com.jieli.bluetooth.bean.base.CommandBase
import com.jieli.bluetooth.bean.device.DeviceInfo
import com.jieli.bluetooth.bean.device.eq.EqInfo
import com.jieli.bluetooth.bean.device.eq.EqPresetInfo
import com.jieli.bluetooth.bean.response.ADVInfoResponse
import com.jieli.bluetooth.constant.AttrAndFunCode
import com.jieli.bluetooth.constant.BluetoothConstant
import com.jieli.bluetooth.constant.JL_DeviceType
import com.jieli.bluetooth.impl.rcsp.RCSPController
import com.jieli.bluetooth.utils.ParseDataUtil
import com.jieli.bluetooth.utils.CommandBuilder
import com.jieli.bluetooth.interfaces.rcsp.callback.BTRcspEventCallback
import com.jieli.bluetooth.interfaces.rcsp.callback.OnRcspActionCallback
import com.jieli.bluetooth.interfaces.bluetooth.RcspCommandCallback

class JieliModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    companion object {
        private const val NAME = "JieliModule"
        private const val EVENT = "jieliStateChanged"
        private const val CONTROLS_EVENT = "jieliControlsChanged"
        private const val DEVICE_ADDRESS = "EB:77:75:7D:7B:42"
        private const val ZENVIBE_2_PRODUCT_NAME = "ZenVibe 2"
        private const val MAX_DEVICE_NAME_BYTES = 31
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: RCSPController? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeout: Runnable? = null
    private var scanning = false
    private var callbackRegistered = false
    private var currentDevice: BluetoothDevice? = null
    private var pendingRename: Pair<BluetoothDevice, String>? = null
    private var batteryPoll: Runnable? = null
    private var reconnectGrace: Runnable? = null
    private var monitoringCase = false
    private var bluetoothOption: BluetoothOption? = null
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = try {
                device.name.orEmpty()
            } catch (_: SecurityException) {
                ""
            }

            val scanMessage = parseScanMessage(result.scanRecord?.bytes)
            if (scanMessage?.deviceType == JL_DeviceType.JL_DEVICE_TYPE_CHARGING_BIN) {
                emitCaseStatus(scanMessage)
                return
            }
            if (!isTargetJieliDevice(device, scanMessage)) return
            if (scanMessage != null) emitCaseStatus(scanMessage)
            // The case monitor also sees the buds' rotating BLE address. Treat the
            // controller's active TWS session as authoritative or the monitor can
            // mistake an already-connected bud for a new device and reconnect.
            if (controller?.isDeviceConnected() == true) return
            stopScanInternal()
            currentDevice = device
            emitState("connecting", deviceName = deviceName)
            controller?.connectDevice(device, BluetoothConstant.PROTOCOL_TYPE_BLE)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScanInternal()
            emitState("disconnected", "Bluetooth scan failed ($errorCode).")
        }
    }

    private val rcspCallback = object : BTRcspEventCallback() {
        override fun onDeviceCommand(device: BluetoothDevice, cmd: CommandBase<*, *>) {
            logWireCommand("RX", device, cmd)
        }

        override fun onDeviceResponse(device: BluetoothDevice, cmd: CommandBase<*, *>) {
            logWireCommand("RESPONSE", device, cmd)
        }

        override fun onDeviceData(device: BluetoothDevice, data: ByteArray) {
            Log.d("JieliAudioMode", "RAW_RX device=${device.address} data=${hex(data)}")
            parseAudioModeResponse(data)
        }

        override fun onExpandFunction(device: BluetoothDevice, type: Int, data: ByteArray) {
            Log.d("JieliProbe", "EXPAND type=$type raw=${hex(data)}")
        }

        override fun onConnection(device: BluetoothDevice, state: Int) {
            mainHandler.post {
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (currentDevice != null && !sameDevice(currentDevice, device)) return@post
                    currentDevice = null
                    stopBatteryPolling()
                    stopScanInternal()
                    scheduleReconnectScan()
                    return@post
                }

                val activeController = controller
                if (activeController?.isDeviceConnected() == true) {
                    cancelReconnectGrace()
                    val connectedDevice = activeController.usingDevice ?: currentDevice ?: device
                    currentDevice = connectedDevice
                    emitState("connected", deviceName = deviceName(connectedDevice))
                    queryBattery(connectedDevice)
                    startBatteryPolling(connectedDevice)
                    queryControls(connectedDevice)
                    startCaseStatusMonitor()
                    verifyPendingRename(connectedDevice)
                } else if (state == BluetoothProfile.STATE_CONNECTED) {
                    cancelReconnectGrace()
                    currentDevice = device
                    emitState("connected", deviceName = deviceName(device))
                    queryBattery(device)
                    startBatteryPolling(device)
                    queryControls(device)
                    startCaseStatusMonitor()
                    verifyPendingRename(device)
                } else if (state == BluetoothProfile.STATE_CONNECTING || state == BluetoothProfile.STATE_DISCONNECTING) {
                    cancelReconnectGrace()
                    currentDevice = device
                    emitState("connecting", deviceName = deviceName(device))
                }
            }
        }

        override fun onDeviceSettingsInfo(device: BluetoothDevice, mask: Int, info: ADVInfoResponse) {
            mainHandler.post { emitBattery(info) }
        }

        override fun onEqPresetChange(device: BluetoothDevice, info: EqPresetInfo) {
            Log.d("JieliModule", "EQ presets: ${info.eqInfos.orEmpty().joinToString { it.toString() }}")
            mainHandler.post { emitEqPresets(info) }
        }

        override fun onEqChange(device: BluetoothDevice, info: EqInfo) {
            Log.d("JieliModule", "EQ current: $info values=${info.value?.joinToString()}")
            mainHandler.post { emitEqInfo(info) }
        }

        override fun onVoiceModeList(device: BluetoothDevice, modes: List<VoiceMode>) {
            mainHandler.post { emitVoiceModes(modes) }
        }

        override fun onCurrentVoiceMode(device: BluetoothDevice, mode: VoiceMode) {
            mainHandler.post { emitCurrentVoiceMode(mode) }
        }

        override fun onError(error: BaseError) {
            mainHandler.post {
                if (controller?.isDeviceConnected() == true) {
                    emitControlMessage(error.toString())
                }
            }
        }
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun startScan() {
        mainHandler.post {
            if (!hasBluetoothPermission()) {
                emitState("disconnected", "Bluetooth permission is required.")
                return@post
            }

            val activeController = initializeController() ?: return@post
            if (activeController.isDeviceConnected()) {
                val device = activeController.usingDevice ?: currentDevice
                if (device != null) {
                    cancelReconnectGrace()
                    currentDevice = device
                    emitState("connected", deviceName = deviceName(device))
                    queryBattery(device)
                    startBatteryPolling(device)
                    queryControls(device)
                    startCaseStatusMonitor()
                }
                return@post
            }

            if (scanning) {
                emitState("scanning")
                return@post
            }

            if (currentDevice != null) {
                emitState("connecting", deviceName = deviceName(currentDevice!!))
                return@post
            }

            stopScanInternal()
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                emitState("disconnected", "Turn on Bluetooth to scan for your earbuds.")
                return@post
            }

            scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                emitState("disconnected", "Bluetooth LE scanning is unavailable.")
                return@post
            }

            val pairedDevice = try {
                adapter.bondedDevices.firstOrNull { device ->
                    device.address.equals(DEVICE_ADDRESS, ignoreCase = true)
                }
            } catch (_: SecurityException) {
                null
            }
            if (pairedDevice != null) {
                currentDevice = pairedDevice
                emitState("connecting", deviceName = deviceName(pairedDevice))
                try {
                    activeController.connectDevice(pairedDevice, BluetoothConstant.PROTOCOL_TYPE_BLE)
                    return@post
                } catch (_: SecurityException) {
                    currentDevice = null
                }
            }

            scanning = true
            emitState("scanning")
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner?.startScan(null, settings, scanCallback)
                val timeout = Runnable {
                    if (scanning) {
                        stopScanInternal()
                        emitState("disconnected", "No earbuds found yet.")
                    }
                }
                scanTimeout = timeout
                mainHandler.postDelayed(timeout, 15_000)
            } catch (_: SecurityException) {
                stopScanInternal()
                emitState("disconnected", "Bluetooth permission is required.")
            }
        }
    }

    @ReactMethod
    fun stopScan() {
        mainHandler.post { stopScanInternal() }
    }

    @ReactMethod
    fun setEqMode(mode: Int) {
        mainHandler.post {
            emitControlMessage("EQ profiles are unavailable for this firmware.")
        }
    }

    /** Temporary native-only feasibility probe for the earbuds' separate audio models. */
    @ReactMethod
    fun probeAudioMode(mode: Int) {
        mainHandler.post {
            if (mode !in 0..1) {
                Log.d("JieliAudioMode", "PROBE rejected invalid model=$mode; expected 0 or 1")
                return@post
            }

            val activeController = controller ?: return@post
            val device = currentDevice ?: activeController.usingDevice
            if (device == null || !activeController.isDeviceConnected(device)) {
                Log.d("JieliAudioMode", "PROBE unavailable: earbuds are not connected")
                return@post
            }

            val command = byteArrayOf(
                0xF5.toByte(),
                0x07,
                0x01,
                0x07,
                mode.toByte(),
                0x00,
                0x00,
            )
            val checksum = command
                .take(5)
                .sumOf { it.toInt() and 0xFF }
            command[5] = (checksum and 0xFF).toByte()
            command[6] = ((checksum ushr 8) and 0xFF).toByte()

            val sdkCommand = CommandBuilder.buildCustomCmd(command)
            Log.d("JieliAudioMode", "PROBE_SET model=$mode packet=${hex(command)} envelope=${sdkCommand.id}")
            activeController.sendRcspCommand(device, sdkCommand, object : RcspCommandCallback {
                override fun onCommandResponse(ignoredDevice: BluetoothDevice, response: CommandBase<*, *>) {
                    logWireCommand("AUDIO_CUSTOM_RESPONSE", ignoredDevice, response)
                    if (response.status == 0) {
                        mainHandler.postDelayed({ queryAudioModeInternal(activeController, device) }, 300)
                    }
                }

                override fun onErrCode(ignoredDevice: BluetoothDevice, error: BaseError) {
                    Log.d("JieliAudioMode", "AUDIO_CUSTOM_ERROR model=$mode error=$error")
                }
            })
        }
    }

    /** Temporary native-only query for the separate audio-model state. */
    @ReactMethod
    fun queryAudioMode() {
        mainHandler.post {
            val activeController = controller ?: return@post
            val device = currentDevice ?: activeController.usingDevice
            if (device == null || !activeController.isDeviceConnected(device)) {
                Log.d("JieliAudioMode", "QUERY unavailable: earbuds are not connected")
                return@post
            }
            queryAudioModeInternal(activeController, device)
        }
    }

    @ReactMethod
    fun setAncMode(mode: Int) {
        mainHandler.post {
            val activeController = controller ?: return@post
            val device = currentDevice ?: activeController.usingDevice ?: return@post
            val voiceMode = VoiceMode().setMode(mode)
            activeController.setCurrentVoiceMode(device, voiceMode, object : OnRcspActionCallback<Boolean> {
                override fun onSuccess(ignored: BluetoothDevice, ignoredResult: Boolean) {
                    activeController.getCurrentVoiceMode(device, object : OnRcspActionCallback<Boolean> {
                        override fun onSuccess(ignoredDevice: BluetoothDevice, ignoredValue: Boolean) = Unit
                        override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                            emitControlMessage(error.toString())
                        }
                    })
                }

                override fun onError(ignored: BluetoothDevice, error: BaseError) {
                    emitControlMessage(error.toString())
                }
            })
        }
    }

    @ReactMethod
    fun setGameMode(enabled: Boolean) {
        mainHandler.post {
            val activeController = controller ?: return@post
            val device = currentDevice ?: activeController.usingDevice ?: return@post
            val workMode = if (enabled) 2 else 1
            activeController.modifyDeviceSettingsInfo(
                device,
                AttrAndFunCode.ADV_TYPE_WORK_MODE,
                byteArrayOf(workMode.toByte()),
                object : OnRcspActionCallback<Int> {
                    override fun onSuccess(ignored: BluetoothDevice, result: Int) {
                        if (result == 0) {
                            queryBattery(device)
                        } else {
                            emitControlMessage("Gaming mode could not be changed.")
                        }
                    }

                    override fun onError(ignored: BluetoothDevice, error: BaseError) {
                        emitControlMessage(error.toString())
                    }
                },
            )
        }
    }

    @ReactMethod
    fun setDeviceName(name: String) {
        mainHandler.post {
            val cleanName = name.trim()
            val nameBytes = cleanName.toByteArray(Charsets.UTF_8)
            if (cleanName.isEmpty()) {
                emitRenameStatus("error", "Enter a name for your earbuds.")
                return@post
            }
            if (nameBytes.size > MAX_DEVICE_NAME_BYTES) {
                emitRenameStatus("error", "That name is too long for the earbuds.")
                return@post
            }
            if (cleanName.any { it.isISOControl() || (it.isWhitespace() && it != ' ') }) {
                emitRenameStatus("error", "Use spaces instead of line breaks or tabs.")
                return@post
            }

            val activeController = controller
            val device = currentDevice ?: activeController?.usingDevice
            if (activeController == null || device == null || !activeController.isDeviceConnected(device)) {
                emitRenameStatus("error", "Connect your earbuds before renaming them.")
                return@post
            }

            emitRenameStatus("saving", null)
            activeController.configDeviceName(device, cleanName, object : OnRcspActionCallback<Int> {
                override fun onSuccess(ignoredDevice: BluetoothDevice, result: Int) {
                    if (result != 0) {
                        mainHandler.post {
                            emitRenameStatus("error", "The earbuds rejected the new name (code $result).")
                        }
                        return
                    }
                    mainHandler.post {
                        pendingRename = device to cleanName
                        activeController.rebootDevice(device, object : OnRcspActionCallback<Boolean> {
                            override fun onSuccess(ignoredDevice: BluetoothDevice, ignoredResult: Boolean) {
                                // The saved ADV name becomes active after the device restarts.
                            }

                            override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                                pendingRename = null
                                emitRenameStatus("error", error.toString())
                            }
                        })
                    }
                }

                override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                    mainHandler.post { emitRenameStatus("error", error.toString()) }
                }
            })
        }
    }

    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    private fun initializeController(): RCSPController? {
        if (!RCSPController.isInit()) {
            val option = BluetoothOption.createDefaultOption()
                .setUseMultiDevice(true)
                .setPriority(BluetoothOption.PREFER_BLE)
                .setMandatoryUseBLE(true)
                .setMtu(BluetoothConstant.BLE_MTU_MAX)
                .setUseDeviceAuth(true)
                .setBleScanMode(2)
            bluetoothOption = option
            RCSPController.init(context.applicationContext, option)
        }

        val activeController = RCSPController.getInstance()
        controller = activeController
        if (bluetoothOption == null) bluetoothOption = activeController.bluetoothOption
        if (!callbackRegistered) {
            activeController.addBTRcspEventCallback(rcspCallback)
            callbackRegistered = true
        }
        return activeController
    }

    private fun queryBattery(device: BluetoothDevice) {
        controller?.getDeviceSettingsInfo(device, -1, object : OnRcspActionCallback<ADVInfoResponse> {
            override fun onSuccess(ignored: BluetoothDevice, info: ADVInfoResponse) {
                mainHandler.post { emitBattery(info) }
            }

            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                mainHandler.post { emitControlMessage("Battery unavailable: ${error.toString()}") }
            }
        })
    }

    private fun parseScanMessage(rawData: ByteArray?): BleScanMessage? {
        val option = bluetoothOption ?: return null
        return rawData?.let { ParseDataUtil.isFilterBleDevice(option, it) }
    }

    private fun isTargetJieliDevice(device: BluetoothDevice, scanMessage: BleScanMessage?): Boolean {
        if (device.address.equals(DEVICE_ADDRESS, ignoreCase = true)) return true
        val type = scanMessage?.deviceType ?: return false
        return type == JL_DeviceType.JL_DEVICE_TYPE_TWS_HEADSET_V1 ||
            type == JL_DeviceType.JL_DEVICE_TYPE_TWS_HEADSET_V2
    }

    private fun emitCaseStatus(scanMessage: BleScanMessage) {
        val status = scanMessage.chargingBinStatus
        if (status != 0 && status != 1) return
        Log.d(
            "JieliModule",
            "Case advertisement: status=$status battery=${scanMessage.chargingBinQuantity} charging=${scanMessage.isDeviceCharging} supported=${scanMessage.isSupportChargingCase}"
        )
        val map = Arguments.createMap()
        map.putString("caseStatus", if (status == 1) "open" else "closed")
        emitControls(map)
    }

    private fun startCaseStatusMonitor() {
        if (monitoringCase || scanning) {
            Log.d("JieliModule", "Case monitor skipped: monitoring=$monitoringCase scanning=$scanning")
            return
        }
        if (scanner == null) {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        }
        val activeScanner = scanner
        if (activeScanner == null) {
            Log.d("JieliModule", "Case monitor unavailable: BLE scanner is null")
            return
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            activeScanner.startScan(null, settings, scanCallback)
            monitoringCase = true
            Log.d("JieliModule", "Case advertisement monitor started")
        } catch (_: SecurityException) {
            monitoringCase = false
            Log.d("JieliModule", "Case monitor unavailable: Bluetooth permission rejected")
        }
    }

    private fun scheduleReconnectScan() {
        cancelReconnectGrace()
        val retry = Runnable {
            reconnectGrace = null
            if (controller?.isDeviceConnected() != true) {
                emitState("disconnected", "Connection lost. Looking for your earbuds.", shouldScan = true)
            }
        }
        reconnectGrace = retry
        mainHandler.postDelayed(retry, 4_000)
    }

    private fun cancelReconnectGrace() {
        reconnectGrace?.let { mainHandler.removeCallbacks(it) }
        reconnectGrace = null
    }

    private fun startBatteryPolling(device: BluetoothDevice) {
        stopBatteryPolling()
        val poll = object : Runnable {
            override fun run() {
                val activeController = controller
                if (activeController?.isDeviceConnected(device) != true) {
                    stopBatteryPolling()
                    return
                }
                queryBattery(device)
                mainHandler.postDelayed(this, 2_500)
            }
        }
        batteryPoll = poll
        mainHandler.postDelayed(poll, 2_500)
    }

    private fun stopBatteryPolling() {
        batteryPoll?.let { mainHandler.removeCallbacks(it) }
        batteryPoll = null
    }

    private fun queryControls(device: BluetoothDevice) {
        queryProtocolState(device)
        controller?.getEqInfo(device, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignored: BluetoothDevice, ignoredValue: Boolean) = Unit
            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                mainHandler.post { emitControlMessage("EQ unavailable: ${error.toString()}") }
            }
        })
        controller?.getAllVoiceModes(device, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignored: BluetoothDevice, ignoredValue: Boolean) = Unit
            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                mainHandler.post { emitControlMessage("ANC unavailable: ${error.toString()}") }
            }
        })
        controller?.getCurrentVoiceMode(device, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignored: BluetoothDevice, ignoredValue: Boolean) = Unit
            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                mainHandler.post { emitControlMessage("ANC unavailable: ${error.toString()}") }
            }
        })
    }

    private fun queryProtocolState(device: BluetoothDevice) {
        val activeController = controller ?: return
        activeController.requestDeviceInfo(device, -1, object : OnRcspActionCallback<DeviceInfo> {
            override fun onSuccess(ignored: BluetoothDevice, info: DeviceInfo) {
                Log.d(
                    "JieliProbe",
                    "TARGET raw=${hex(info.toData())} functionMask=${info.functionMask} protocol=${info.protocolVersion} version=${info.versionCode} name=${info.name}"
                )
                Log.d(
                    "JieliProbe",
                    "TARGET_FLAGS eq=${info.isEqEnable} banEq=${info.isBanEq} anc=${info.isSupportAnc} soundCard=${info.isSupportSoundCard} customVersion=${info.customVersionMsg}"
                )
                Log.d(
                    "JieliProbe",
                    "TARGET_STATE eq=${info.eqInfo} eqRaw=${hex(info.eqInfo?.value)} expand=${info.expandFunction} expandMask=${info.expandFunction?.mask} expandRaw=${hex(info.expandFunction?.getData(0))} systemRaw=${hex(info.systemInfo?.toData())}"
                )
            }

            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                Log.d("JieliProbe", "TARGET_ERROR ${error}")
            }
        })
        activeController.getDevSysInfo(device, 0xFF, -1, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignored: BluetoothDevice, ignoredValue: Boolean) {
                Log.d("JieliProbe", "SYS_INFO_QUERY accepted")
            }

            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                Log.d("JieliProbe", "SYS_INFO_ERROR ${error}")
            }
        })
        activeController.getExpandDataInfo(device, object : OnRcspActionCallback<Boolean> {
            override fun onSuccess(ignored: BluetoothDevice, ignoredValue: Boolean) {
                Log.d("JieliProbe", "EXPAND_QUERY accepted")
            }

            override fun onError(ignored: BluetoothDevice, error: BaseError) {
                Log.d("JieliProbe", "EXPAND_ERROR ${error}")
            }
        })
    }

    private fun queryAudioModeInternal(activeController: RCSPController, device: BluetoothDevice) {
        val query = byteArrayOf(
            0xF5.toByte(),
            0x07,
            0x00,
            0x06,
            0x08,
            0x01,
        )
        val sdkCommand = CommandBuilder.buildCustomCmd(query)
        Log.d("JieliAudioMode", "QUERY packet=${hex(query)} envelope=${sdkCommand.id}")
        activeController.sendRcspCommand(device, sdkCommand, object : RcspCommandCallback {
            override fun onCommandResponse(ignoredDevice: BluetoothDevice, response: CommandBase<*, *>) {
                logWireCommand("AUDIO_QUERY_RESPONSE", ignoredDevice, response)
                val responseData = response.response?.rawData
                if (responseData != null) parseAudioModeResponse(responseData)
            }

            override fun onErrCode(ignoredDevice: BluetoothDevice, error: BaseError) {
                Log.d("JieliAudioMode", "AUDIO_QUERY_ERROR error=$error")
            }
        })
    }

    private fun parseAudioModeResponse(data: ByteArray) {
        if (data.size < 7) return
        if ((data[0].toInt() and 0xFF) != 0xF5) return
        if ((data[1].toInt() and 0xFF) != 0x07) return
        val responseType = data[2].toInt() and 0xFF
        if (responseType != 0x02 && responseType != 0x03 && responseType != 0x0B) return
        if ((data[3].toInt() and 0xFF) != 0x07) return

        val mode = data[4].toInt() and 0xFF
        Log.d("JieliAudioMode", "MODE_RESPONSE type=$responseType model=$mode raw=${hex(data)}")
        val map = Arguments.createMap()
        map.putInt("audioMode", mode)
        emitControls(map)
    }

    private fun logWireCommand(direction: String, device: BluetoothDevice, cmd: CommandBase<*, *>) {
        Log.d(
            "JieliWire",
            "$direction device=${device.address} id=${cmd.id} opcode=${cmd.id.toString(16)} sn=${cmd.opCodeSn} type=${cmd.type} status=${cmd.status} name=${cmd.name} param=${hex(cmd.param?.paramData)} response=${hex(cmd.response?.rawData)} parsed=$cmd"
        )
    }

    private fun hex(data: ByteArray?): String =
        data?.joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) } ?: "null"

    private fun emitEqPresets(info: EqPresetInfo) {
        val map = Arguments.createMap()
        val presetArray = Arguments.createArray()
        info.eqInfos.orEmpty().forEach { preset ->
            val presetMap = Arguments.createMap()
            presetMap.putInt("mode", preset.mode)
            presetMap.putBoolean("dynamic", preset.isDynamic)
            presetMap.putArray("values", byteArrayToArray(preset.value))
            presetArray.pushMap(presetMap)
        }
        map.putArray("eqPresets", presetArray)
        map.putInt("eqPresetCount", info.eqInfos.orEmpty().size)
        map.putArray("eqFreqs", intArrayToArray(info.freqs))
        emitControls(map)
    }

    private fun emitEqInfo(info: EqInfo) {
        val map = Arguments.createMap()
        map.putInt("eqMode", info.mode)
        map.putBoolean("eqDynamic", info.isDynamic)
        map.putArray("eqValues", byteArrayToArray(info.value))
        emitControls(map)
    }

    private fun emitVoiceModes(modes: List<VoiceMode>) {
        val values = Arguments.createArray()
        modes.forEach { values.pushInt(it.mode) }
        val map = Arguments.createMap()
        map.putArray("ancModes", values)
        emitControls(map)
    }

    private fun emitCurrentVoiceMode(mode: VoiceMode) {
        val map = Arguments.createMap()
        map.putInt("ancMode", mode.mode)
        MusungoWidget.updateNoiseMode(context, mode.mode)
        emitControls(map)
    }

    private fun emitControlMessage(message: String) {
        val map = Arguments.createMap()
        map.putString("controlsMessage", message)
        emitControls(map)
    }

    private fun emitRenameStatus(status: String, message: String?) {
        val map = Arguments.createMap()
        map.putString("renameStatus", status)
        if (message == null) map.putNull("renameMessage") else map.putString("renameMessage", message)
        emitControls(map)
    }

    private fun verifyDeviceName(device: BluetoothDevice, expectedName: String) {
        val activeController = controller ?: return
        activeController.getDeviceSettingsInfo(device, -1, object : OnRcspActionCallback<ADVInfoResponse> {
            override fun onSuccess(ignoredDevice: BluetoothDevice, info: ADVInfoResponse) {
                val reportedName = info.deviceName?.takeIf { it.isNotBlank() }
                mainHandler.post {
                    if (reportedName == expectedName) {
                        pendingRename = null
                        emitBattery(info)
                        val bluetoothName = deviceName(device)
                        if (bluetoothName == expectedName) {
                            emitRenameStatus("success", "Your earbuds are now called $expectedName.")
                        } else {
                            emitRenameStatus("error", "Saved the app name, but Bluetooth still reports $bluetoothName. This firmware does not support changing its Bluetooth name.")
                        }
                    } else {
                        pendingRename = null
                        emitRenameStatus("error", "The new name could not be confirmed by the earbuds.")
                    }
                }
            }

            override fun onError(ignoredDevice: BluetoothDevice, error: BaseError) {
                mainHandler.post {
                    pendingRename = null
                    emitRenameStatus("error", "The new name was not confirmed: ${error.toString()}")
                }
            }
        })
    }

    private fun verifyPendingRename(device: BluetoothDevice) {
        val rename = pendingRename ?: return
        if (sameDevice(rename.first, device)) {
            verifyDeviceName(device, rename.second)
        }
    }

    private fun byteArrayToArray(values: ByteArray?): WritableArray {
        val array = Arguments.createArray()
        values?.forEach { array.pushInt(it.toInt()) }
        return array
    }

    private fun intArrayToArray(values: IntArray?): WritableArray {
        val array = Arguments.createArray()
        values?.forEach { array.pushInt(it) }
        return array
    }

    private fun emitBattery(info: ADVInfoResponse) {
        val map = Arguments.createMap()
        map.putString("status", "connected")
        map.putBoolean("scanning", false)
        val reportedName = info.deviceName?.takeIf { it.isNotBlank() }
        val resolvedName = reportedName ?: currentDevice?.let { deviceName(it) } ?: "Earbuds"
        map.putString("deviceName", resolvedName)
        val resolvedProductName = currentDevice?.let { productName(it) }
        if (resolvedProductName == null) map.putNull("productName") else map.putString("productName", resolvedProductName)
        putBattery(map, "left", info.leftDeviceQuantity, info.isLeftCharging)
        putBattery(map, "right", info.rightDeviceQuantity, info.isRightCharging)
        putBattery(map, "case", info.chargingBinQuantity, info.isDeviceCharging)
        map.putBoolean("leftCharging", info.isLeftCharging)
        map.putBoolean("rightCharging", info.isRightCharging)
        map.putBoolean("caseCharging", info.isDeviceCharging)
        map.putBoolean("batteryReady", true)
        map.putNull("message")
        map.putBoolean("shouldScan", false)
        val controlsMap = Arguments.createMap()
        controlsMap.putBoolean("gameMode", info.workModel == 2)
        MusungoWidget.updateDeviceName(context, resolvedName)
        MusungoWidget.updateState(
            context,
            "connected",
            info.leftDeviceQuantity.takeIf { it > 0 },
            info.rightDeviceQuantity.takeIf { it > 0 },
            info.isLeftCharging,
            info.isRightCharging,
        )
        emit(map)
        emitControls(controlsMap)
    }

    private fun putBattery(map: WritableMap, key: String, value: Int, charging: Boolean) {
        if (value <= 0 && !charging) {
            map.putNull(key)
        } else {
            map.putInt(key, value.coerceIn(0, 100))
        }
    }

    private fun emitState(status: String, message: String? = null, deviceName: String? = null, shouldScan: Boolean = false) {
        val map = Arguments.createMap()
        map.putString("status", status)
        map.putBoolean("scanning", status == "scanning" || status == "connecting")
        if (deviceName == null) map.putNull("deviceName") else map.putString("deviceName", deviceName)
        val resolvedProductName = currentDevice?.let { productName(it) }
        if (resolvedProductName == null) map.putNull("productName") else map.putString("productName", resolvedProductName)
        map.putNull("left")
        map.putNull("right")
        map.putNull("case")
        map.putBoolean("leftCharging", false)
        map.putBoolean("rightCharging", false)
        map.putBoolean("caseCharging", false)
        map.putBoolean("batteryReady", false)
        if (message == null) map.putNull("message") else map.putString("message", message)
        map.putBoolean("shouldScan", shouldScan)
        if (deviceName != null) MusungoWidget.updateDeviceName(context, deviceName)
        MusungoWidget.updateState(context, status, null, null)
        emit(map)
    }

    private fun emit(map: WritableMap) {
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(EVENT, map)
    }

    private fun emitControls(map: WritableMap) {
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(CONTROLS_EVENT, map)
    }

    private fun stopScanInternal() {
        if (scanning || monitoringCase) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Permission loss should not prevent the UI from leaving scanning state.
            }
        }
        scanning = false
        monitoringCase = false
        scanTimeout?.let { mainHandler.removeCallbacks(it) }
        scanTimeout = null
    }

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private fun deviceName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: "Earbuds"
    } catch (_: SecurityException) {
        "Earbuds"
    }

    private fun productName(device: BluetoothDevice): String? =
        if (device.address.equals(DEVICE_ADDRESS, ignoreCase = true)) ZENVIBE_2_PRODUCT_NAME else null

    private fun sameDevice(first: BluetoothDevice?, second: BluetoothDevice): Boolean =
        first?.address?.equals(second.address, ignoreCase = true) == true

    override fun onCatalystInstanceDestroy() {
        stopScanInternal()
        stopBatteryPolling()
        cancelReconnectGrace()
        controller?.removeBTRcspEventCallback(rcspCallback)
        super.onCatalystInstanceDestroy()
    }
}
