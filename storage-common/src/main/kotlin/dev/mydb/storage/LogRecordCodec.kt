package dev.mydb.storage

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * [LogRecord] ↔ 프레임 payload 변환.
 *
 * ```
 * PUT (0x01)           | keyLen u32 | valLen u32 | key | value |
 * DELETE (0x02)        | keyLen u32 | key |
 * BATCH (0x03)         | count u32 | (PUT 또는 DELETE, type 바이트 포함) × count |
 * MERGE_FOOTER (0x7F)  | entryCount u64 |
 * ```
 * 모든 레코드는 type u8 한 바이트로 시작하고, 정수는 빅엔디언이다.
 */
object LogRecordCodec {
    const val MAX_KEY_SIZE = 64 * 1024
    const val MAX_VALUE_SIZE = 16 * 1024 * 1024

    private const val TYPE_PUT: Byte = 0x01
    private const val TYPE_DELETE: Byte = 0x02
    private const val TYPE_BATCH: Byte = 0x03
    private const val TYPE_MERGE_FOOTER: Byte = 0x7F

    fun encode(record: LogRecord): ByteArray {
        val size = encodedSize(record)
        require(size <= FrameCodec.MAX_PAYLOAD) {
            "레코드가 프레임 최대 크기 ${FrameCodec.MAX_PAYLOAD} 바이트를 넘습니다: $size"
        }
        val buf = ByteBuffer.allocate(size.toInt())
        write(buf, record)
        return buf.array()
    }

    /** 배치는 값 여러 개가 합쳐져 Int를 넘을 수 있으므로 Long으로 계산한다. */
    fun encodedSize(record: LogRecord): Long = when (record) {
        is LogRecord.Put -> 1L + 4 + 4 + record.key.size + record.value.size
        is LogRecord.Delete -> 1L + 4 + record.key.size
        is LogRecord.Batch -> 1L + 4 + record.ops.sumOf { encodedSize(it) }
        is LogRecord.MergeFooter -> 1L + 8
    }

    fun decode(payload: ByteArray): LogRecord {
        val buf = ByteBuffer.wrap(payload)
        val record = try {
            when (val type = readType(buf)) {
                TYPE_PUT -> readPut(buf)
                TYPE_DELETE -> readDelete(buf)
                TYPE_BATCH -> readBatch(buf)
                TYPE_MERGE_FOOTER -> readMergeFooter(buf)
                else -> throw CorruptionException("알 수 없는 레코드 타입: 0x%02X".format(type))
            }
        } catch (e: BufferUnderflowException) {
            throw CorruptionException("레코드가 선언된 길이보다 짧습니다 (payload ${payload.size} 바이트)")
        }
        if (buf.hasRemaining()) {
            throw CorruptionException("레코드 뒤에 해석되지 않은 ${buf.remaining()} 바이트가 남았습니다")
        }
        return record
    }

    private fun write(buf: ByteBuffer, record: LogRecord) {
        when (record) {
            is LogRecord.Put -> buf.put(TYPE_PUT)
                .putInt(record.key.size).putInt(record.value.size)
                .put(record.key).put(record.value)
            is LogRecord.Delete -> buf.put(TYPE_DELETE)
                .putInt(record.key.size)
                .put(record.key)
            is LogRecord.Batch -> {
                buf.put(TYPE_BATCH).putInt(record.ops.size)
                record.ops.forEach { write(buf, it) }
            }
            is LogRecord.MergeFooter -> buf.put(TYPE_MERGE_FOOTER).putLong(record.entryCount)
        }
    }

    private fun readType(buf: ByteBuffer): Byte {
        if (!buf.hasRemaining()) throw CorruptionException("빈 payload입니다")
        return buf.get()
    }

    private fun readPut(buf: ByteBuffer): LogRecord.Put {
        val keyLen = readLength(buf, MAX_KEY_SIZE, "키")
        val valLen = readLength(buf, MAX_VALUE_SIZE, "값")
        return LogRecord.Put(readBytes(buf, keyLen), readBytes(buf, valLen))
    }

    private fun readDelete(buf: ByteBuffer): LogRecord.Delete =
        LogRecord.Delete(readBytes(buf, readLength(buf, MAX_KEY_SIZE, "키")))

    private fun readBatch(buf: ByteBuffer): LogRecord.Batch {
        val count = buf.getInt()
        // op 하나는 최소 5바이트(DELETE, 빈 키)이므로 남은 바이트로 count의 상한을 검사할 수 있다
        if (count <= 0 || count > buf.remaining() / 5) {
            throw CorruptionException("배치 op 개수가 올바르지 않습니다: $count")
        }
        val ops = List(count) {
            when (val type = readType(buf)) {
                TYPE_PUT -> readPut(buf)
                TYPE_DELETE -> readDelete(buf)
                else -> throw CorruptionException("배치 안에 올 수 없는 레코드 타입: 0x%02X".format(type))
            }
        }
        return LogRecord.Batch(ops)
    }

    private fun readMergeFooter(buf: ByteBuffer): LogRecord.MergeFooter {
        val entryCount = buf.getLong()
        if (entryCount < 0) throw CorruptionException("entryCount가 음수입니다: $entryCount")
        return LogRecord.MergeFooter(entryCount)
    }

    /** u32를 Int로 읽으므로 2^31 이상은 음수가 된다. 생성자의 require 대신 여기서 CorruptionException으로 바꾼다. */
    private fun readLength(buf: ByteBuffer, max: Int, what: String): Int {
        val len = buf.getInt()
        if (len !in 0..max) throw CorruptionException("$what 길이가 올바르지 않습니다: $len")
        return len
    }

    private fun readBytes(buf: ByteBuffer, len: Int): ByteArray {
        if (buf.remaining() < len) throw BufferUnderflowException()
        return ByteArray(len).also { buf.get(it) }
    }
}
