package com.example.glorytun

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairBondConnectionSlotTest {
    @Test
    fun retirementClosesPendingHandshakeAndRejectsLateAdoption() {
        val slot = PairBondConnectionSlot<TestConnection>()
        val pending = TestConnection()
        assertTrue(slot.begin(pending) { true })
        val retired = slot.retire()
        assertSame(pending, retired.single())
        retired.forEach { it.close() }
        assertFalse(slot.adopt(pending) { true })
        assertTrue(pending.closed)
    }

    @Test
    fun retirementRacingAdoptionOwnsTheNewlyAdoptedConnection() {
        val slot = PairBondConnectionSlot<TestConnection>()
        val candidate = TestConnection()
        assertTrue(slot.begin(candidate) { true })
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val retired = AtomicReference<List<TestConnection>>()
        val adopter = Thread {
            slot.adopt(candidate) {
                entered.countDown()
                check(proceed.await(2, TimeUnit.SECONDS))
                true
            }
        }
        adopter.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { retired.set(slot.retire()) }
        closer.start()
        proceed.countDown()
        adopter.join(2_000)
        closer.join(2_000)
        assertFalse(adopter.isAlive)
        assertFalse(closer.isAlive)
        assertSame(candidate, retired.get().single())
        retired.get().forEach { it.close() }
        assertTrue(candidate.closed)
    }

    private class TestConnection : Closeable {
        var closed = false
        override fun close() { closed = true }
    }
}
