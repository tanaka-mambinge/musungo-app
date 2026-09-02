package com.musungo.mheadphones

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class MusungoWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CYCLE_NOISE) MusungoDeviceService.cycleNoise(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        setWidgetCount(context, manager.getAppWidgetIds(ComponentName(context, MusungoWidget::class.java)).size)
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onEnabled(context: Context) {
        setWidgetCount(context, 1)
        MusungoDeviceService.startMonitoring(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        setWidgetCount(context, manager.getAppWidgetIds(ComponentName(context, MusungoWidget::class.java)).size)
        if (!hasWidgets(context)) MusungoDeviceService.stopMonitoring(context)
    }

    override fun onDisabled(context: Context) {
        setWidgetCount(context, 0)
        MusungoDeviceService.stopMonitoring(context)
    }

    companion object {
        internal const val PREFS = "musungo_widget"
        private const val STATUS = "status"
        private const val DEVICE_NAME = "device_name"
        private const val LEFT = "left"
        private const val RIGHT = "right"
        private const val LEFT_CHARGING = "left_charging"
        private const val RIGHT_CHARGING = "right_charging"
        internal const val NOISE_MODE = "noise_mode"
        private const val WIDGET_COUNT = "widget_count"
        private const val ACTION_CYCLE_NOISE = MusungoDeviceService.ACTION_CYCLE_NOISE

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, MusungoWidget::class.java))
                .isNotEmpty()

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

        private fun setWidgetCount(context: Context, count: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(WIDGET_COUNT, count)
                .apply()
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
            views.setViewVisibility(R.id.widget_battery_row, if (connected) android.view.View.VISIBLE else android.view.View.GONE)
            views.setViewVisibility(R.id.widget_state_row, if (connected) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_noise_row, if (connected) android.view.View.VISIBLE else android.view.View.GONE)
            views.setImageViewResource(R.id.widget_state_icon, connectionIcon(status))
            views.setTextViewText(R.id.widget_state_message, connectionMessage(status))
            views.setTextViewText(R.id.widget_state_detail, connectionDetail(status))
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

            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_state_row, openAppPendingIntent)

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

        private fun connectionIcon(status: String): Int = when (status) {
            "disconnected" -> R.drawable.widget_status_disconnected
            else -> R.drawable.widget_status_connecting
        }

        private fun connectionMessage(status: String): String = when (status) {
            "connecting" -> "Connecting to your earbuds"
            "scanning" -> "Looking for your earbuds"
            else -> "Earbuds disconnected"
        }

        private fun connectionDetail(status: String): String = when (status) {
            "connecting" -> "Keep them nearby"
            "scanning" -> "Keep the case open"
            else -> "Tap to open Musungo and reconnect"
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
