package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class BandwidthLimitRoutingTest {
    @Test
    fun wifiAndSimLimitsDoNotApplyToOtherTransports() {
        val decision = BandwidthDecision(
            wifi = NetworkBandwidthDecision(rateBytesPerSecond = 1_000L, limited = true),
            sim = NetworkBandwidthDecision(rateBytesPerSecond = 2_000L, limited = true),
        )
        val paths = listOf(
            path(1L, "wifi-1"),
            path(2L, "cellular-2"),
            path(3L, "bluetooth-3"),
            path(4L, "ethernet-4"),
            path(5L, "other-5"),
            path(6L, "wifi-closed", PathInfo.STATUS_CLOSED),
        )

        assertEquals(
            mapOf(1L to 1_000L, 2L to 2_000L, 3L to 0L, 4L to 0L, 5L to 0L),
            pathBandwidthLimits(paths, decision),
        )
    }

    private fun path(handle: Long, iface: String, status: Int = PathInfo.STATUS_ACTIVE) = PathInfo(
        handle = handle,
        status = status,
        iface = iface,
        bytesTx = 0L,
        bytesRx = 0L,
        srttMs = 0L,
    )
}
