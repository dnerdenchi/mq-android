package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Test

class PairBondTrafficCountersTest {
    @Test
    fun controlFramesDoNotAppearAsUserTraffic() {
        val counters = PairBondTrafficCounters()
        counters.snapshotAt(1_000_000_000L)
        val control = PairBondFrame(PairBondFrameType.PATH_QUALITY, 0, 0L, ByteArray(17))
        counters.sent(control)
        counters.received(control)
        val idle = counters.snapshotAt(2_000_000_000L)
        assertEquals(17L, counters.sentBytesForQuality())
        assertEquals(0L, idle.txBytes)
        assertEquals(0L, idle.rxBytes)
        assertEquals(0L, idle.txBytesPerSecond)
        assertEquals(0L, idle.rxBytesPerSecond)

        val data = PairBondFrame(PairBondFrameType.TCP_DATA, 1, 0L, ByteArray(1_000))
        counters.sent(data)
        counters.received(data)
        val active = counters.snapshotAt(3_000_000_000L)
        assertEquals(1_000L, active.txBytes)
        assertEquals(1_000L, active.rxBytes)
        assertEquals(1_000L, active.txBytesPerSecond)
        assertEquals(1_000L, active.rxBytesPerSecond)
    }

    @Test
    fun rapidOrOutOfOrderSnapshotsDoNotProduceRateSpikes() {
        val counters = PairBondTrafficCounters()
        counters.snapshotAt(1_000_000_000L)
        val data = PairBondFrame(PairBondFrameType.TCP_DATA, 1, 0L, ByteArray(100))
        counters.sent(data)
        assertEquals(0L, counters.snapshotAt(1_001_000_000L).txBytesPerSecond)
        assertEquals(0L, counters.snapshotAt(900_000_000L).txBytesPerSecond)
        assertEquals(100L, counters.snapshotAt(2_000_000_000L).txBytesPerSecond)
        assertEquals(0L, counters.snapshotAt(3_000_000_000L).txBytesPerSecond)
    }
}
