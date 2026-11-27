package io.legado.app.help.watchparty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFreshnessTest {

    @Test
    fun `first sync is accepted`() {
        val gate = SyncFreshness()
        assertTrue(gate.accept("a", 100L))
    }

    @Test
    fun `replayed older sync is dropped`() {
        val gate = SyncFreshness()
        assertTrue(gate.accept("a", 200L))
        assertFalse(gate.accept("a", 100L))
    }

    @Test
    fun `same timestamp is dropped`() {
        val gate = SyncFreshness()
        assertTrue(gate.accept("a", 100L))
        assertFalse(gate.accept("a", 100L))
    }

    @Test
    fun `newer sync is accepted`() {
        val gate = SyncFreshness()
        assertTrue(gate.accept("a", 100L))
        assertTrue(gate.accept("a", 150L))
    }

    @Test
    fun `users are tracked independently`() {
        val gate = SyncFreshness()
        assertTrue(gate.accept("a", 300L))
        assertTrue(gate.accept("b", 100L))
    }
}
