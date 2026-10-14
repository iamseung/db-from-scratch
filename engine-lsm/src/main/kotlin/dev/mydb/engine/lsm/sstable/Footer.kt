package dev.mydb.engine.lsm.sstable

import dev.mydb.storage.CorruptionException
import dev.mydb.storage.FrameCodec
import java.nio.ByteBuffer

/**
 * | indexOffset u64 | indexFrameSize u32 | bloomOffset u64 | bloomFrameSize u32 | entryCount u64 | age u64 | magic u32 |
 * 고정 크기 프레임이라 파일 크기만 알면 위치가 정해진다.
 */
internal data class Footer(
    val indexOffset: Long,
    val indexFrameSize: Int,
    val bloomOffset: Long,
    val bloomFrameSize: Int,
    val entryCount: Long,
    val age: Long,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(PAYLOAD_SIZE)
        .putLong(indexOffset).putInt(indexFrameSize)
        .putLong(bloomOffset).putInt(bloomFrameSize)
        .putLong(entryCount).putLong(age)
        .putInt(MAGIC)
        .array()

    companion object {
        const val PAYLOAD_SIZE = 44
        const val FRAME_SIZE = FrameCodec.HEADER_SIZE + PAYLOAD_SIZE
        const val MAGIC = 0x4C534D31 // "LSM1"

        fun decode(payload: ByteArray): Footer {
            if (payload.size != PAYLOAD_SIZE) throw CorruptionException("footer 크기가 올바르지 않습니다: ${payload.size}")
            val buf = ByteBuffer.wrap(payload)
            val footer = Footer(buf.getLong(), buf.getInt(), buf.getLong(), buf.getInt(), buf.getLong(), buf.getLong())
            val magic = buf.getInt()
            if (magic != MAGIC) throw CorruptionException("SSTable magic이 다릅니다: 0x%08X".format(magic))
            return footer
        }
    }
}
