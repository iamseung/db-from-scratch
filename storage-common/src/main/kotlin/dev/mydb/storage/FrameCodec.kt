package dev.mydb.storage

import java.nio.ByteBuffer
import java.util.zip.CRC32C

/**
 * 모든 로그 파일의 최소 단위인 프레임을 인코딩/디코딩한다.
 *
 * ```
 * offset  size  field
 * 0       4     crc32c   (u32 BE) ← length + payload 를 덮는다
 * 4       4     length   (u32 BE) ← payload 바이트 수
 * 8       n     payload
 * ```
 *
 * 프레임은 "이 바이트들이 온전히 쓰였는가"만 책임지고 payload의 의미는 모른다.
 */
object FrameCodec {
    const val HEADER_SIZE = 8
    const val MAX_PAYLOAD = 32 * 1024 * 1024
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    fun frameSize(payloadSize: Int): Int = HEADER_SIZE + payloadSize

    /** 파일에 그대로 쓸 수 있는 프레임 버퍼를 만든다. 반환 버퍼는 읽기 위치 0으로 flip된 상태다. */
    fun encode(payload: ByteArray): ByteBuffer {
        require(payload.size <= MAX_PAYLOAD) {
            "payload는 최대 $MAX_PAYLOAD 바이트입니다: ${payload.size}"
        }
        return ByteBuffer.allocate(frameSize(payload.size))
            .putInt(checksum(payload.size, payload))
            .putInt(payload.size)
            .put(payload)
            .flip()
    }

    /** [offset]에서 시작하는 프레임 하나를 읽는다. */
    fun read(source: FrameSource, offset: Long): FrameReadResult {
        val size = source.size
        require(offset in 0..size) { "offset이 범위를 벗어났습니다: $offset (size=$size)" }

        val remaining = size - offset
        if (remaining == 0L) return FrameReadResult.EndOfFile
        if (remaining < HEADER_SIZE) return corrupted(offset, CorruptionReason.TRUNCATED_HEADER)

        val header = ByteBuffer.allocate(HEADER_SIZE)
        source.readFully(offset, header)
        header.flip()
        val storedCrc = header.getInt()
        val length = header.getInt()

        // u32를 Int로 읽었으므로 2^31 이상은 음수로 보인다. 둘 다 상한 초과로 취급한다.
        if (length !in 0..MAX_PAYLOAD) return corrupted(offset, CorruptionReason.LENGTH_OVERFLOW)
        if (remaining - HEADER_SIZE < length) return corrupted(offset, CorruptionReason.TRUNCATED_PAYLOAD)

        val payload = ByteArray(length)
        source.readFully(offset + HEADER_SIZE, ByteBuffer.wrap(payload))
        if (checksum(length, payload) != storedCrc) return corrupted(offset, CorruptionReason.CHECKSUM_MISMATCH)

        return FrameReadResult.Ok(payload, offset + frameSize(length))
    }

    /** [from]부터 끝까지 프레임을 차례로 읽는다. 첫 손상에서 그 결과를 내보내고 멈춘다. */
    fun readAll(source: FrameSource, from: Long = 0): Sequence<Pair<Long, FrameReadResult>> = sequence {
        var offset = from
        while (true) {
            val result = read(source, offset)
            if (result == FrameReadResult.EndOfFile) return@sequence
            yield(offset to result)
            if (result !is FrameReadResult.Ok) return@sequence
            offset = result.nextOffset
        }
    }

    /** length 필드까지 CRC에 넣어야 length가 깨진 경우를 감지할 수 있다. */
    private fun checksum(length: Int, payload: ByteArray): Int {
        val crc = CRC32C()
        crc.update(ByteBuffer.allocate(4).putInt(length).array())
        crc.update(payload)
        return crc.value.toInt()
    }

    private fun corrupted(offset: Long, reason: CorruptionReason) = FrameReadResult.Corrupted(offset, reason)
}
