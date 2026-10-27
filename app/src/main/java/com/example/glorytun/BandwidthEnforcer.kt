package com.example.glorytun

import android.content.Context
import com.mqvpn.sdk.core.model.PathInfo
import kotlin.math.roundToLong

data class BandwidthStatus(
    val usage: BandwidthUsage,
    val wifiLimited: Boolean,
    val simLimited: Boolean,
)

class BandwidthEnforcer(
    context: Context,
    private val applyPathRates: (Map<Long, Long>) -> Unit,
) {
    private val settingsPrefs = context.getSharedPreferences(
        GlorytunConstants.PREFS_BANDWIDTH,
        Context.MODE_PRIVATE,
    )
    private val usageStore = BandwidthUsageStore(context)
    private var lastRates: Map<Long, Long>? = null

    fun onTick(
        paths: List<PathInfo>,
        wifiDeltaKb: Float,
        simDeltaKb: Float,
        totalDeltaBytes: Long? = null,
    ): BandwidthStatus {
        val wifiBytes = (wifiDeltaKb * 1_024.0).roundToLong().coerceAtLeast(0L)
        val simBytes = (simDeltaKb * 1_024.0).roundToLong().coerceAtLeast(0L)
        val usage = usageStore.add(
            wifiBytes = wifiBytes,
            simBytes = simBytes,
            totalBytes = totalDeltaBytes ?: (wifiBytes + simBytes),
        )
        val decision = BandwidthPolicy.evaluate(
            BandwidthSettings.from(settingsPrefs),
            usage,
        )
        val rates = pathBandwidthLimits(paths, decision)
        if (rates != lastRates) {
            applyPathRates(rates)
            lastRates = rates
        }
        return BandwidthStatus(
            usage = usage,
            wifiLimited = decision.wifi.limited,
            simLimited = decision.sim.limited,
        )
    }

    fun stop() {
        usageStore.flush()
        if (lastRates?.values?.any { it > 0L } == true) {
            applyPathRates(lastRates.orEmpty().mapValues { 0L })
        }
        lastRates = null
    }
}

internal fun pathBandwidthLimits(paths: List<PathInfo>, decision: BandwidthDecision): Map<Long, Long> =
    paths.asSequence()
        .filter { it.status != PathInfo.STATUS_CLOSED }
        .associate { path ->
            path.handle to when {
                isWifiInterface(path.iface) -> decision.wifi.rateBytesPerSecond
                isCellularInterface(path.iface) -> decision.sim.rateBytesPerSecond
                else -> 0L
            }
        }
