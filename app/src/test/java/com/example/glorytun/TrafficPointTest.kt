package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficPointTest {
    @Test
    fun sameHourIntervalsKeepActualBytesAndElapsedTime() {
        val first = TrafficPoint(1_000L, 1f, 2f, 2_000L, 2_048L, 4_096L, 3f, 8_192L)
        val second = TrafficPoint(2_000L, 4f, 1f, 3_000L, 12_288L, 3_072L, 5f, 18_432L)

        val merged = mergeTrafficPoints(first, second)

        assertEquals(5_000L, merged.durationMillis)
        assertEquals(14_336L, merged.measuredWifiBytes)
        assertEquals(7_168L, merged.measuredSimBytes)
        assertEquals(26_624L, merged.measuredTotalBytes)
        assertEquals(2.8f, merged.wifiKBs, 0.001f)
        assertEquals(1.4f, merged.simKBs, 0.001f)
        assertEquals(5f, merged.peakCombinedKBs, 0.001f)
    }

    @Test
    fun oldHistoryWithoutDurationOrByteFieldsKeepsItsPreviousEstimate() {
        val legacy = TrafficPoint(0L, 1f, 0.5f)
        assertEquals(3_686_400L, legacy.measuredWifiBytes)
        assertEquals(1_843_200L, legacy.measuredSimBytes)
        assertEquals(5_529_600L, legacy.measuredTotalBytes)
    }

    @Test
    fun firstSamplePreservesRealBytesEvenWithoutElapsedTime() {
        val sample = createTrafficSample(
            timestamp = 1_000L,
            wifiKBs = 0f,
            simKBs = 0f,
            totalKBs = 0f,
            durationMillis = 0L,
            wifiBytes = 0L,
            simBytes = 0L,
            totalBytes = 1_234L,
        )
        assertNotNull(sample)
        assertEquals(1_234L, sample!!.measuredTotalBytes)
        assertEquals(0L, sample.durationMillis)
        val wifiOnly = createTrafficSample(1_000L, 0f, 0f, 0f, 0L, 128L, 0L, 0L)
        val simOnly = createTrafficSample(1_000L, 0f, 0f, 0f, 0L, 0L, 256L, 0L)
        assertEquals(128L, wifiOnly!!.measuredWifiBytes)
        assertEquals(256L, simOnly!!.measuredSimBytes)
        assertNull(createTrafficSample(1_001L, 0f, 0f, 0f, 0L, 0L, 0L, 0L))
    }

    @Test
    fun mqvpnFormattingUsesDecimalUnitsAndNiceAxisSteps() {
        assertEquals(10_000L, ceilNiceBandwidth(0L))
        assertEquals(20_000L, ceilNiceBandwidth(12_500L))
        assertEquals(50_000L, ceilNiceBandwidth(20_001L))
        assertEquals("12.5 Kbps", formatBandwidthBps(12_500L))
        assertEquals("1.5 MB", formatBandwidthBytes(1_500_000L))
    }

    @Test
    fun usageAxisExtendsBeyondOldTerabyteLimitWithoutOverflow() {
        val largeValue = 100_000f * 1_073_741_824f
        val axis = usageBarAxis(largeValue)
        assertTrue(axis.maximum >= largeValue)
        assertTrue(axis.ticks.isNotEmpty())
        assertTrue(axis.ticks.size <= 6)

        val extreme = usageBarAxis(Float.POSITIVE_INFINITY)
        assertTrue(extreme.maximum.isFinite())
        assertTrue(extreme.ticks.all { it.isFinite() })
        assertEquals(Float.MAX_VALUE, extreme.maximum, 0f)
    }
}
