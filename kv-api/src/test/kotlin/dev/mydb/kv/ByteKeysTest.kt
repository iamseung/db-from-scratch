package dev.mydb.kv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteKeysTest {
    @Test
    fun `부호 없는 바이트로 비교한다`() {
        // 0xFF는 signed byte로는 -1이지만 키 비교에서는 가장 큰 값이어야 한다
        assertTrue(ByteKeys.compare(byteArrayOf(0x01), byteArrayOf(0xFF.toByte())) < 0)
    }

    @Test
    fun `짧은 접두사가 먼저 온다`() {
        assertTrue(ByteKeys.compare("ab".toByteArray(), "abc".toByteArray()) < 0)
    }

    @Test
    fun `long 인코딩은 숫자 순서를 바이트 순서로 보존한다`() {
        val values = listOf(Long.MIN_VALUE, -1_000L, -1L, 0L, 1L, 42L, 1_000L, Long.MAX_VALUE)
        val sortedByBytes = values.shuffled().sortedWith(compareBy(ByteKeys.COMPARATOR) { ByteKeys.encodeLong(it) })
        assertEquals(values, sortedByBytes)
    }

    @Test
    fun `long 인코딩은 왕복 변환된다`() {
        listOf(Long.MIN_VALUE, -42L, 0L, 42L, Long.MAX_VALUE).forEach {
            assertEquals(it, ByteKeys.decodeLong(ByteKeys.encodeLong(it)))
        }
    }
}
