package com.orchestrator.app.notify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescerTest {
    @Test
    fun `the first goes at once, then at most one every two seconds, and it's the newest`() = runTest {
        val sent = mutableListOf<Pair<Long, String>>()
        val c = Coalescer<String>(backgroundScope, 2_000, { testScheduler.currentTime }) { _, v -> sent += testScheduler.currentTime to v }
        c.offer("k", "1")
        runCurrent()
        assertEquals(listOf(0L to "1"), sent)
        advanceTimeBy(500); c.offer("k", "2")
        advanceTimeBy(500); c.offer("k", "3")
        advanceTimeBy(500); c.offer("k", "4")
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(600)
        assertEquals(listOf(0L to "1", 2_000L to "4"), sent)
        // Quiet for a while: the next goes at once again.
        advanceTimeBy(5_000); c.offer("k", "5")
        runCurrent()
        assertEquals(7_100L to "5", sent.last())
    }

    @Test
    fun `keys don't hold each other up, and the same again isn't sent`() = runTest {
        val sent = mutableListOf<String>()
        val c = Coalescer<String>(backgroundScope, 2_000, { testScheduler.currentTime }) { k, v -> sent += "$k=$v" }
        c.offer("a", "1"); c.offer("b", "1"); c.offer("a", "1")
        runCurrent()
        assertEquals(listOf("a=1", "b=1"), sent)
        advanceTimeBy(3_000); c.offer("a", "1")
        runCurrent()
        assertEquals(2, sent.size)
    }

    @Test
    fun `a removed key sends nothing more, and says whether anything went`() = runTest {
        val sent = mutableListOf<String>()
        val c = Coalescer<String>(backgroundScope, 2_000, { testScheduler.currentTime }) { _, v -> sent += v }
        assertFalse(c.forget("never"))
        c.offer("k", "1"); runCurrent()
        c.offer("k", "2")
        assertTrue(c.forget("k"))
        advanceTimeBy(5_000)
        assertEquals(listOf("1"), sent)
        assertFalse(c.forget("k"))
    }
}
