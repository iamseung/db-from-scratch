package dev.mydb.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 프레임의 모든 비트를 하나씩 뒤집어 본다.
 * 어느 비트가 깨져도 Ok로 읽혀서는 안 된다.
 */
class BitFlipTest {
    private val frame = FrameCodec.encode("0123456789abcdef".toByteArray()).toByteArray()

    @Test
    fun `헤더와 payload 어느 비트가 깨져도 Ok로 읽히지 않는다`() {
        for (bit in 0 until frame.size * 8) {
            val result = FrameCodec.read(ByteArrayFrameSource(flip(frame, bit)), 0)

            assertIs<FrameReadResult.Corrupted>(result, "비트 $bit 반전")
        }
    }

    @Test
    fun `payload 비트가 깨지면 체크섬 불일치다`() {
        for (bit in FrameCodec.HEADER_SIZE * 8 until frame.size * 8) {
            val result = FrameCodec.read(ByteArrayFrameSource(flip(frame, bit)), 0)

            assertEquals(FrameReadResult.Corrupted(0, CorruptionReason.CHECKSUM_MISMATCH), result, "비트 $bit 반전")
        }
    }

    @Test
    fun `length가 더 작은 값으로 깨져도 CRC가 length를 덮으므로 감지된다`() {
        // length 16의 하위 비트(바이트 7의 bit4)를 지워 0으로 만든다: 남은 바이트는 충분하므로 길이 검사는 통과한다
        val bytes = frame.copyOf().also { it[7] = 0 }

        val result = FrameCodec.read(ByteArrayFrameSource(bytes), 0)

        assertEquals(FrameReadResult.Corrupted(0, CorruptionReason.CHECKSUM_MISMATCH), result)
    }

    private fun flip(bytes: ByteArray, bit: Int): ByteArray =
        bytes.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
}
