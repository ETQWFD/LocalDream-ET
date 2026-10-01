package io.github.xororz.localdream.data

/**
 * Bounded min-heap that keeps the highest [capacity] (score, index) pairs seen.
 *
 * Each pair is packed into a single Long with the score in the high word, so a
 * plain signed Long comparison orders by score (then index). Used by the tag
 * suggester to collect the best [capacity] fuzzy hits without allocating a
 * TagSuggestion per candidate or running a full O(n log n) sort over every hit.
 */
internal class TopKLongs(private val capacity: Int) {
    private val heap = LongArray(if (capacity > 0) capacity else 1)
    private var size = 0

    fun offer(score: Int, index: Int) {
        if (capacity <= 0) return
        val packed = (score.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
        if (size < capacity) {
            heap[size] = packed
            siftUp(size)
            size++
        } else if (packed > heap[0]) {
            heap[0] = packed
            siftDown()
        }
    }

    fun forEach(action: (score: Int, index: Int) -> Unit) {
        for (i in 0 until size) {
            val packed = heap[i]
            action((packed shr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())
        }
    }

    private fun siftUp(from: Int) {
        var child = from
        while (child > 0) {
            val parent = (child - 1) ushr 1
            if (heap[child] >= heap[parent]) break
            val tmp = heap[child]
            heap[child] = heap[parent]
            heap[parent] = tmp
            child = parent
        }
    }

    private fun siftDown() {
        var parent = 0
        while (true) {
            val left = parent * 2 + 1
            val right = left + 1
            var smallest = parent
            if (left < size && heap[left] < heap[smallest]) smallest = left
            if (right < size && heap[right] < heap[smallest]) smallest = right
            if (smallest == parent) break
            val tmp = heap[parent]
            heap[parent] = heap[smallest]
            heap[smallest] = tmp
            parent = smallest
        }
    }
}
