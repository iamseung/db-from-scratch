package dev.mydb.engine.btree

import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp
import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.DirectoryLock
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.Fsync
import dev.mydb.storage.IoCounters
import dev.mydb.storage.LogRecord
import dev.mydb.storage.LogRecordCodec
import dev.mydb.storage.RecoveryMode
import dev.mydb.storage.SyncPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * B+tree KV 엔진. 페이지를 제자리에서 고친다.
 *
 * 쓰기: WAL append(논리 레코드) → 버퍼 풀의 페이지 수정(dirty). 페이지는 체크포인트 때만 디스크에 간다 (no-steal).
 * 체크포인트: 바뀐 페이지 전체를 doublewrite 파일에 확정(commit point) → 제자리 쓰기 → doublewrite·옛 WAL 삭제.
 * 복구: 온전한 doublewrite를 다시 덮어쓰고, 남은 WAL을 전부 재생한다. 레코드가 "k를 v로" 같은 논리 연산이라 재생은 멱등이다.
 */
class BTreeEngine private constructor(
    private val options: BTreeOptions,
    private val lock: DirectoryLock,
    private val counters: IoCounters,
    private val pager: Pager,
    private val pool: BufferPool,
    private val tree: BTree,
    private var wal: AppendOnlyFile,
    private var walId: Long,
) : KvEngine {
    private val dir = options.dataDir
    private val maxCellPayload = Page.maxCellPayload(options.pageSize)

    /** 복구 중 WAL 재생: 이때의 체크포인트는 WAL을 바꾸거나 지우지 않는다 (재생 중인 WAL이 사라지면 안 된다). */
    private var replaying = false
    private var userBytesWritten = 0L
    private var closed = false

    @Synchronized
    override fun get(key: ByteArray): ByteArray? {
        checkOpen()
        ensureHeadroom(carry = null)
        return tree.get(key)
    }

    @Synchronized
    override fun put(key: ByteArray, value: ByteArray) {
        checkOpen()
        checkCellSize(key, value)
        write(LogRecord.Put(key, value))
        userBytesWritten += key.size + value.size
    }

    @Synchronized
    override fun delete(key: ByteArray) {
        checkOpen()
        write(LogRecord.Delete(key))
        userBytesWritten += key.size
    }

    @Synchronized
    override fun writeBatch(ops: List<WriteOp>) {
        checkOpen()
        if (ops.isEmpty()) return
        val records = ops.map { op ->
            when (op) {
                is WriteOp.Put -> LogRecord.Put(op.key, op.value).also { checkCellSize(op.key, op.value) }
                is WriteOp.Delete -> LogRecord.Delete(op.key)
            }
        }
        write(LogRecord.Batch(records))
        userBytesWritten += records.sumOf { if (it is LogRecord.Put) (it.key.size + it.value.size).toLong() else it.key.size.toLong() }
    }

    /**
     * 범위를 생성 시점에 복사한다. 반복 중 쓰기로 split이 일어나면 셀이 다른 페이지로 옮겨
     * 리프를 따라가는 반복자는 누락·중복이 생길 수 있어서다 (설계 §7.4). 대가: 범위 크기만큼 메모리.
     */
    @Synchronized
    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        checkOpen()
        ensureHeadroom(carry = null)
        val entries = tree.range(from, to).iterator()
        return object : KvIterator {
            override fun hasNext() = entries.hasNext()

            override fun next(): KvEntry = entries.next().let { (k, v) -> KvEntry(k, v) }

            override fun close() = Unit
        }
    }

    @Synchronized
    override fun flush() {
        checkOpen()
        wal.sync()
    }

    /** 바뀐 페이지를 디스크에 반영하고 WAL을 새로 시작한다. */
    @Synchronized
    fun checkpoint() {
        checkOpen()
        checkpointInternal(carry = null)
    }

    /** 체크포인트 후 트리 전체의 불변식을 검사한다 (버퍼 풀이 깨끗해야 전체를 순회할 수 있다). */
    @Synchronized
    fun verify(): VerifyReport {
        checkOpen()
        checkpointInternal(carry = null)
        return BTreeVerifier.verify(tree)
    }

    @Synchronized
    override fun stats(): EngineStats = EngineStats(
        userBytesWritten = userBytesWritten,
        diskBytesWritten = counters.bytesWritten.get(),
        diskBytesRead = counters.bytesRead.get(),
        diskBytesUsed = pager.fileSize + wal.size,
    )

    /** 체크포인트하지 않고 닫는다. 다음 open이 WAL을 재생한다 (크래시와 같은 경로를 늘 쓰게 된다). */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        wal.close()
        pager.close()
        lock.close()
    }

    private fun write(record: LogRecord) {
        wal.append(LogRecordCodec.encode(record))
        if (options.syncPolicy == SyncPolicy.EVERY_WRITE) wal.sync()
        apply(record)
        if (wal.size >= options.checkpointWalBytes) checkpointInternal(carry = null)
    }

    /** 레코드를 트리에 적용한다. 연산마다 버퍼 풀 여유를 확인하고, 모자라면 이 레코드를 들고 체크포인트한다. */
    private fun apply(record: LogRecord) {
        when (record) {
            is LogRecord.Put -> {
                ensureHeadroom(carry = record)
                tree.put(record.key, record.value)
            }
            is LogRecord.Delete -> {
                ensureHeadroom(carry = record)
                tree.delete(record.key)
            }
            is LogRecord.Batch -> record.ops.forEach { op ->
                // 배치 중간에 체크포인트가 일어나도 배치 전체를 새 WAL에 다시 적으므로 나머지가 사라지지 않는다
                ensureHeadroom(carry = record)
                when (op) {
                    is LogRecord.Put -> tree.put(op.key, op.value)
                    is LogRecord.Delete -> tree.delete(op.key)
                }
            }
            is LogRecord.MergeFooter -> Unit
        }
    }

    /**
     * 연산 도중 버퍼 풀이 가득 차면 트리가 메모리에서 반쯤 바뀐 채로 멈춘다. 그래서 연산 **전에** 확인한다.
     * [carry]: 이미 WAL에 쓰였지만 아직 다 적용되지 않은 레코드. 체크포인트가 옛 WAL을 지우기 전에 새 WAL로 옮긴다.
     */
    private fun ensureHeadroom(carry: LogRecord?) {
        if (pool.hasHeadroom(tree.requiredHeadroom)) return
        checkpointInternal(carry)
        check(pool.hasHeadroom(tree.requiredHeadroom)) {
            "버퍼 풀(${pool.capacity}장)이 트리 높이 ${tree.height}에 비해 작습니다 (필요 ${tree.requiredHeadroom}장)"
        }
    }

    /**
     * 1. (재생 중이 아니면) 새 WAL 생성 + carry 레코드 기록·sync
     * 2. 바뀐 페이지 + meta를 doublewrite에 기록·sync   ← commit point
     * 3. 제자리 쓰기 → DB sync
     * 4. doublewrite 삭제, 옛 WAL 삭제
     */
    private fun checkpointInternal(carry: LogRecord?) {
        var newWal: AppendOnlyFile? = null
        val newWalId = walId + 1
        if (!replaying) {
            newWal = AppendOnlyFile.openForAppend(walPath(dir, newWalId), counters)
            carry?.let {
                newWal.append(LogRecordCodec.encode(it))
                newWal.sync()
            }
            Fsync.directory(dir)
        }

        val pages = pool.dirtyFrames().map { it.page } + metaPage()
        DoubleWrite.write(dir, pages, counters)
        Fsync.directory(dir)
        options.faultInjector.reached(BTreeStep.DW_SYNCED)
        for (page in pages) {
            pager.write(page)
            options.faultInjector.reached(BTreeStep.PAGE_WRITTEN)
        }
        pager.sync()
        Files.deleteIfExists(dir.resolve(DoubleWrite.FILE_NAME))
        pool.markAllClean()

        if (newWal != null) {
            wal.close()
            listWals(dir).filter { (id, _) -> id < newWalId }.forEach { (_, path) -> Files.deleteIfExists(path) }
            wal = newWal
            walId = newWalId
        }
        Fsync.directory(dir)
        options.faultInjector.reached(BTreeStep.CHECKPOINT_DONE)
    }

    private fun metaPage(): Page {
        val page = Page(0, ByteArray(options.pageSize))
        page.init(Page.META)
        page.putI32(META_MAGIC_POS, META_MAGIC)
        page.putI32(META_MAGIC_POS + 4, options.pageSize)
        page.putI32(META_MAGIC_POS + 8, tree.root)
        page.putI32(META_MAGIC_POS + 12, pager.pageCount)
        page.putI32(META_MAGIC_POS + 16, tree.height)
        return page
    }

    private fun checkCellSize(key: ByteArray, value: ByteArray) {
        require(key.size + value.size <= maxCellPayload) {
            "B+tree 엔진은 key + value ≤ $maxCellPayload B만 저장합니다 (페이지 ${options.pageSize} B, overflow page 없음): ${key.size + value.size}"
        }
    }

    private fun checkOpen() {
        check(!closed) { "닫힌 엔진입니다: $dir" }
    }

    companion object {
        private const val DB_FILE = "data.db"
        private const val META_MAGIC = 0x42545231 // "BTR1"
        private const val META_MAGIC_POS = Page.HEADER_SIZE
        private val WAL_NAME = Regex("""^(\d{6,})\.wal$""")

        /**
         * 1. 잠금  2. 온전한 doublewrite가 있으면 다시 덮어쓰기(멱등), 아니면 버림
         * 3. meta 읽기 (새 DB면 빈 리프 루트 생성)  4. 새 WAL 생성  5. 옛 WAL 전부 재생
         * 6. 체크포인트 후 옛 WAL 삭제
         */
        fun open(options: BTreeOptions): BTreeEngine {
            val dir = options.dataDir
            Files.createDirectories(dir)
            val lock = DirectoryLock.acquire(dir)
            val counters = IoCounters()
            var pager: Pager? = null
            var wal: AppendOnlyFile? = null
            try {
                pager = Pager(dir.resolve(DB_FILE), options.pageSize, counters)
                recoverDoubleWrite(dir, pager, options.pageSize, counters)

                val pool = BufferPool(options.bufferPoolPages, pager)
                val tree = if (pager.fileSize == 0L) {
                    pager.pageCount = 1 // 0번은 meta
                    val root = pool.newPage(Page.LEAF)
                    pool.unpin(root)
                    BTree(pool, root.page.id, 1)
                } else {
                    // 설정과 다른 크기로 읽으면 체크섬부터 틀려 "손상"으로 보이므로, 기록된 페이지 크기를 먼저 확인한다
                    val prefix = Page(0, pager.readPrefix(Page.HEADER_SIZE + 8))
                    check(prefix.i32(META_MAGIC_POS) == META_MAGIC) { "B+tree 데이터 파일이 아닙니다: $dir" }
                    val pageSize = prefix.i32(META_MAGIC_POS + 4)
                    require(pageSize == options.pageSize) { "페이지 크기가 다릅니다: 파일 $pageSize, 설정 ${options.pageSize}" }
                    val meta = pager.read(0)
                    check(meta.type == Page.META) { "0번 페이지가 meta가 아닙니다: $dir" }
                    pager.pageCount = meta.i32(META_MAGIC_POS + 12)
                    BTree(pool, meta.i32(META_MAGIC_POS + 8), meta.i32(META_MAGIC_POS + 16))
                }

                val oldWals = listWals(dir)
                val walId = (oldWals.maxOfOrNull { it.first } ?: 0L) + 1
                wal = AppendOnlyFile.openForAppend(walPath(dir, walId), counters)
                Fsync.directory(dir)

                val engine = BTreeEngine(options, lock, counters, pager, pool, tree, wal, walId)
                engine.replaying = true
                oldWals.forEachIndexed { index, (_, path) ->
                    AppendOnlyFile.openForAppend(path, counters).use { file ->
                        replayWal(file, isLast = index == oldWals.lastIndex, options.recoveryMode, engine)
                    }
                }
                engine.checkpointInternal(carry = null)
                oldWals.forEach { (_, path) -> Files.deleteIfExists(path) }
                Fsync.directory(dir)
                engine.replaying = false
                return engine
            } catch (e: Exception) {
                wal?.close()
                pager?.close()
                lock.close()
                throw e
            }
        }

        private fun recoverDoubleWrite(dir: Path, pager: Pager, pageSize: Int, counters: IoCounters) {
            val path = dir.resolve(DoubleWrite.FILE_NAME)
            if (!Files.exists(path)) return
            val pages = DoubleWrite.readComplete(path, pageSize, counters)
            if (pages != null) {
                // 체크포인트가 commit된 뒤 제자리 쓰기 도중 죽었다: 이미지로 다시 덮어써 완료 상태로 만든다
                pages.forEach { (pageId, bytes) -> pager.writeRaw(pageId, bytes) }
                pager.sync()
            }
            Files.delete(path)
            Fsync.directory(dir)
        }

        private fun replayWal(file: AppendOnlyFile, isLast: Boolean, mode: RecoveryMode, engine: BTreeEngine) {
            for ((offset, result) in file.frames()) {
                when (result) {
                    is FrameReadResult.Ok -> engine.apply(LogRecordCodec.decode(result.payload))
                    is FrameReadResult.Corrupted -> {
                        if (!isLast || mode == RecoveryMode.STRICT) {
                            throw CorruptionException("WAL ${file.path} 의 $offset 에서 손상: ${result.reason}")
                        }
                        file.truncate(offset)
                        file.sync()
                    }
                    FrameReadResult.EndOfFile -> Unit
                }
            }
        }

        private fun walPath(dir: Path, id: Long): Path = dir.resolve("%06d.wal".format(id))

        private fun listWals(dir: Path): List<Pair<Long, Path>> =
            Files.list(dir).use { paths ->
                paths.toList()
                    .mapNotNull { path -> WAL_NAME.matchEntire(path.name)?.let { it.groupValues[1].toLong() to path } }
                    .sortedBy { it.first }
            }
    }
}
