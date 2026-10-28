package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficHistoryReconcilerTest {
    private fun point(timestamp: Long, bytes: Long, scope: String? = null) = TrafficPoint(
        timestamp = timestamp,
        wifiKBs = 0f,
        simKBs = 0f,
        durationMillis = 1_000L,
        wifiBytes = bytes,
        simBytes = 0L,
        totalBytes = bytes,
        sampleScopeId = scope,
    )

    @Test
    fun committedRangeRemovesOnlyItsOwnAlreadyReceivedSamples() {
        val saved = mutableListOf<TrafficPoint>()
        val live = mutableListOf<TrafficPoint>()
        val history = TrafficHistoryReconciler(saved, live)
        history.addLive(point(1_000L, 10L, "vpn"))
        history.addLive(point(2_000L, 20L, "vpn"))
        history.addLive(point(3_000L, 30L, "vpn"))
        history.addLive(point(1_000L, 40L, "proxy"))

        history.acceptCommitted(point(0L, 30L), "vpn", 1_000L, 2_000L)

        assertEquals(listOf(30L, 40L), live.map { it.measuredTotalBytes })
        assertEquals(30L, saved.single().measuredTotalBytes)
        assertFalse(history.addLive(point(2_000L, 20L, "vpn")))
        assertTrue(history.addLive(point(2_000L, 50L, "proxy")))
    }

    @Test
    fun noticeBeforeSampleSuppressesOnlySamplesInsideSavedRange() {
        val saved = mutableListOf<TrafficPoint>()
        val live = mutableListOf<TrafficPoint>()
        val history = TrafficHistoryReconciler(saved, live)
        history.acceptCommitted(point(0L, 10L), "vpn", 1_000L, 1_000L)

        assertFalse(history.addLive(point(1_000L, 10L, "vpn")))
        assertTrue(history.addLive(point(2_000L, 20L, "vpn")))
        assertEquals(30L, saved.sumOf { it.measuredTotalBytes } + live.sumOf { it.measuredTotalBytes })
    }

    @Test
    fun staleAndDuplicateHourSnapshotsDoNotOverwriteNewerLoadedTotals() {
        val saved = mutableListOf(point(0L, 40L))
        val live = mutableListOf<TrafficPoint>()
        val history = TrafficHistoryReconciler(saved, live)

        history.acceptCommitted(point(0L, 20L), "vpn", 1_000L, 2_000L)
        history.acceptCommitted(point(0L, 40L), "vpn", 3_000L, 4_000L)
        history.acceptCommitted(point(0L, 20L), "vpn", 1_000L, 2_000L)

        assertEquals(1, saved.size)
        assertEquals(40L, saved.single().measuredTotalBytes)
        assertFalse(history.addLive(point(2_000L, 20L, "vpn")))
        assertFalse(history.addLive(point(4_000L, 20L, "vpn")))
        assertTrue(history.addLive(point(2_500L, 5L, "vpn")))
    }
}
