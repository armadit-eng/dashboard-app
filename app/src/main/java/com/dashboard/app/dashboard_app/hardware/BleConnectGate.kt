package com.dashboard.app.dashboard_app.hardware

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

/**
 * Strict FIFO serialization gate for BLE GATT setup
 * (connectGatt -> requestMtu -> discoverServices -> CCCD enable).
 * Prevents concurrent GATT negotiations from triggering Android status 133 errors.
 */
object BleConnectGate {
    private const val TAG = "BleConnectGate"
    private const val HOLD_TIMEOUT_MS = 25_000L

    private data class Pending(val who: String, val onReady: () -> Unit)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pending>()
    private val abortHooks = HashMap<String, () -> Unit>()

    private var holder: String? = null
    private var watchdog: Runnable? = null

    @Synchronized
    fun setAbortHook(who: String, hook: () -> Unit) {
        abortHooks[who] = hook
    }

    @Synchronized
    fun waitingFor(): String = holder?.let { "menunggu $it" } ?: ""

    @Synchronized
    fun acquire(who: String, onReady: () -> Unit) {
        if (holder == null && queue.isEmpty()) {
            holder = who
            armWatchdog(who)
            Log.d(TAG, "$who acquired BLE gate")
            onReady()
        } else {
            queue.offer(Pending(who, onReady))
            Log.d(TAG, "$who queued (holder=$holder, queue=${queue.size})")
        }
    }

    @Synchronized
    fun release(who: String) {
        if (holder == null || holder != who) return
        holder = null
        cancelWatchdog()
        Log.d(TAG, "$who released BLE gate")
        val next = queue.poll() ?: return
        holder = next.who
        armWatchdog(next.who)
        mainHandler.post(next.onReady)
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun armWatchdog(who: String) {
        cancelWatchdog()
        val task = Runnable {
            val hook: (() -> Unit)?
            synchronized(this) {
                if (holder != who) return@Runnable
                Log.w(TAG, "$who held gate > ${HOLD_TIMEOUT_MS}ms — force releasing")
                hook = abortHooks[who]
            }
            try {
                hook?.invoke()
            } catch (_: Exception) {
            }
            release(who)
        }
        watchdog = task
        mainHandler.postDelayed(task, HOLD_TIMEOUT_MS)
    }
}
