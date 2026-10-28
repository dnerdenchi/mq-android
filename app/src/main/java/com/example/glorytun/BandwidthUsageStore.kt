package com.example.glorytun

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

class BandwidthUsageStore internal constructor(
    private val prefs: SharedPreferences,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, nowMillis: () -> Long = System::currentTimeMillis) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        nowMillis,
    )

    fun add(
        wifiBytes: Long,
        simBytes: Long,
        totalBytes: Long = wifiBytes.coerceAtLeast(0L) + simBytes.coerceAtLeast(0L),
    ): BandwidthUsage = synchronized(storeLock) {
        val period = periodKeys(nowMillis())
        val current = loadCurrentUsage(period)
        val safeWifi = wifiBytes.coerceAtLeast(0L)
        val safeSim = simBytes.coerceAtLeast(0L)
        val next = current.copy(
            dailyWifiBytes = current.dailyWifiBytes + safeWifi,
            dailySimBytes = current.dailySimBytes + safeSim,
            monthlyWifiBytes = current.monthlyWifiBytes + safeWifi,
            monthlySimBytes = current.monthlySimBytes + safeSim,
            dailyTotalBytes = current.dailyTotalBytes + totalBytes.coerceAtLeast(0L),
        )
        persist(next, period)
        next
    }

    fun snapshot(): BandwidthUsage = synchronized(storeLock) {
        loadCurrentUsage(periodKeys(nowMillis()))
    }

    // Each addition is persisted immediately, as it was with SAVE_EVERY_TICKS = 1.
    fun flush() = Unit

    private fun loadCurrentUsage(now: PeriodKeys): BandwidthUsage {
        val storedDay = prefs.getLong(KEY_DAY, Long.MIN_VALUE)
        val storedMonth = prefs.getLong(KEY_MONTH, Long.MIN_VALUE)
        val wifi = if (storedDay == now.day) prefs.getLong(KEY_DAILY_WIFI, 0L) else 0L
        val sim = if (storedDay == now.day) prefs.getLong(KEY_DAILY_SIM, 0L) else 0L
        return BandwidthUsage(
            dailyWifiBytes = wifi,
            dailySimBytes = sim,
            monthlyWifiBytes = if (storedMonth == now.month) prefs.getLong(KEY_MONTHLY_WIFI, 0L) else 0L,
            monthlySimBytes = if (storedMonth == now.month) prefs.getLong(KEY_MONTHLY_SIM, 0L) else 0L,
            dailyTotalBytes = if (storedDay == now.day) prefs.getLong(KEY_DAILY_TOTAL, wifi + sim) else 0L,
        )
    }

    private fun persist(usage: BandwidthUsage, now: PeriodKeys) {
        prefs.edit()
            .putLong(KEY_DAY, now.day)
            .putLong(KEY_MONTH, now.month)
            .putLong(KEY_DAILY_WIFI, usage.dailyWifiBytes)
            .putLong(KEY_DAILY_SIM, usage.dailySimBytes)
            .putLong(KEY_DAILY_TOTAL, usage.dailyTotalBytes)
            .putLong(KEY_MONTHLY_WIFI, usage.monthlyWifiBytes)
            .putLong(KEY_MONTHLY_SIM, usage.monthlySimBytes)
            .apply()
    }

    private data class PeriodKeys(val day: Long, val month: Long)

    private fun periodKeys(timeMillis: Long): PeriodKeys {
        val calendar = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val year = calendar.get(Calendar.YEAR).toLong()
        val month = calendar.get(Calendar.MONTH).toLong()
        val day = calendar.get(Calendar.DAY_OF_MONTH).toLong()
        return PeriodKeys(
            day = year * 10_000L + (month + 1L) * 100L + day,
            month = year * 100L + month + 1L,
        )
    }

    companion object {
        private val storeLock = Any()
        private const val PREFS_NAME = "bandwidth_usage"
        private const val KEY_DAY = "period_day"
        private const val KEY_MONTH = "period_month"
        private const val KEY_DAILY_WIFI = "daily_wifi_bytes"
        private const val KEY_DAILY_SIM = "daily_sim_bytes"
        private const val KEY_DAILY_TOTAL = "daily_total_bytes"
        private const val KEY_MONTHLY_WIFI = "monthly_wifi_bytes"
        private const val KEY_MONTHLY_SIM = "monthly_sim_bytes"
    }
}
