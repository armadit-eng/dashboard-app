package com.dashboard.app.dashboard_app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class GesitsWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val PREFS = "gesits_widget_prefs"
        private const val KEY_SOC = "soc"
        private const val KEY_RANGE = "range"
        private const val KEY_BIKE = "bike"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_UPDATED = "updated"

        fun updateSnapshot(
            context: Context,
            socPct: Double?,
            rangeKm: Double?,
            bikeName: String,
            connected: Boolean
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            prefs.edit()
                .putString(KEY_SOC, socPct?.let { "${it.roundToInt()}%" } ?: "--%")
                .putString(KEY_RANGE, rangeKm?.let { "${String.format(Locale.US, "%.1f", it)} km" } ?: "-- km")
                .putString(KEY_BIKE, bikeName.ifBlank { "GESITS Cockpit" })
                .putBoolean(KEY_CONNECTED, connected)
                .putString(KEY_UPDATED, "Update $stamp")
                .apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, GesitsWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    renderWidget(context, mgr, id)
                }
            }
        }

        private fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val soc = prefs.getString(KEY_SOC, "--%") ?: "--%"
            val range = prefs.getString(KEY_RANGE, "-- km") ?: "-- km"
            val bike = prefs.getString(KEY_BIKE, "GESITS Cockpit") ?: "GESITS Cockpit"
            val connected = prefs.getBoolean(KEY_CONNECTED, false)
            val updated = prefs.getString(KEY_UPDATED, "Ketuk untuk buka dashboard") ?: ""

            val views = RemoteViews(context.packageName, R.layout.widget_gesits)
            views.setTextViewText(R.id.widgetBikeName, bike)
            views.setTextViewText(R.id.widgetSoc, soc)
            views.setTextViewText(R.id.widgetRange, range)
            views.setTextViewText(R.id.widgetStatus, if (connected) "Terhubung" else "Terputus")
            views.setTextColor(
                R.id.widgetStatus,
                if (connected) 0xFF00E5A3.toInt() else 0xFF94A3B8.toInt()
            )
            views.setTextViewText(R.id.widgetUpdatedAt, updated)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getActivity(context, appWidgetId, launchIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            renderWidget(context, appWidgetManager, id)
        }
    }
}
