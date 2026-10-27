package com.example.glorytun

import com.mqvpn.sdk.core.model.VpnStats
import org.junit.Assert.assertEquals
import org.junit.Test

class MqvpnTrafficDeltaTest {
    @Test
    fun tracksNativeTotalsAndCountsAfterCounterReset() {
        val tracker = MqvpnTrafficDelta()
        assertEquals(300L, tracker.consume(VpnStats(bytesTx = 100, bytesRx = 200)))
        assertEquals(70L, tracker.consume(VpnStats(bytesTx = 120, bytesRx = 250)))
        assertEquals(10L, tracker.consume(VpnStats(bytesTx = 4, bytesRx = 6)))
        tracker.reset()
        assertEquals(3L, tracker.consume(VpnStats(bytesTx = 1, bytesRx = 2)))
    }
}
