package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTrafficAccumulatorTest {
    @Test
    fun onlyValidatedPathsSetTheirOwnAvailabilityFlags() {
        val update = PathTrafficAccumulator().update(
            listOf(
                path(1, PathInfo.STATUS_ACTIVE, "wifi-1"),
                path(2, PathInfo.STATUS_PENDING, "bluetooth-2"),
                path(3, PathInfo.STATUS_STANDBY, "ethernet-3"),
                path(4, PathInfo.STATUS_CLOSED, "cellular-4"),
            ),
        )

        assertTrue(update.totals.wifiPathActive)
        assertFalse(update.totals.bluetoothActive)
        assertTrue(update.totals.ethernetActive)
        assertFalse(update.totals.simActive)
    }

    @Test
    fun bluetoothAndEthernetAreNotCountedAsWifiOrSimTraffic() {
        val accumulator = PathTrafficAccumulator()
        val paths = listOf(
            path(1, PathInfo.STATUS_ACTIVE, "wifi-1", bytesTx = 10),
            path(2, PathInfo.STATUS_ACTIVE, "cellular-2", bytesTx = 10),
            path(3, PathInfo.STATUS_ACTIVE, "bluetooth-3", bytesTx = 10),
            path(4, PathInfo.STATUS_ACTIVE, "ethernet-4", bytesTx = 10),
        )
        accumulator.update(paths)

        val update = accumulator.update(paths.map { it.copy(bytesTx = it.bytesTx + 100) })

        assertTrue(update.totals.bluetoothActive)
        assertTrue(update.totals.ethernetActive)
        assertEquals(100L, update.totals.wifiTx)
        assertEquals(100L, update.totals.simTx)
    }

    @Test
    fun nonWifiPathsDoNotMakeWifiActive() {
        val update = PathTrafficAccumulator().update(
            listOf(path(3, PathInfo.STATUS_ACTIVE, "bluetooth-3")),
        )

        assertTrue(update.totals.bluetoothActive)
        assertFalse(update.totals.wifiActive)
        assertFalse(update.totals.wifiPathActive)
        assertFalse(update.totals.simActive)
    }

    @Test
    fun closedPathIsHiddenAfterItsFinalCountersAreAccounted() {
        val accumulator = PathTrafficAccumulator()
        accumulator.update(listOf(path(5, PathInfo.STATUS_ACTIVE, "wifi-5", bytesTx = 100)))

        val update = accumulator.update(
            listOf(path(5, PathInfo.STATUS_CLOSED, "wifi-5", bytesTx = 140)),
        )

        assertFalse(update.totals.wifiPathActive)
        assertEquals(40L, update.totals.wifiTx)
    }

    private fun path(
        handle: Long,
        status: Int,
        iface: String,
        bytesTx: Long = 0,
        bytesRx: Long = 0,
    ) = PathInfo(handle, status, iface, bytesTx, bytesRx, srttMs = 0)
}
