package com.example.glorytun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class PairShareNetworkTest {
    @Test
    fun physicalWifiWithLanAddressIsAccepted() {
        assertTrue(
            PairShareNetwork.isLanWifiCandidate(
                hasWifiTransport = true,
                isNotVpn = true,
                hasLocalIpv4 = true,
            ),
        )
    }

    @Test
    fun vpnThatInheritsWifiTransportIsRejected() {
        assertFalse(
            PairShareNetwork.isLanWifiCandidate(
                hasWifiTransport = true,
                isNotVpn = false,
                hasLocalIpv4 = true,
            ),
        )
    }

    @Test
    fun losingTheBoundNetworkDisconnectsOnlyThatPath() {
        val state = PairShareNetwork.LeaseNetworkState<String, TestSocket>("bluetooth-1")
        val socket = TestSocket()
        assertTrue(state.accept("bluetooth-1", socket))
        assertNull(state.onLost("unrelated-network"))
        assertTrue(state.isBoundAvailable())
        assertSame(socket, state.onLost("bluetooth-1"))
        assertFalse(state.isBoundAvailable())
        assertNull(state.current())
    }

    @Test
    fun networkSwitchAndCloseRejectSocketsBoundToAnOldNetwork() {
        val state = PairShareNetwork.LeaseNetworkState<String, TestSocket>("ethernet-1")
        assertNull(state.onAvailable("ethernet-2"))
        assertFalse(state.accept("ethernet-1", TestSocket()))
        val old = TestSocket()
        assertTrue(state.accept("ethernet-2", old))
        val invalidated = state.onAvailable("ethernet-3")
        assertSame(old, invalidated)
        assertFalse(state.isBoundAvailable())
        val replacement = TestSocket()
        assertTrue(state.accept("ethernet-3", replacement))
        // The old callback may close its detached socket after the replacement is accepted.
        invalidated?.close()
        assertTrue(old.closed)
        assertFalse(replacement.closed)
        assertTrue(state.isBoundAvailable())
        assertSame(replacement, state.close())
        assertTrue(state.isClosed())
        assertFalse(state.accept("ethernet-3", TestSocket()))
        assertNull(state.onAvailable("ethernet-4"))
    }

    private class TestSocket : Closeable {
        var closed = false
        override fun close() { closed = true }
    }
}
