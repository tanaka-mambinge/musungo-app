package com.musungo.mheadphones

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        private val ICON_TINT = Color.parseColor("#D7DEDB")

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, MusungoWidget::class.java))
                .isNotEmpty()

        fun isConnected(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(STATUS, null) == "connected"

        fun updateState(
            context: Context,
            status: String,
            left: Int?,
            right: Int?,
            leftCharging: Boolean = false,
            rightCharging: Boolean = false,
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(STATUS, status)
            if (status == "connected") {
                editor
                    .putString(LEFT, left?.toString())
                    .putString(RIGHT, right?.toString())
                    .putBoolean(LEFT_CHARGING, leftCharging)
                    .putBoolean(RIGHT_CHARGING, rightCharging)
            }
            editor.apply()
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
            val views = RemoteViews(context.packageName, R.layout.widget_musungo)

            if (connected) {
                bindConnectedState(context, views, widgetId, prefs)
            } else {
                bindSkeletonState(views, widgetId, context)
            }

            manager.updateAppWidget(widgetId, views)
        }

        private fun bindConnectedState(context: Context, views: RemoteViews, widgetId: Int, prefs: android.content.SharedPreferences) {
            val name = prefs.getString(DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: "Musungo earbuds"
            val left = prefs.getString(LEFT, null)?.toIntOrNull()?.coerceIn(0, 100)
            val right = prefs.getString(RIGHT, null)?.toIntOrNull()?.coerceIn(0, 100)
            val leftCharging = prefs.getBoolean(LEFT_CHARGING, false)
            val rightCharging = prefs.getBoolean(RIGHT_CHARGING, false)
            val noiseMode = prefs.getInt(NOISE_MODE, 0)

            views.setViewVisibility(R.id.widget_connected_content, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_skeleton_content, android.view.View.GONE)
            views.setTextViewText(R.id.widget_device_name, name)

            views.setImageViewBitmap(R.id.widget_left_ring, createRingBitmap(left))
            views.setImageViewBitmap(R.id.widget_right_ring, createRingBitmap(right))
            views.setImageViewResource(R.id.widget_left_bud, R.drawable.widget_earbud_right)
            views.setImageViewResource(R.id.widget_right_bud, R.drawable.widget_earbud_left)

            views.setImageViewResource(R.id.widget_left_battery_icon, batteryIcon(left, leftCharging))
            views.setImageViewResource(R.id.widget_right_battery_icon, batteryIcon(right, rightCharging))
            views.setInt(R.id.widget_left_battery_icon, "setColorFilter", ICON_TINT)
            views.setInt(R.id.widget_right_battery_icon, "setColorFilter", ICON_TINT)
            views.setTextViewText(R.id.widget_left_battery, formatBattery(left))
            views.setTextViewText(R.id.widget_right_battery, formatBattery(right))

            views.setImageViewResource(R.id.widget_noise_mode_icon, noiseIcon(noiseMode))
            views.setInt(R.id.widget_noise_mode_icon, "setColorFilter", ICON_TINT)
            views.setTextViewText(R.id.widget_noise_mode, noiseLabel(noiseMode))

            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title_button, openAppPendingIntent)

            val cycleIntent = Intent(context, MusungoWidget::class.java).setAction(ACTION_CYCLE_NOISE)
            val cyclePendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                cycleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_noise_row, cyclePendingIntent)
        }

        private fun bindSkeletonState(views: RemoteViews, widgetId: Int, context: Context) {
            views.setViewVisibility(R.id.widget_connected_content, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_skeleton_content, android.view.View.VISIBLE)

            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_skeleton_content, openAppPendingIntent)
        }

        private fun createRingBitmap(percent: Int?): Bitmap {
            val lowBattery = percent != null && percent <= 30 && percent > 0
            val size = 240
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val strokeWidth = 24f
            val inset = strokeWidth / 2f + 2f
            val ringRect = RectF(inset, inset, size - inset, size - inset)

            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                color = Color.parseColor("#33403C")
                strokeCap = Paint.Cap.ROUND
            }

            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                color = Color.parseColor(if (lowBattery) "#FF6B6B" else "#70DF90")
                strokeCap = Paint.Cap.ROUND
            }

            canvas.drawArc(ringRect, 0f, 360f, false, basePaint)
            if (percent != null) {
                canvas.drawArc(ringRect, -90f, 3.6f * percent, false, progressPaint)
            }
            return bitmap
        }

        private fun formatBattery(value: Int?): String = value?.let { "$it%" } ?: "—"

        private fun batteryIcon(percent: Int?, charging: Boolean): Int {
            if (charging) return R.drawable.widget_battery_charging
            val value = percent ?: return R.drawable.widget_battery
            return when {
                value <= 20 -> R.drawable.widget_battery
                value <= 40 -> R.drawable.widget_battery_1
                value <= 60 -> R.drawable.widget_battery_2
                value <= 80 -> R.drawable.widget_battery_3
                else -> R.drawable.widget_battery_4
            }
        }

        private fun noiseLabel(mode: Int): String = when (mode) {
            1 -> "ANC"
            2 -> "Ambient"
            else -> "Off"
        }

        private fun noiseIcon(mode: Int): Int = when (mode) {
            1 -> R.drawable.widget_noise_mode_cancel
            2 -> R.drawable.widget_noise_mode_transparency
            else -> R.drawable.widget_noise_mode_off
        }
    }
}
