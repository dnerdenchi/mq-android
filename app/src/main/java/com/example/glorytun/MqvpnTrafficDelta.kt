package com.example.glorytun

import com.mqvpn.sdk.core.model.VpnStats

/** Tracks the native mqvpn totals, including a counter reset after reconnect. */
internal class MqvpnTrafficDelta {
    private var previousTx = 0L
    private var previousRx = 0L

    fun consume(stats: VpnStats): Long {
        val tx = stats.bytesTx.coerceAtLeast(0L)
        val rx = stats.bytesRx.coerceAtLeast(0L)
        val deltaTx = if (tx >= previousTx) tx - previousTx else tx
        val deltaRx = if (rx >= previousRx) rx - previousRx else rx
        previousTx = tx
        previousRx = rx
        return deltaTx + deltaRx
    }

    fun reset() {
        previousTx = 0L
        previousRx = 0L
    }
}
