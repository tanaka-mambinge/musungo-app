package com.musungo.mheadphones

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.jieli.bluetooth.bean.base.BaseError
import com.jieli.bluetooth.bean.base.VoiceMode
import com.jieli.bluetooth.impl.rcsp.RCSPController
import com.jieli.bluetooth.interfaces.rcsp.callback.OnRcspActionCallback

class MusungoWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CYCLE_NOISE) cycleNoise(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        private const val PREFS = "musungo_widget"
        private const val STATUS = "status"
        private const val DEVICE_NAME = "device_name"
        private const val LEFT = "left"
        private const val RIGHT = "right"
        private const val LEFT_CHARGING = "left_charging"
        private const val RIGHT_CHARGING = "right_charging"
        private const val NOISE_MODE = "noise_mode"
        private const val ACTION_CYCLE_NOISE = "com.musungo.mheadphones.ACTION_CYCLE_NOISE"

        fun updateState(
            context: Context,
            status: String,
            left: Int?,
            right: Int?,
            leftCharging: Boolean = false,
            rightCharging: Boolean = false,
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(STATUS, status)
                .putString(LEFT, left?.toString())
                .putString(RIGHT, right?.toString())
                .putBoolean(LEFT_CHARGING, leftCharging)
                .putBoolean(RIGHT_CHARGING, rightCharging)
                .apply()
            updateAll(context)
        }

        fun updateDeviceName(context: Context, name: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(DEVICE_NAME, name)
                .apply()
            updateAll(context)
        }

        fun updateNoiseMode(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(NOISE_MODE, mode)
                .apply()
            updateAll(context)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MusungoWidget::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun cycleNoise(context: Context) {
            if (!RCSPController.isInit()) return
            val controller = RCSPController.getInstance()
            if (!controller.isDeviceConnected()) return
            val device = controller.usingDevice ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val currentMode = prefs.getInt(NOISE_MODE, 0)
            val nextMode = (currentMode + 1) % 3
            controller.setCurrentVoiceMode(device, VoiceMode().setMode(nextMode), object : OnRcspActionCallback<Boolean> {
                override fun onSuccess(ignored: BluetoothDevice, ignoredResult: Boolean) {
                    updateNoiseMode(context, nextMode)
                }

                override fun onError(ignored: BluetoothDevice, ignoredError: BaseError) = Unit
            })
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val status = prefs.getString(STATUS, "disconnected") ?: "disconnected"
            val connected = status == "connected"
            val name = prefs.getString(DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: "Musungo earbuds"
            val left = if (connected) prefs.getString(LEFT, null) ?: "—" else "—"
            val right = if (connected) prefs.getString(RIGHT, null) ?: "—" else "—"
            val leftCharging = connected && prefs.getBoolean(LEFT_CHARGING, false)
            val rightCharging = connected && prefs.getBoolean(RIGHT_CHARGING, false)
            val noiseMode = prefs.getInt(NOISE_MODE, 0)
            val views = RemoteViews(context.packageName, R.layout.widget_musungo)
            views.setTextViewText(R.id.widget_device_name, name)
            views.setTextViewText(R.id.widget_connection_status, connectionLabel(status))
            views.setTextColor(R.id.widget_connection_status, connectionColor(status))
            views.setTextViewText(R.id.widget_left_battery, formatBattery("L", left))
            views.setTextViewText(R.id.widget_right_battery, formatBattery("R", right))
            views.setProgressBar(R.id.widget_left_battery_progress, 100, batteryProgress(left), false)
            views.setProgressBar(R.id.widget_right_battery_progress, 100, batteryProgress(right), false)
            views.setImageViewResource(R.id.widget_left_battery_icon, batteryIcon(left, leftCharging))
            views.setImageViewResource(R.id.widget_right_battery_icon, batteryIcon(right, rightCharging))
            views.setInt(R.id.widget_left_battery_icon, "setColorFilter", batteryColor(leftCharging))
            views.setInt(R.id.widget_right_battery_icon, "setColorFilter", batteryColor(rightCharging))
            views.setTextViewText(R.id.widget_noise_mode, noiseLabel(noiseMode))
            views.setImageViewResource(R.id.widget_noise_mode_icon, noiseIcon(noiseMode))
            views.setImageViewResource(R.id.widget_left_bud, R.drawable.widget_earbud_left)
            views.setImageViewResource(R.id.widget_right_bud, R.drawable.widget_earbud_right)

            val cycleIntent = Intent(context, MusungoWidget::class.java).setAction(ACTION_CYCLE_NOISE)
            val cyclePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                cycleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_noise_row, cyclePendingIntent)
            manager.updateAppWidget(widgetId, views)
        }

        private fun formatBattery(label: String, value: String): String = if (value == "—") "$label  —" else "$label  $value%"

        private fun batteryProgress(value: String): Int = value.toIntOrNull()?.coerceIn(0, 100) ?: 0

        private fun batteryIcon(value: String, charging: Boolean): Int {
            if (charging) return R.drawable.widget_battery_charging
            val percent = value.toIntOrNull() ?: return R.drawable.widget_battery
            return when {
                percent < 20 -> R.drawable.widget_battery
                percent < 40 -> R.drawable.widget_battery_1
                percent < 60 -> R.drawable.widget_battery_2
                percent < 80 -> R.drawable.widget_battery_3
                else -> R.drawable.widget_battery_4
            }
        }

        private fun batteryColor(charging: Boolean): Int =
            Color.parseColor(if (charging) "#70DF90" else "#BCBCBC")

        private fun connectionLabel(status: String): String = when (status) {
            "connected" -> "Connected"
            "connecting" -> "Connecting…"
            "scanning" -> "Searching…"
            else -> "Disconnected"
        }

        private fun connectionColor(status: String): Int = when (status) {
            "connected" -> Color.parseColor("#70DF90")
            "connecting", "scanning" -> Color.parseColor("#FFC66D")
            else -> Color.parseColor("#AEB4BD")
        }

        private fun noiseLabel(mode: Int): String = when (mode) {
            1 -> "Noise cancellation"
            2 -> "Transparency"
            else -> "Noise controls off"
        }

        private fun noiseIcon(mode: Int): Int = when (mode) {
            1 -> R.drawable.widget_noise_mode_cancel
            2 -> R.drawable.widget_noise_mode_transparency
            else -> R.drawable.widget_noise_mode_off
        }
    }
}
