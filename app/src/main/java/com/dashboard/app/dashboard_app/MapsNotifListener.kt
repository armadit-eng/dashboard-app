package com.dashboard.app.dashboard_app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.Normalizer

/**
 * Reads active Google Maps turn-by-turn notifications so ScreenMirrorService
 * can render an info strip, fallback turn cards when screen turns off, and push
 * native AT+NAV guidance to the motorcycle MID.
 */
class MapsNotifListener : NotificationListenerService() {

    companion object {
        private const val MAPS_PKG = "com.google.android.apps.maps"

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, MapsNotifListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(target, ignoreCase = true) || it.contains(MapsNotifListener::class.java.name) }
        }
    }

    private var lastKey = ""

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PKG) return
        val n = sbn.notification ?: return
        val e = n.extras ?: return

        val title = cleanStr(e.getCharSequence(Notification.EXTRA_TITLE))
        var text = cleanStr(e.getCharSequence(Notification.EXTRA_TEXT))
        val sub = cleanStr(e.getCharSequence(Notification.EXTRA_SUB_TEXT))
        if (text.isEmpty()) {
            text = cleanStr(e.getCharSequence(Notification.EXTRA_BIG_TEXT))
        }
        if (title.isEmpty() && text.isEmpty() && sub.isEmpty()) return

        val key = "$title|$text|$sub"
        if (key == lastKey) return
        lastKey = key

        if (text.isNotEmpty()) {
            ScreenMirrorService.navInstr = text.replaceFirst(Regex("(?i)^ke arah\\s+"), "")
        }
        if (title.isNotEmpty() && title[0].isDigit()) {
            ScreenMirrorService.navDist = title
        }
        if (sub.isNotEmpty()) {
            ScreenMirrorService.navEta = sub.replace(Regex("(?i)PWT:?\\s*"), "tiba ")
        }
        ScreenMirrorService.navUpdatedMs = System.currentTimeMillis()
        captureTurnIcon(n)

        if (ScreenMirrorService.isRunning) {
            val line = formatNavLine(ScreenMirrorService.navDist, ScreenMirrorService.navInstr)
            if (line.isNotEmpty()) {
                ScreenMirrorService.onExternalNavLine?.invoke(line)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PKG) return
        ScreenMirrorService.navDist = ""
        ScreenMirrorService.navInstr = ""
        ScreenMirrorService.navEta = ""
        ScreenMirrorService.navUpdatedMs = 0L
        ScreenMirrorService.navIcon = null
        lastKey = ""
    }

    private fun captureTurnIcon(n: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val ic = n.getLargeIcon() ?: return
            val drawable = ic.loadDrawable(this) ?: return
            val sz = 96
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, sz, sz)
            drawable.draw(canvas)
            ScreenMirrorService.navIcon = bmp
        } catch (_: Exception) {
        }
    }

    private fun formatNavLine(dist: String, instr: String): String {
        val raw = (if (dist.isEmpty()) "" else "$dist ") + instr
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return ""
        return if (normalized.length > 20) normalized.substring(0, 20).trim() else normalized
    }

    private fun cleanStr(cs: CharSequence?): String =
        cs?.toString()?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim() ?: ""
}
