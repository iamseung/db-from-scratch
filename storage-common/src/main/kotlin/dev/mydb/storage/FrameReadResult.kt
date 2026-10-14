package dev.mydb.storage

sealed interface FrameReadResult {
    class Ok(val payload: ByteArray, val nextOffset: Long) : FrameReadResult

    /** offset이 정확히 저장소 끝이다. 손상이 아니라 정상 종료. */
    data object EndOfFile : FrameReadResult

    data class Corrupted(val offset: Long, val reason: CorruptionReason) : FrameReadResult
}

enum class CorruptionReason {
    /** 헤더 8바이트조차 다 쓰이지 않았다. */
    TRUNCATED_HEADER,

    /** length 필드가 상한을 넘거나 음수(u32 기준 2^31 이상)다. */
    LENGTH_OVERFLOW,

    /** 헤더가 말하는 길이만큼 payload가 없다. */
    TRUNCATED_PAYLOAD,

    /** length + payload의 CRC32C가 저장된 값과 다르다. */
    CHECKSUM_MISMATCH,
}
