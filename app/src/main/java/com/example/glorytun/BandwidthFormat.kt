// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.example.glorytun

import java.util.Locale

/** mqvpn のグラフと同じ 1/2/5 ステップの速度軸。 */
internal fun ceilNiceBandwidth(bps: Long): Long {
    var scale = 10_000L
    while (true) {
        for (multiplier in longArrayOf(1L, 2L, 5L)) {
            if (scale > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE
            val value = scale * multiplier
            if (bps <= value) return value
        }
        if (scale > Long.MAX_VALUE / 10L) return Long.MAX_VALUE
        scale *= 10L
    }
}

internal data class UsageBarAxis(val maximum: Float, val ticks: List<Float>)

/** 通信量に合わせて 0.5/1/2/5 の目盛を伸ばす。 */
internal fun usageBarAxis(value: Float): UsageBarAxis {
    val limit = Float.MAX_VALUE.toDouble()
    val safeValue = when {
        value.isNaN() || value <= 0f -> 0.0
        value.isInfinite() -> limit
        else -> value.toDouble()
    }
    val target = safeValue / 6.0
    var scale = 1_073_741_824.0
    var step = scale / 2.0
    while (step < target) {
        val matching = doubleArrayOf(1.0, 2.0, 5.0)
            .map { scale * it }
            .firstOrNull { it >= target }
        if (matching != null) {
            step = matching
            break
        }
        scale *= 10.0
    }
    val count = kotlin.math.ceil(safeValue / step).toInt().coerceIn(1, 6)
    val maximum = minOf(step * count, limit).toFloat()
    val ticks = (1..count).map { minOf(step * it, maximum.toDouble()).toFloat() }.distinct()
    return UsageBarAxis(maximum, ticks)
}

/** mqvpn の速度表示と同じ SI 単位。 */
internal fun formatBandwidthBps(bps: Long): String {
    if (bps <= 0L) return "0"
    if (bps < 1_000L) return "$bps bps"
    val (divisor, unit) = when {
        bps < 1_000_000L -> 1_000.0 to "Kbps"
        bps < 1_000_000_000L -> 1_000_000.0 to "Mbps"
        else -> 1_000_000_000.0 to "Gbps"
    }
    return "${String.format(Locale.US, "%.1f", bps / divisor).removeSuffix(".0")} $unit"
}

/** mqvpn の累積通信量表示と同じ SI 単位。 */
internal fun formatBandwidthBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "${bytes.coerceAtLeast(0L)} B"
}
