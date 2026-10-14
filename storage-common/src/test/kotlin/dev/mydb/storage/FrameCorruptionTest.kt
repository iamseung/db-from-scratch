package dev.mydb.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 마지막 쓰기가 중간에 끊긴 상황을 흉내 낸다.
 * 두 번째 프레임을 0바이트부터 전체-1바이트까지 모든 길이로 잘라 본다.
 */
class FrameCorruptionTest {
    private val first = FrameCodec.encode("committed".toByteArray()).toByteArray()
    private val second = FrameCodec.encode("in-flight-write".toByteArray()).toByteArray()

    @Test
    fun `두 번째 프레임이 어느 지점에서 잘려도 첫 프레임은 온전하고 잘린 프레임은 감지된다`() {
        for (written in 0 until second.size) {
            val source = ByteArrayFrameSource(first + second.copyOf(written))

            val results = FrameCodec.readAll(source).toList()

            val (firstOffset, firstResult) = results[0]
            assertEquals(0L, firstOffset)
            assertIs<FrameReadResult.Ok>(firstResult)
            assertContentEquals("committed".toByteArray(), firstResult.payload)

            if (written == 0) {
                assertEquals(1, results.size, "두 번째 프레임을 한 바이트도 안 썼으면 정상 종료")
                continue
            }
            val expected = if (written < FrameCodec.HEADER_SIZE) {
                CorruptionReason.TRUNCATED_HEADER
            } else {
                CorruptionReason.TRUNCATED_PAYLOAD
            }
            assertEquals(
                listOf(first.size.toLong() to FrameReadResult.Corrupted(first.size.toLong(), expected)),
                results.drop(1),
                "두 번째 프레임을 $written 바이트만 쓴 경우",
            )
        }
    }
}
