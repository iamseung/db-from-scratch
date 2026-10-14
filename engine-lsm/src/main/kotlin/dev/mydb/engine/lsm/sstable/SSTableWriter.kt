package dev.mydb.engine.lsm.sstable

import dev.mydb.engine.lsm.Entry
import dev.mydb.kv.ByteKeys
import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.Fsync
import dev.mydb.storage.IoCounters
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * 정렬된 엔트리를 받아 SSTable 하나를 만든다. 파일은 M0 프레임의 연속이다:
 * data block들 → index → bloom → footer.
 *
 * `.tmp`에 쓰고 sync한 뒤 rename한다. 이 시점에도 테이블은 MANIFEST에 오르기 전까지 존재하지 않는 것으로 취급된다.
 */
class SSTableWriter(
    private val finalPath: Path,
    private val fileId: Long,
    private val age: Long,
    private val counters: IoCounters,
    private val blockSizeBytes: Int,
    private val bloomBitsPerKey: Int,
) {
    private val tmpPath = finalPath.resolveSibling(finalPath.fileName.toString() + ".tmp")
    private val file: AppendOnlyFile

    private val block = ArrayList<Entry>()
    private var blockBytes = 4
    private val indexKeys = ArrayList<ByteArray>()
    private val indexOffsets = ArrayList<Long>()
    private val indexSizes = ArrayList<Int>()
    private var keyHashes = LongArray(1024)
    private var count = 0
    private var firstKey: ByteArray? = null
    private var lastKey: ByteArray? = null

    init {
        Files.deleteIfExists(tmpPath)
        file = AppendOnlyFile.openForAppend(tmpPath, counters)
    }

    fun add(entry: Entry) {
        lastKey?.let { require(ByteKeys.compare(it, entry.key) < 0) { "SSTable 키는 엄격히 오름차순이어야 합니다" } }
        if (block.isNotEmpty() && blockBytes + Block.entrySize(entry) > blockSizeBytes) flushBlock()
        block += entry
        blockBytes += Block.entrySize(entry)
        if (count == keyHashes.size) keyHashes = keyHashes.copyOf(count * 2)
        keyHashes[count++] = BloomFilter.hash64(entry.key)
        if (firstKey == null) firstKey = entry.key
        lastKey = entry.key
    }

    /** 엔트리가 하나도 없으면 파일을 만들지 않고 null. */
    fun finish(): TableMeta? {
        if (count == 0) {
            abort()
            return null
        }
        flushBlock()
        val indexOffset = file.size
        file.append(encodeIndex())
        val bloomOffset = file.size
        file.append(BloomFilter.build(keyHashes, count, bloomBitsPerKey).encode())
        val footerOffset = file.size
        file.append(Footer(indexOffset, (bloomOffset - indexOffset).toInt(), bloomOffset, (footerOffset - bloomOffset).toInt(), count.toLong(), age).encode())
        file.sync()
        val size = file.size
        file.close()
        Files.move(tmpPath, finalPath, ATOMIC_MOVE)
        Fsync.directory(finalPath.parent)
        return TableMeta(fileId, age, count.toLong(), size, firstKey!!, lastKey!!)
    }

    fun abort() {
        file.close()
        Files.deleteIfExists(tmpPath)
    }

    private fun flushBlock() {
        if (block.isEmpty()) return
        val offset = file.append(Block.encode(block))
        indexKeys += block.last().key
        indexOffsets += offset
        indexSizes += (file.size - offset).toInt()
        block.clear()
        blockBytes = 4
    }

    /** | blockCount u32 | (lastKeyLen u32 | lastKey | blockOffset u64 | blockFrameSize u32) × blockCount | */
    private fun encodeIndex(): ByteArray {
        val buf = ByteBuffer.allocate(4 + indexKeys.sumOf { 4 + it.size + 8 + 4 })
        buf.putInt(indexKeys.size)
        for (i in indexKeys.indices) {
            buf.putInt(indexKeys[i].size).put(indexKeys[i]).putLong(indexOffsets[i]).putInt(indexSizes[i])
        }
        return buf.array()
    }
}
