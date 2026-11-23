package com.dashboard.app.dashboard_app.hardware

/**
 * Bluetooth radio coexistence coordinator: Classic SPP (AT+MAPS frames & MID polling)
 * and BLE GATT (JK BMS, ESP32 #1, ESP32 #2) share one 2.4 GHz radio.
 * Stretching BLE reconnect delays while Classic SPP is actively writing prevents
 * repeated connectGatt() scans/attempts from degrading AT+MAPS throughput.
 */
object BleCoex {
    @Volatile
    private var lastClassicWriteAt: Long = 0L

    fun markClassicWrite() {
        lastClassicWriteAt = System.currentTimeMillis()
    }

    fun classicActive(): Boolean =
        System.currentTimeMillis() - lastClassicWriteAt < 5_000L

    fun stretch(delayMs: Long): Long =
        if (classicActive()) (delayMs * 3L).coerceAtMost(60_000L) else delayMs
}
