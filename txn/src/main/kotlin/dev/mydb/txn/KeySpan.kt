package dev.mydb.txn

import dev.mydb.kv.ByteKeys
import java.util.TreeSet

/**
 * SSI 기록의 읽기·쓰기 집합: 정확한 키 목록 + [from, to) 범위 목록 (to = null이면 끝까지).
 * 기록 수·키 수 상한을 넘으면 [coarsened]로 범위 하나로 거칠게 만든다. 거칠게 만들면 겹침 판정이 늘어날 뿐(거짓 양성)
 * 줄어들지는 않으므로 직렬화 가능성은 그대로다.
 */
internal class KeySpan(val keys: TreeSet<ByteArray>, val ranges: List<Pair<ByteArray, ByteArray?>>) {
    val size: Int
        get() = keys.size + ranges.size

    /** 키끼리는 작은 쪽을 순회하고, 범위는 양쪽 모두 상대의 키·범위와 비교한다. */
    fun intersects(other: KeySpan): Boolean {
        val (small, large) = if (keys.size <= other.keys.size) keys to other.keys else other.keys to keys
        if (small.any { it in large }) return true
        return ranges.any { (from, to) -> other.overlapsRange(from, to) } || other.ranges.any { (from, to) -> overlapsRange(from, to) }
    }

    private fun overlapsRange(from: ByteArray, to: ByteArray?): Boolean {
        if ((if (to == null) keys.tailSet(from, true) else keys.subSet(from, true, to, false)).isNotEmpty()) return true
        // [a, b)와 [c, d)가 겹치려면 a < d 이고 c < b
        return ranges.any { (c, d) -> (d == null || ByteKeys.compare(from, d) < 0) && (to == null || ByteKeys.compare(c, to) < 0) }
    }

    /** 모든 키·범위를 덮는 범위 하나. 비어 있으면 그대로. */
    fun coarsened(): KeySpan {
        if (size == 0) return this
        val lows = keys.toList() + ranges.map { it.first }
        val highs: List<ByteArray?> = keys.map { afterKey(it) } + ranges.map { it.second }
        val from = lows.minWith(ByteKeys.COMPARATOR)
        val to = if (highs.any { it == null }) null else highs.filterNotNull().maxWith(ByteKeys.COMPARATOR)
        return KeySpan(TreeSet(ByteKeys.COMPARATOR), listOf(from to to))
    }

    /** 둘을 모두 덮는 범위 하나 (요약 기록끼리 합칠 때). */
    fun unionCoarse(other: KeySpan): KeySpan =
        KeySpan(TreeSet(ByteKeys.COMPARATOR).apply { addAll(keys); addAll(other.keys) }, ranges + other.ranges).coarsened()

    companion object {
        fun of(keys: Collection<ByteArray>, ranges: List<Pair<ByteArray, ByteArray?>>, maxKeys: Int): KeySpan {
            val span = KeySpan(TreeSet(ByteKeys.COMPARATOR).apply { addAll(keys) }, ranges.toList())
            return if (span.size > maxKeys) span.coarsened() else span
        }

        /** key보다 큰 가장 작은 키 (key + 0x00). */
        private fun afterKey(key: ByteArray) = key + byteArrayOf(0)
    }
}
