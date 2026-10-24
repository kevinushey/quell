package com.kevinushey.quell.data

/**
 * Least-recently-used cache bounded by the summed size of its values rather
 * than their count, so a few huge recordings cannot exhaust the heap. Not
 * thread-safe; callers serialize access.
 */
class ByteBudgetCache<K, V>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var bytes = 0L
        private set

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
        map[key] = value
        bytes += sizeOf(value)

        // Evict from the least recently used end; the new entry is always kept.
        val it = map.entries.iterator()
        while (bytes > budgetBytes && map.size > 1) {
            val eldest = it.next()
            bytes -= sizeOf(eldest.value)
            it.remove()
        }
    }

    fun remove(key: K) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
    }

    fun clear() {
        map.clear()
        bytes = 0L
    }

    fun removeIf(predicate: (K) -> Boolean) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) {
                bytes -= sizeOf(e.value)
                it.remove()
            }
        }
    }

    companion object {
        /** A quarter of the heap: two such caches plus working copies still leave headroom. */
        fun defaultBudget(): Long = Runtime.getRuntime().maxMemory() / 4
    }
}
