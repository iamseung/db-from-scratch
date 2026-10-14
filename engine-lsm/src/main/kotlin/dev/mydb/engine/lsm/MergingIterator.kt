package dev.mydb.engine.lsm

import dev.mydb.kv.ByteKeys
import java.util.PriorityQueue

/**
 * 정렬된 반복자 여러 개를 하나의 정렬된 흐름으로 합친다 (k-way merge).
 * [sources]의 앞쪽이 우선순위가 높다(더 새로운 데이터). 같은 키가 여러 곳에 있으면 가장 앞의 것 하나만 내보낸다.
 * [dropTombstones]면 이긴 값이 tombstone일 때 그 키를 아예 내보내지 않는다.
 */
class MergingIterator(sources: List<Iterator<Entry>>, private val dropTombstones: Boolean) : Iterator<Entry> {
    private class Head(val entry: Entry, val priority: Int, val rest: Iterator<Entry>)

    private val heap = PriorityQueue<Head>(
        maxOf(1, sources.size),
        compareBy<Head, ByteArray>(ByteKeys.COMPARATOR) { it.entry.key }.thenBy { it.priority },
    )
    private var nextEntry: Entry? = null

    init {
        sources.forEachIndexed { priority, source -> advance(priority, source) }
        nextEntry = computeNext()
    }

    override fun hasNext() = nextEntry != null

    override fun next(): Entry {
        val result = nextEntry ?: throw NoSuchElementException()
        nextEntry = computeNext()
        return result
    }

    private fun computeNext(): Entry? {
        while (heap.isNotEmpty()) {
            val winner = heap.poll()
            advance(winner.priority, winner.rest)
            // 같은 키를 가진 우선순위 낮은 항목은 가려진 옛 값이므로 버린다
            while (heap.isNotEmpty() && ByteKeys.compare(heap.peek().entry.key, winner.entry.key) == 0) {
                val shadowed = heap.poll()
                advance(shadowed.priority, shadowed.rest)
            }
            if (dropTombstones && winner.entry.value is MemValue.Tombstone) continue
            return winner.entry
        }
        return null
    }

    private fun advance(priority: Int, source: Iterator<Entry>) {
        if (source.hasNext()) heap.add(Head(source.next(), priority, source))
    }
}
