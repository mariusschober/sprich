package com.sprich.app.input.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSelectionRegistryTest {

    @Test
    fun ownSelectionIsConsumedOnceThenForgotten() {
        val registry = OwnSelectionRegistry()
        registry.note(5, 5)
        assertTrue(registry.consumeIfOwned(5, 5))
        assertFalse(registry.consumeIfOwned(5, 5))
        assertFalse(registry.consumeIfOwned(6, 6))
    }

    @Test
    fun externalSelectionIsNotSwallowed() {
        val registry = OwnSelectionRegistry()
        registry.note(1, 1)
        assertFalse(registry.consumeIfOwned(2, 2))
        // The recorded own selection is still available for its own callback.
        assertTrue(registry.consumeIfOwned(1, 1))
    }

    @Test
    fun entriesAreConsumedInReportingOrder() {
        val registry = OwnSelectionRegistry()
        registry.note(1, 1)
        registry.note(2, 2)
        registry.note(3, 3)
        assertTrue(registry.consumeIfOwned(2, 2))
        assertFalse(registry.consumeIfOwned(1, 1)) // older entry dropped with the matched prefix
        assertTrue(registry.consumeIfOwned(3, 3))
    }

    @Test
    fun historyIsBounded() {
        val registry = OwnSelectionRegistry(maxEntries = 4)
        repeat(10) { registry.note(it, it) }
        assertTrue(registry.size() <= 4)
        assertFalse(registry.consumeIfOwned(0, 0)) // evicted
        assertTrue(registry.consumeIfOwned(9, 9))
    }

    @Test
    fun clearDropsEverything() {
        val registry = OwnSelectionRegistry()
        registry.note(7, 7)
        registry.clear()
        assertFalse(registry.consumeIfOwned(7, 7))
        assertTrue(registry.size() == 0)
    }
}
