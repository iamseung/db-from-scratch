package dev.mydb.engine.lsm.manifest

import dev.mydb.engine.lsm.sstable.TableMeta
import dev.mydb.storage.CorruptionException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * 살아 있는 파일 집합의 변경 한 건. MANIFEST의 프레임 하나에 담기므로 전부 적용되거나 전부 무시된다.
 * flush(테이블 추가 + WAL 폐기)와 compaction(추가 + 입력 제거)이 이것으로 원자적이 된다.
 */
class ManifestEdit(val changes: List<Change>) {
    sealed interface Change {
        class AddTable(val meta: TableMeta) : Change
        data class RemoveTable(val fileId: Long) : Change

        /** 이 번호 미만의 WAL은 이미 SSTable에 반영되었다. */
        data class LogNumber(val walFileId: Long) : Change
        data class NextFileId(val fileId: Long) : Change
        data class NextAge(val age: Long) : Change
    }

    fun encode(): ByteArray {
        val size = 4 + changes.sumOf { change ->
            1 + when (change) {
                is Change.AddTable -> 8 * 4 + 4 + change.meta.minKey.size + 4 + change.meta.maxKey.size
                else -> 8
            }
        }
        val buf = ByteBuffer.allocate(size).putInt(changes.size)
        for (change in changes) {
            when (change) {
                is Change.AddTable -> {
                    val m = change.meta
                    buf.put(ADD_TABLE).putLong(m.fileId).putLong(m.age).putLong(m.entryCount).putLong(m.fileSize)
                        .putInt(m.minKey.size).put(m.minKey).putInt(m.maxKey.size).put(m.maxKey)
                }
                is Change.RemoveTable -> buf.put(REMOVE_TABLE).putLong(change.fileId)
                is Change.LogNumber -> buf.put(LOG_NUMBER).putLong(change.walFileId)
                is Change.NextFileId -> buf.put(NEXT_FILE_ID).putLong(change.fileId)
                is Change.NextAge -> buf.put(NEXT_AGE).putLong(change.age)
            }
        }
        return buf.array()
    }

    companion object {
        private const val ADD_TABLE: Byte = 0x10
        private const val REMOVE_TABLE: Byte = 0x11
        private const val LOG_NUMBER: Byte = 0x12
        private const val NEXT_FILE_ID: Byte = 0x13
        private const val NEXT_AGE: Byte = 0x14

        fun decode(payload: ByteArray): ManifestEdit {
            val buf = ByteBuffer.wrap(payload)
            try {
                val count = buf.getInt()
                if (count < 0 || count > payload.size) throw CorruptionException("MANIFEST 변경 수가 올바르지 않습니다: $count")
                val changes = List(count) {
                    when (val type = buf.get()) {
                        ADD_TABLE -> {
                            val fileId = buf.getLong()
                            val age = buf.getLong()
                            val entryCount = buf.getLong()
                            val fileSize = buf.getLong()
                            Change.AddTable(TableMeta(fileId, age, entryCount, fileSize, readBytes(buf), readBytes(buf)))
                        }
                        REMOVE_TABLE -> Change.RemoveTable(buf.getLong())
                        LOG_NUMBER -> Change.LogNumber(buf.getLong())
                        NEXT_FILE_ID -> Change.NextFileId(buf.getLong())
                        NEXT_AGE -> Change.NextAge(buf.getLong())
                        else -> throw CorruptionException("알 수 없는 MANIFEST 변경 타입: 0x%02X".format(type))
                    }
                }
                if (buf.hasRemaining()) throw CorruptionException("MANIFEST 편집 뒤에 ${buf.remaining()} 바이트가 남았습니다")
                return ManifestEdit(changes)
            } catch (e: BufferUnderflowException) {
                throw CorruptionException("MANIFEST 편집이 선언된 길이보다 짧습니다")
            }
        }

        private fun readBytes(buf: ByteBuffer): ByteArray {
            val len = buf.getInt()
            if (len !in 0..buf.remaining()) throw CorruptionException("MANIFEST 키 길이가 올바르지 않습니다: $len")
            return ByteArray(len).also { buf.get(it) }
        }
    }
}
