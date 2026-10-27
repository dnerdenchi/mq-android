package com.example.glorytun

import java.util.UUID

/** Accumulates exact byte deltas and saves short intervals within each hour. */
internal class TrafficHourRecorder(private val save: (TrafficPoint, Long, Long, String) -> Unit) {
    val sampleScopeId: String = UUID.randomUUID().toString()
    private var hour: Long? = null
    private var firstSampleMillis: Long? = null
    private var lastSampleMillis: Long? = null
    private var wifiBytes = 0L
    private var simBytes = 0L
    private var totalBytes = 0L
    private var durationMillis = 0L
    private var peakKBs = 0f

    @Synchronized
    fun record(
        nowMillis: Long,
        wifiBytes: Long,
        simBytes: Long,
        totalBytes: Long,
        durationMillis: Long,
        totalKBs: Float,
    ) {
        val newHour = nowMillis / GlorytunConstants.MILLIS_IN_HOUR
        if (hour != null && hour != newHour) flush()
        hour = newHour
        firstSampleMillis = firstSampleMillis?.let { minOf(it, nowMillis) } ?: nowMillis
        lastSampleMillis = lastSampleMillis?.let { maxOf(it, nowMillis) } ?: nowMillis
        this.wifiBytes += wifiBytes.coerceAtLeast(0L)
        this.simBytes += simBytes.coerceAtLeast(0L)
        this.totalBytes += totalBytes.coerceAtLeast(0L)
        this.durationMillis += durationMillis.coerceAtLeast(0L)
        peakKBs = maxOf(peakKBs, totalKBs.coerceAtLeast(0f))
        if (this.durationMillis >= SAVE_INTERVAL_MILLIS) flush()
    }

    @Synchronized
    fun flush() {
        val recordedHour = hour ?: return
        if (durationMillis > 0L || wifiBytes > 0L || simBytes > 0L || totalBytes > 0L) {
            val seconds = durationMillis / 1_000.0
            save(
                TrafficPoint(
                    timestamp = recordedHour * GlorytunConstants.MILLIS_IN_HOUR,
                    wifiKBs = if (seconds > 0) (wifiBytes / 1_024.0 / seconds).toFloat() else 0f,
                    simKBs = if (seconds > 0) (simBytes / 1_024.0 / seconds).toFloat() else 0f,
                    durationMillis = durationMillis,
                    wifiBytes = wifiBytes,
                    simBytes = simBytes,
                    totalBytes = totalBytes,
                    peakCombinedKBs = peakKBs,
                ),
                firstSampleMillis ?: return,
                lastSampleMillis ?: return,
                sampleScopeId,
            )
        }
        hour = null
        firstSampleMillis = null
        lastSampleMillis = null
        wifiBytes = 0L
        simBytes = 0L
        totalBytes = 0L
        durationMillis = 0L
        peakKBs = 0f
    }

    private companion object {
        const val SAVE_INTERVAL_MILLIS = 60_000L
    }
}
