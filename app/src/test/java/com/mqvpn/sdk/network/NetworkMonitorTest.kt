package com.mqvpn.sdk.network

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun classifiesWifiCellularEthernetAndBluetooth() {
        assertEquals(
            PathType.WIFI,
            NetworkMonitor.classifyTransport(true, false, false, false),
        )
        assertEquals(
            PathType.CELLULAR,
            NetworkMonitor.classifyTransport(false, true, false, false),
        )
        assertEquals(
            PathType.ETHERNET,
            NetworkMonitor.classifyTransport(false, false, true, false),
        )
        assertEquals(
            PathType.BLUETOOTH,
            NetworkMonitor.classifyTransport(false, false, false, true),
        )
        assertEquals(
            PathType.OTHER,
            NetworkMonitor.classifyTransport(false, false, false, false),
        )
    }

    @Test
    fun wifiAndCellularRemainHeldAndBluetoothCanBeHeldAlongsideEthernet() {
        assertEquals(
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET,
            ),
            NetworkMonitor.holdTransports(holdBluetooth = false),
        )
        assertEquals(
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_BLUETOOTH,
            ),
            NetworkMonitor.holdTransports(holdBluetooth = true),
        )
    }
}
