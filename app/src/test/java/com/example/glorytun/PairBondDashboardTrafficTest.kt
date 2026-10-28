package com.example.glorytun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairBondDashboardTrafficTest {
    @Test
    fun separatesLocalWifiLocalSimAndReceivedPeerTraffic() {
        val accumulator = PairBondDashboardTrafficAccumulator()
        val names = mapOf("oppo" to "OPPO CPH2353")

        accumulator.update(
            stats = listOf(
                stats(PairBondLocalPath.WIFI_ID, 1_000L, 2_000L),
                stats(PairBondLocalPath.CELLULAR_ID, 3_000L, 4_000L),
                stats("oppo", 5_000L, 6_000L),
            ),
            peerNames = names,
            nowMillis = 1_000L,
        )

        val update = accumulator.update(
            stats = listOf(
                stats(PairBondLocalPath.WIFI_ID, 11_000L, 22_000L),
                stats(PairBondLocalPath.CELLULAR_ID, 33_000L, 44_000L),
                stats("oppo", 55_000L, 66_000L),
            ),
            peerNames = names,
            nowMillis = 2_000L,
        )

        assertEquals(10_000L, update.totals.wifiTx)
        assertEquals(20_000L, update.totals.wifiRx)
        assertEquals(30_000L, update.totals.simTx)
        assertEquals(40_000L, update.totals.simRx)
        assertEquals(30_000L * 8L, update.wifiBps)
        assertEquals(70_000L * 8L, update.simBps)
        assertEquals(110_000L * 8L, update.pairShareBps)
        assertEquals(listOf("OPPO CPH2353"), update.receivingPeerNames)
        assertTrue(update.totals.wifiActive)
        assertTrue(update.totals.simActive)
    }

    @Test
    fun counterResetDoesNotCreateANegativeOrHugeRate() {
        val accumulator = PairBondDashboardTrafficAccumulator()
        accumulator.update(
            listOf(stats(PairBondLocalPath.WIFI_ID, 10_000L, 20_000L)),
            emptyMap(),
            1_000L,
        )

        val update = accumulator.update(
            listOf(stats(PairBondLocalPath.WIFI_ID, 100L, 200L)),
            emptyMap(),
            2_000L,
        )

        assertEquals(0L, update.wifiBps)
        assertEquals(0L, update.totals.wifiTotal)
    }

    @Test
    fun offlinePathsAreHiddenAndNewLocalTransportsAreMeasuredSeparately() {
        val accumulator = PairBondDashboardTrafficAccumulator()
        val names = mapOf("peer" to "ペア端末")
        val offline = listOf(
            stats(PairBondLocalPath.WIFI_ID, 0L, 0L, ready = false),
            stats(PairBondLocalPath.CELLULAR_ID, 0L, 0L, ready = false),
            stats(PairBondLocalPath.BLUETOOTH_ID, 0L, 0L, ready = false),
            stats(PairBondLocalPath.ETHERNET_ID, 0L, 0L, ready = false),
            stats("peer", 0L, 0L, ready = false),
        )
        val initial = accumulator.update(offline, names, 1_000L)
        assertFalse(initial.totals.wifiPathActive)
        assertFalse(initial.totals.simActive)
        assertFalse(initial.totals.bluetoothActive)
        assertFalse(initial.totals.ethernetActive)
        assertTrue(initial.receivingPeerNames.isEmpty())

        val connected = listOf(
            stats(PairBondLocalPath.WIFI_ID, 1_000L, 2_000L),
            stats(PairBondLocalPath.CELLULAR_ID, 3_000L, 4_000L),
            stats(PairBondLocalPath.BLUETOOTH_ID, 5_000L, 6_000L),
            stats(PairBondLocalPath.ETHERNET_ID, 7_000L, 8_000L),
            stats("peer", 9_000L, 10_000L),
        )
        val measured = accumulator.update(connected, names, 2_000L)
        assertTrue(measured.totals.wifiPathActive)
        assertTrue(measured.totals.simActive)
        assertTrue(measured.totals.bluetoothActive)
        assertTrue(measured.totals.ethernetActive)
        assertEquals(3_000L * 8L, measured.wifiBps)
        assertEquals(7_000L * 8L, measured.simBps)
        assertEquals(11_000L * 8L, measured.bluetoothBps)
        assertEquals(15_000L * 8L, measured.ethernetBps)
        assertEquals(19_000L * 8L, measured.pairShareBps)
        assertEquals(listOf("ペア端末"), measured.receivingPeerNames)

        val disconnected = accumulator.update(connected.map { it.copy(pathReady = false) }, names, 3_000L)
        assertFalse(disconnected.totals.wifiPathActive)
        assertFalse(disconnected.totals.simActive)
        assertFalse(disconnected.totals.bluetoothActive)
        assertFalse(disconnected.totals.ethernetActive)
        assertTrue(disconnected.receivingPeerNames.isEmpty())
    }

    private fun stats(id: String, tx: Long, rx: Long, ready: Boolean = true) = PairSharePeerStats(
        peerId = id,
        txBytes = tx,
        rxBytes = rx,
        pathReady = ready,
    )
}
