package dev.mydb.txn

import dev.mydb.kv.ByteKeys
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeySpanTest {
    private fun b(s: String) = s.toByteArray()

    private fun span(keys: List<String> = emptyList(), ranges: List<Pair<String, String?>> = emptyList()) =
        KeySpan(TreeSet(ByteKeys.COMPARATOR).apply { keys.forEach { add(b(it)) } }, ranges.map { (f, t) -> b(f) to t?.let { b(it) } })

    @Test
    fun `키·범위 조합의 겹침 판정`() {
        assertTrue(span(keys = listOf("a", "m")).intersects(span(keys = listOf("m"))))
        assertFalse(span(keys = listOf("a")).intersects(span(keys = listOf("b"))))
        assertTrue(span(keys = listOf("c")).intersects(span(ranges = listOf("b" to "d"))), "키가 범위 안")
        assertTrue(span(ranges = listOf("b" to "d")).intersects(span(keys = listOf("c"))), "반대 방향도")
        assertFalse(span(keys = listOf("d")).intersects(span(ranges = listOf("b" to "d"))), "상한은 제외")
        assertTrue(span(ranges = listOf("a" to "c")).intersects(span(ranges = listOf("b" to null))), "범위끼리, 끝까지")
        assertFalse(span(ranges = listOf("a" to "b")).intersects(span(ranges = listOf("b" to "c"))), "맞닿기만 하면 겹치지 않는다")
    }

    @Test
    fun `거칠게 만든 범위는 원래의 모든 키와 범위를 덮는다`() {
        val original = span(keys = listOf("k10", "k50", "k99"), ranges = listOf("m" to "n"))
        val coarse = original.coarsened()

        assertEquals(0, coarse.keys.size)
        assertEquals(1, coarse.ranges.size)
        for (probe in listOf("k10", "k50", "k99", "m", "mz")) assertTrue(coarse.intersects(span(keys = listOf(probe))), probe)
        assertTrue(coarse.intersects(span(keys = listOf("k77"))), "원래 없던 키도 덮인다 (거짓 양성의 원인)")
        assertFalse(coarse.intersects(span(keys = listOf("n"))), "가장 큰 범위의 상한 밖")
        assertTrue(span(ranges = listOf("x" to null)).coarsened().intersects(span(keys = listOf("zzzz"))), "끝까지 열린 범위는 열린 채")
    }

    @Test
    fun `키 수 상한을 넘을 때만 거칠게 만든다`() {
        val keys = (0 until 20).map { b("k%02d".format(it)) }
        assertEquals(20, KeySpan.of(keys, emptyList(), maxKeys = 20).keys.size)
        assertEquals(1, KeySpan.of(keys, emptyList(), maxKeys = 19).ranges.size)
    }
}
