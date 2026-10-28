package com.example.glorytun

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Calendar

class BandwidthUsageStoreTest {
    @Test
    fun legacyDailyUsageIsMigratedAndTheExplicitTotalIsPersisted() {
        val memory = MemoryPreferences().apply {
            values.putAll(
                mapOf(
                    "period_day" to 20261027L,
                    "period_month" to 202610L,
                    "daily_wifi_bytes" to 100L,
                    "daily_sim_bytes" to 20L,
                    "monthly_wifi_bytes" to 500L,
                    "monthly_sim_bytes" to 80L,
                ),
            )
        }
        val time = atNoon(2026, Calendar.OCTOBER, 27)
        val store = BandwidthUsageStore(memory.prefs) { time }

        assertEquals(120L, store.snapshot().dailyTotalBytes)
        val updated = store.add(wifiBytes = 7L, simBytes = 8L, totalBytes = 50L)
        assertEquals(170L, updated.dailyTotalBytes)
        assertEquals(107L, updated.dailyWifiBytes)
        assertEquals(28L, updated.dailySimBytes)
        assertEquals(updated, BandwidthUsageStore(memory.prefs) { time }.snapshot())
    }

    @Test
    fun dailyAndMonthlyValuesResetOnPeriodChange() {
        val memory = MemoryPreferences()
        var time = atNoon(2026, Calendar.OCTOBER, 31)
        val store = BandwidthUsageStore(memory.prefs) { time }
        store.add(wifiBytes = 10L, simBytes = 20L, totalBytes = 100L)

        time = atNoon(2026, Calendar.NOVEMBER, 1)
        assertEquals(BandwidthUsage(), store.snapshot())
        val updated = store.add(wifiBytes = 3L, simBytes = 4L, totalBytes = 8L)
        assertEquals(8L, updated.dailyTotalBytes)
        assertEquals(3L, updated.monthlyWifiBytes)
        assertEquals(4L, updated.monthlySimBytes)
    }

    @Test
    fun separateStoresPreserveEachOthersUpdatesAndDefaultToWifiPlusSim() {
        val memory = MemoryPreferences()
        val time = atNoon(2026, Calendar.OCTOBER, 27)
        val first = BandwidthUsageStore(memory.prefs) { time }
        val second = BandwidthUsageStore(memory.prefs) { time }

        first.add(wifiBytes = 10L, simBytes = 0L, totalBytes = 30L)
        val updated = second.add(wifiBytes = 0L, simBytes = 20L)

        assertEquals(10L, updated.dailyWifiBytes)
        assertEquals(20L, updated.dailySimBytes)
        assertEquals(50L, updated.dailyTotalBytes)
        assertEquals(updated, first.snapshot())
    }

    private fun atNoon(year: Int, month: Int, day: Int): Long = Calendar.getInstance().run {
        clear()
        set(year, month, day, 12, 0, 0)
        timeInMillis
    }

    /** Minimal in-memory SharedPreferences implementation for the store's Long keys. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Long>()

        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLong" -> values[args!![0] as String] ?: args[1] as Long
                "edit" -> editor()
                else -> error("Unexpected preference method: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Long>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putLong" -> {
                        pending[args!![0] as String] = args[1] as Long
                        proxy
                    }
                    "apply" -> {
                        values.putAll(pending)
                        null
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
