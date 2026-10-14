package dev.mydb.engine.lsm.sstable

import dev.mydb.engine.lsm.Entry
import dev.mydb.engine.lsm.MemValue
import dev.mydb.kv.ByteKeys
import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.IoCounters
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.file.Path

/**
 * 테이블을 열 때 footer·인덱스·bloom을 메모리에 올리고, data block은 필요할 때 하나씩 읽는다.
 * 메모리 사용량은 키 수가 아니라 블록 수(인덱스)와 키당 bloom 비트에 비례한다.
 */
class SSTableReader private constructor(
    val path: Path,
    private val file: AppendOnlyFile,
    val footer: TableFooter,
    private val lastKeys: Array<ByteArray>,
    private val offsets: LongArray,
    private val bloom: BloomFilter,
) : AutoCloseable {

    val blockCount: Int
        get() = lastKeys.size

    /** 이 테이블에서의 값. 테이블에 키가 없으면 null (tombstone은 [MemValue.Tombstone]). */
    fun get(key: ByteArray): MemValue? {
        if (!bloom.mightContain(key)) return null
        val blockIndex = firstBlockAtOrAfter(key)
        if (blockIndex == lastKeys.size) return null
        return readBlock(blockIndex).firstOrNull { ByteKeys.compare(it.key, key) == 0 }?.value
    }

    /** [from, to) 범위를 키 순서로. tombstone도 포함한다 (가릴 옛 값이 다른 테이블에 있을 수 있다). */
    fun iterator(from: ByteArray, to: ByteArray?): Iterator<Entry> = iterator {
        var blockIndex = firstBlockAtOrAfter(from)
        while (blockIndex < lastKeys.size) {
            for (entry in readBlock(blockIndex)) {
                if (ByteKeys.compare(entry.key, from) < 0) continue
                if (to != null && ByteKeys.compare(entry.key, to) >= 0) return@iterator
                yield(entry)
            }
            blockIndex++
        }
    }

    override fun close() = file.close()

    /** lastKey ≥ key 인 첫 블록. 없으면 blockCount. get과 scan 시작 위치가 같은 규칙을 쓴다. */
    private fun firstBlockAtOrAfter(key: ByteArray): Int {
        var low = 0
        var high = lastKeys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ByteKeys.compare(lastKeys[mid], key) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun readBlock(index: Int): List<Entry> = Block.decode(readFrame(offsets[index], "data block $index"))

    private fun readFrame(offset: Long, what: String): ByteArray = readFrame(file, offset, "$path $what")

    companion object {
        fun open(path: Path, counters: IoCounters): SSTableReader {
            val file = AppendOnlyFile.openReadOnly(path, counters)
            try {
                if (file.size < Footer.FRAME_SIZE) throw CorruptionException("SSTable이 footer보다 작습니다: $path (${file.size} B)")
                val footer = Footer.decode(readFrame(file, file.size - Footer.FRAME_SIZE, "$path footer"))
                val (lastKeys, offsets) = decodeIndex(readFrame(file, footer.indexOffset, "$path index"))
                val bloom = BloomFilter.decode(readFrame(file, footer.bloomOffset, "$path bloom"))
                return SSTableReader(path, file, TableFooter(footer.entryCount, footer.age), lastKeys, offsets, bloom)
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }

        private fun readFrame(file: AppendOnlyFile, offset: Long, what: String): ByteArray {
            if (offset !in 0..file.size) throw CorruptionException("$what 위치가 파일 밖입니다: $offset")
            return when (val result = file.readFrame(offset)) {
                is FrameReadResult.Ok -> result.payload
                else -> throw CorruptionException("$what 을(를) 읽을 수 없습니다: $result")
            }
        }

        private fun decodeIndex(payload: ByteArray): Pair<Array<ByteArray>, LongArray> {
            val buf = ByteBuffer.wrap(payload)
            try {
                val count = buf.getInt()
                if (count < 0 || count > payload.size / 16) throw CorruptionException("인덱스 블록 수가 올바르지 않습니다: $count")
                val offsets = LongArray(count)
                val keys = Array(count) { i ->
                    val keyLen = buf.getInt()
                    if (keyLen !in 0..buf.remaining()) throw CorruptionException("인덱스 키 길이가 올바르지 않습니다: $keyLen")
                    val key = ByteArray(keyLen).also { buf.get(it) }
                    offsets[i] = buf.getLong()
                    buf.getInt() // blockFrameSize: 프레임 헤더로 길이를 알 수 있어 읽기에는 쓰지 않는다
                    key
                }
                return keys to offsets
            } catch (e: BufferUnderflowException) {
                throw CorruptionException("인덱스 블록이 선언된 길이보다 짧습니다")
            }
        }
    }
}

/** footer에서 엔진이 대조에 쓰는 값. */
data class TableFooter(val entryCount: Long, val age: Long)
