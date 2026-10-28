package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrafficHourRecorderTest {
    @Test
    fun storesExactBytesAndDurationAcrossReconnectsAndHourBoundaries() {
        val saved = mutableListOf<TrafficPoint>()
        val ranges = mutableListOf<Pair<Long, Long>>()
        val recorder = TrafficHourRecorder { point, from, through, _ ->
            saved.add(point)
            ranges.add(from to through)
        }
        val hour = GlorytunConstants.MILLIS_IN_HOUR
        recorder.record(hour + 1_000, 100, 50, 200, 1_000, 1f)
        recorder.record(hour + 2_000, 20, 30, 75, 2_000, 2f)
        recorder.record(2 * hour + 1_000, 4, 5, 10, 1_000, 3f)
        recorder.flush()
        assertEquals(2, saved.size)
        assertEquals(120L, saved[0].measuredWifiBytes)
        assertEquals(80L, saved[0].measuredSimBytes)
        assertEquals(275L, saved[0].measuredTotalBytes)
        assertEquals(3_000L, saved[0].durationMillis)
        assertEquals(2f, saved[0].peakCombinedKBs, 0f)
        assertEquals(10L, saved[1].measuredTotalBytes)
        assertEquals((hour + 1_000) to (hour + 2_000), ranges[0])
        assertEquals((2 * hour + 1_000) to (2 * hour + 1_000), ranges[1])
    }

    @Test
    fun preservesFirstNativeCounterEvenWithoutAnElapsedInterval() {
        val saved = mutableListOf<TrafficPoint>()
        val recorder = TrafficHourRecorder { point, _, _, _ -> saved.add(point) }
        recorder.record(1_000, 0, 0, 123, 0, 0f)
        recorder.flush()
        assertEquals(123L, saved.single().measuredTotalBytes)
    }

    @Test
    fun periodicallyPersistsLongSessionsWithoutDoubleCounting() {
        val saved = mutableListOf<TrafficPoint>()
        val recorder = TrafficHourRecorder { point, _, _, _ -> saved.add(point) }
        recorder.record(3_700_000, 10, 0, 10, 60_000, 1f)
        recorder.record(3_701_000, 20, 0, 20, 1_000, 1f)
        recorder.flush()
        assertEquals(2, saved.size)
        assertEquals(30L, saved.sumOf { it.measuredTotalBytes })
    }

    @Test
    fun concurrentRecordAndFlushKeepEveryCounterAndValidRange() {
        val saved = Collections.synchronizedList(mutableListOf<TrafficPoint>())
        val ranges = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val recorder = TrafficHourRecorder { point, from, through, _ ->
            saved.add(point)
            ranges.add(from to through)
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val writers = List(4) {
                pool.submit {
                    start.await()
                    repeat(1_000) { index ->
                        recorder.record(3_700_000L + index, 1L, 0L, 1L, 1L, 1f)
                    }
                }
            }
            val flusher = pool.submit {
                start.await()
                repeat(100) { recorder.flush() }
            }
            start.countDown()
            (writers + flusher).forEach { it.get(10, TimeUnit.SECONDS) }
            recorder.flush()
            assertEquals(4_000L, saved.sumOf { it.measuredTotalBytes })
            assertEquals(4_000L, saved.sumOf { it.durationMillis })
            assertTrue(ranges.all { (from, through) -> from <= through })
        } finally {
            pool.shutdownNow()
        }
    }
}
