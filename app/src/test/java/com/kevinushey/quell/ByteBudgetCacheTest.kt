package com.kevinushey.quell

import com.kevinushey.quell.data.ByteBudgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteBudgetCacheTest {

    @Test
    fun evictsLeastRecentlyUsedPastBudget() {
        val cache = ByteBudgetCache<String, IntArray>(100) { it.size.toLong() }
        cache.put("a", IntArray(40))
        cache.put("b", IntArray(40))
        cache["a"]
        cache.put("c", IntArray(40))

        assertNull(cache["b"])
        assertEquals(40, cache["a"]!!.size)
        assertEquals(40, cache["c"]!!.size)
        assertTrue(cache.bytes <= 100)
    }

    @Test
    fun oversizedEntryIsStillKept() {
        val cache = ByteBudgetCache<String, IntArray>(10) { it.size.toLong() }
        cache.put("big", IntArray(50))
        assertEquals(1, cache.size)
        cache.removeIf { it == "big" }
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)
    }
}
