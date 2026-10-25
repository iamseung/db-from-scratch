package dev.mydb.engine.btree

import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.IoCounters
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * 페이지 번호 ↔ 파일 오프셋. 처음으로 파일 **중간을 덮어쓰는** 저장소다.
 * 새로 할당한 페이지는 체크포인트 때 처음 기록되므로 그 전에는 파일에 없다.
 */
class Pager(path: Path, val pageSize: Int, private val counters: IoCounters) : AutoCloseable {
    private val channel = FileChannel.open(path, CREATE, READ, WRITE)

    /** 다음에 할당할 페이지 번호 = 논리적 페이지 수. meta가 기억한다. */
    var pageCount: Int = 0

    val fileSize: Long
        get() = channel.size()

    fun read(pageId: Int): Page {
        val bytes = ByteArray(pageSize)
        val buf = ByteBuffer.wrap(bytes)
        var position = pageId.toLong() * pageSize
        while (buf.hasRemaining()) {
            val n = channel.read(buf, position)
            if (n < 0) throw EOFException("페이지 $pageId 가 파일 밖입니다 (파일 ${channel.size()} B)")
            position += n
        }
        counters.bytesRead.addAndGet(pageSize.toLong())
        val page = Page(pageId, bytes)
        if (!page.checksumMatches()) throw CorruptionException("페이지 $pageId 체크섬 불일치 (찢어진 쓰기 또는 손상)")
        return page
    }

    /** 체크섬 검사 없이 파일 앞부분을 읽는다. 페이지 크기를 모를 때 meta의 pageSize를 먼저 확인하는 데 쓴다. */
    fun readPrefix(length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var position = 0L
        while (buf.hasRemaining()) {
            val n = channel.read(buf, position)
            if (n < 0) break
            position += n
        }
        return buf.array()
    }

    /** 체크섬을 기록하고 제자리에 쓴다. */
    fun write(page: Page) {
        page.writeChecksum()
        writeRaw(page.id, page.bytes)
    }

    /** doublewrite 복구용: 이미 체크섬이 기록된 이미지를 그대로 쓴다. */
    fun writeRaw(pageId: Int, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        var position = pageId.toLong() * pageSize
        while (buf.hasRemaining()) position += channel.write(buf, position)
        counters.bytesWritten.addAndGet(bytes.size.toLong())
    }

    fun allocate(): Int = pageCount++

    fun sync() {
        channel.force(true)
        counters.syncCount.incrementAndGet()
    }

    override fun close() = channel.close()
}

class Frame(val page: Page) {
    var pins = 0
    var dirty = false
}

/**
 * LRU 버퍼 풀. **no-steal**: 내보낼 수 있는 것은 pin이 0이고 깨끗한 프레임뿐이다.
 * 더러운 페이지는 체크포인트 전까지 디스크에 가지 않으므로, 디스크의 트리는 항상 마지막 체크포인트 상태로 일관된다.
 * 대가: 체크포인트 사이에 바뀔 수 있는 페이지 수가 [capacity]로 제한된다.
 */
class BufferPool(val capacity: Int, private val pager: Pager) {
    private val frames = LinkedHashMap<Int, Frame>(capacity, 0.75f, true)

    val size: Int
        get() = frames.size

    fun fetch(pageId: Int): Frame {
        frames[pageId]?.let { it.pins++; return it }
        makeRoom()
        val frame = Frame(pager.read(pageId))
        frame.pins = 1
        frames[pageId] = frame
        return frame
    }

    fun newPage(type: Int): Frame {
        makeRoom()
        val page = Page(pager.allocate(), ByteArray(pager.pageSize))
        page.init(type)
        val frame = Frame(page)
        frame.pins = 1
        frame.dirty = true
        frames[page.id] = frame
        return frame
    }

    fun unpin(frame: Frame) {
        check(frame.pins > 0) { "pin되지 않은 페이지를 unpin합니다: ${frame.page.id}" }
        frame.pins--
    }

    /** 지금 새 프레임을 [n]개 더 확보할 수 있는가 (빈 자리 + 내보낼 수 있는 프레임). */
    fun hasHeadroom(n: Int): Boolean = capacity - frames.size + frames.values.count { it.pins == 0 && !it.dirty } >= n

    fun dirtyFrames(): List<Frame> = frames.values.filter { it.dirty }.sortedBy { it.page.id }

    fun markAllClean() = frames.values.forEach { it.dirty = false }

    private fun makeRoom() {
        if (frames.size < capacity) return
        val victim = frames.entries.firstOrNull { (_, f) -> f.pins == 0 && !f.dirty }
            ?: throw IllegalStateException("버퍼 풀에 내보낼 깨끗한 페이지가 없습니다 (용량 $capacity). 엔진이 미리 체크포인트해야 합니다")
        frames.remove(victim.key)
    }
}

/**
 * 체크포인트의 commit point. 바뀔 페이지 이미지를 먼저 여기에 확정해 두면,
 * 제자리 쓰기가 어디서 멈췄든 이 파일로 다시 덮어써 "체크포인트 완료" 상태로 만들 수 있다.
 *
 * 프레임: `| pageId u32 | page |` × n, 마지막 `| MAGIC u32 | n u32 |` (footer)
 */
object DoubleWrite {
    const val FILE_NAME = "doublewrite"
    private const val FOOTER_MAGIC = 0x44574F4B // "DWOK"

    fun write(dir: Path, pages: List<Page>, counters: IoCounters) {
        val path = dir.resolve(FILE_NAME)
        Files.deleteIfExists(path)
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            for (page in pages) {
                page.writeChecksum()
                file.append(ByteBuffer.allocate(4 + page.size).putInt(page.id).put(page.bytes).array())
            }
            file.append(ByteBuffer.allocate(8).putInt(FOOTER_MAGIC).putInt(pages.size).array())
            file.sync()
        }
    }

    /** footer까지 온전하면 (pageId, 이미지) 목록, 아니면 null (체크포인트가 commit되지 않음). */
    fun readComplete(path: Path, pageSize: Int, counters: IoCounters): List<Pair<Int, ByteArray>>? =
        AppendOnlyFile.openReadOnly(path, counters).use { file ->
            val pages = ArrayList<Pair<Int, ByteArray>>()
            for ((_, result) in file.frames()) {
                if (result !is FrameReadResult.Ok) return null
                val buf = ByteBuffer.wrap(result.payload)
                if (result.payload.size == 8) {
                    val complete = buf.getInt() == FOOTER_MAGIC && buf.getInt() == pages.size
                    return if (complete) pages else null
                }
                if (result.payload.size != 4 + pageSize) throw CorruptionException("doublewrite 프레임 크기가 올바르지 않습니다: ${result.payload.size}")
                val pageId = buf.getInt()
                pages += pageId to ByteArray(pageSize).also { buf.get(it) }
            }
            null
        }
}
