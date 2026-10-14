package dev.mydb.engine.lsm.sstable

import dev.mydb.engine.lsm.Entry
import dev.mydb.engine.lsm.MemValue
import dev.mydb.storage.CorruptionException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * data block: | entryCount u32 | (keyLen u32 | valLen i32 | key | value) × entryCount |
 * valLen = -1이면 tombstone이고 value 바이트가 없다.
 */
internal object Block {
    const val TOMBSTONE_LEN = -1

    fun entrySize(entry: Entry): Int = 8 + entry.key.size + ((entry.value as? MemValue.Put)?.value?.size ?: 0)

    fun encode(entries: List<Entry>): ByteArray {
        val buf = ByteBuffer.allocate(4 + entries.sumOf { entrySize(it) })
        buf.putInt(entries.size)
        for (entry in entries) {
            buf.putInt(entry.key.size)
            when (val value = entry.value) {
                is MemValue.Put -> buf.putInt(value.value.size).put(entry.key).put(value.value)
                MemValue.Tombstone -> buf.putInt(TOMBSTONE_LEN).put(entry.key)
            }
        }
        return buf.array()
    }

    fun decode(payload: ByteArray): List<Entry> {
        val buf = ByteBuffer.wrap(payload)
        try {
            val count = buf.getInt()
            if (count < 0 || count > payload.size / 8) throw CorruptionException("블록 엔트리 수가 올바르지 않습니다: $count")
            val entries = List(count) {
                val keyLen = buf.getInt()
                val valLen = buf.getInt()
                if (keyLen !in 0..buf.remaining() || valLen < TOMBSTONE_LEN || valLen > buf.remaining() - keyLen) {
                    throw CorruptionException("블록 엔트리 길이가 올바르지 않습니다: key=$keyLen value=$valLen")
                }
                val key = ByteArray(keyLen).also { buf.get(it) }
                val value = if (valLen == TOMBSTONE_LEN) MemValue.Tombstone else MemValue.Put(ByteArray(valLen).also { buf.get(it) })
                Entry(key, value)
            }
            if (buf.hasRemaining()) throw CorruptionException("블록 뒤에 ${buf.remaining()} 바이트가 남았습니다")
            return entries
        } catch (e: BufferUnderflowException) {
            throw CorruptionException("블록이 선언된 길이보다 짧습니다")
        }
    }
}
