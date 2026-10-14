package dev.mydb.storage

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FrameCodecTest {
    @Test
    fun `인코딩한 프레임을 다시 읽으면 같은 payload가 나온다`() {
        val payload = "hello".toByteArray()
        val bytes = FrameCodec.encode(payload).toByteArray()

        val result = FrameCodec.read(ByteArrayFrameSource(bytes), 0)

        assertIs<FrameReadResult.Ok>(result)
        assertContentEquals(payload, result.payload)
        assertEquals(bytes.size.toLong(), result.nextOffset)
    }

    @Test
    fun `프레임 크기는 헤더 8바이트 더하기 payload다`() {
        assertEquals(8 + 5, FrameCodec.encode(ByteArray(5)).remaining())
    }

    @Test
    fun `빈 payload도 정상 프레임이다`() {
        val result = FrameCodec.read(ByteArrayFrameSource(FrameCodec.encode(ByteArray(0)).toByteArray()), 0)

        assertIs<FrameReadResult.Ok>(result)
        assertEquals(0, result.payload.size)
    }

    @Test
    fun `최대 크기 payload를 왕복 변환한다`() {
        val payload = ByteArray(FrameCodec.MAX_PAYLOAD) { it.toByte() }

        val result = FrameCodec.read(ByteArrayFrameSource(FrameCodec.encode(payload).toByteArray()), 0)

        assertIs<FrameReadResult.Ok>(result)
        assertContentEquals(payload, result.payload)
    }

    @Test
    fun `최대 크기를 넘는 payload는 인코딩을 거부한다`() {
        assertFailsWith<IllegalArgumentException> { FrameCodec.encode(ByteArray(FrameCodec.MAX_PAYLOAD + 1)) }
    }

    @Test
    fun `이어 붙인 프레임을 순서대로 읽는다`() {
        val payloads = listOf("a", "", "ccc").map { it.toByteArray() }
        val source = ByteArrayFrameSource(concat(payloads.map { FrameCodec.encode(it).toByteArray() }))

        val read = FrameCodec.readAll(source).map { (_, result) -> (result as FrameReadResult.Ok).payload }.toList()

        assertEquals(payloads.map { it.toList() }, read.map { it.toList() })
    }

    @Test
    fun `정확히 끝에서 읽으면 EndOfFile이다`() {
        val bytes = FrameCodec.encode("x".toByteArray()).toByteArray()

        assertEquals(FrameReadResult.EndOfFile, FrameCodec.read(ByteArrayFrameSource(bytes), bytes.size.toLong()))
        assertEquals(FrameReadResult.EndOfFile, FrameCodec.read(ByteArrayFrameSource(ByteArray(0)), 0))
    }

    @Test
    fun `범위 밖 offset은 호출자 버그로 보고 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> { FrameCodec.read(ByteArrayFrameSource(ByteArray(4)), 5) }
    }

    @Test
    fun `length 상위 비트가 켜져 음수로 읽혀도 상한 초과로 판정한다`() {
        val bytes = FrameCodec.encode(ByteArray(4)).toByteArray()
        bytes[4] = 0x80.toByte()

        val result = FrameCodec.read(ByteArrayFrameSource(bytes), 0)

        assertEquals(FrameReadResult.Corrupted(0, CorruptionReason.LENGTH_OVERFLOW), result)
    }
}

internal fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { get(it) }

internal fun concat(parts: List<ByteArray>): ByteArray =
    parts.fold(ByteArray(0)) { acc, part -> acc + part }
