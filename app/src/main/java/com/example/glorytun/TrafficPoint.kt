package com.example.glorytun

import kotlin.math.max
import kotlin.math.roundToLong

/** 時間別データには累積バイトと観測時間も保存する。 */
data class TrafficPoint(
    val timestamp: Long,
    val wifiKBs: Float,
    val simKBs: Float,
    val durationMillis: Long = GlorytunConstants.MILLIS_IN_HOUR,
    val wifiBytes: Long? = null,
    val simBytes: Long? = null,
    val peakCombinedKBs: Float = wifiKBs + simKBs,
    val totalBytes: Long? = null,
    val sampleScopeId: String? = null,
) {
    val measuredWifiBytes: Long
        get() = wifiBytes?.coerceAtLeast(0L) ?: estimatedBytes(wifiKBs)
    val measuredSimBytes: Long
        get() = simBytes?.coerceAtLeast(0L) ?: estimatedBytes(simKBs)
    val measuredTotalBytes: Long
        get() = totalBytes?.coerceAtLeast(0L) ?: (measuredWifiBytes + measuredSimBytes)

    private fun estimatedBytes(rateKBs: Float): Long =
        (rateKBs.coerceAtLeast(0f).toDouble() * 1_024.0 * durationMillis.coerceAtLeast(0L) /
            1_000.0).roundToLong()
}

/** 経過時間の初期化中でも、取得済みの実バイト数は失わない。 */
internal fun createTrafficSample(
    timestamp: Long,
    wifiKBs: Float,
    simKBs: Float,
    totalKBs: Float,
    durationMillis: Long,
    wifiBytes: Long?,
    simBytes: Long?,
    totalBytes: Long?,
    sampleScopeId: String? = null,
): TrafficPoint? {
    val duration = durationMillis.coerceAtLeast(0L)
    val wifi = wifiBytes?.coerceAtLeast(0L)
    val sim = simBytes?.coerceAtLeast(0L)
    val total = totalBytes?.coerceAtLeast(0L)
    if (duration == 0L && (wifi ?: 0L) == 0L && (sim ?: 0L) == 0L && (total ?: 0L) == 0L) {
        return null
    }
    return TrafficPoint(
        timestamp = timestamp,
        wifiKBs = wifiKBs,
        simKBs = simKBs,
        durationMillis = duration,
        wifiBytes = wifi,
        simBytes = sim,
        peakCombinedKBs = totalKBs,
        totalBytes = total,
        sampleScopeId = sampleScopeId,
    )
}

/** 同じ時間帯に複数回保存された区間を重複なく合算する。 */
internal fun mergeTrafficPoints(first: TrafficPoint, second: TrafficPoint): TrafficPoint {
    val duration = first.durationMillis.coerceAtLeast(0L) + second.durationMillis.coerceAtLeast(0L)
    val wifiBytes = first.measuredWifiBytes + second.measuredWifiBytes
    val simBytes = first.measuredSimBytes + second.measuredSimBytes
    val totalBytes = first.measuredTotalBytes + second.measuredTotalBytes
    val seconds = duration / 1_000.0
    return TrafficPoint(
        timestamp = minOf(first.timestamp, second.timestamp),
        wifiKBs = if (seconds > 0) (wifiBytes / 1_024.0 / seconds).toFloat() else 0f,
        simKBs = if (seconds > 0) (simBytes / 1_024.0 / seconds).toFloat() else 0f,
        durationMillis = duration,
        wifiBytes = wifiBytes,
        simBytes = simBytes,
        peakCombinedKBs = max(first.peakCombinedKBs, second.peakCombinedKBs),
        totalBytes = totalBytes,
    )
}
