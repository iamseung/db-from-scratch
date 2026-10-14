package dev.mydb.engine.lsm

import dev.mydb.engine.lsm.compaction.SizeTieredPicker
import dev.mydb.engine.lsm.manifest.Manifest
import dev.mydb.engine.lsm.manifest.ManifestEdit
import dev.mydb.engine.lsm.manifest.ManifestEdit.Change
import dev.mydb.engine.lsm.manifest.Version
import dev.mydb.engine.lsm.sstable.SSTableReader
import dev.mydb.engine.lsm.sstable.SSTableWriter
import dev.mydb.engine.lsm.sstable.TableMeta
import dev.mydb.kv.ByteKeys
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

/**
 * LSM-tree KV 엔진.
 *
 * 쓰기: WAL append → memtable 반영 → memtable이 가득 차면 SSTable로 flush → 조건이 맞으면 compaction.
 * 읽기: memtable → SSTable(age 내림차순). 같은 키는 먼저 만난 쪽이 최신이다.
 * 파일 집합의 변경(flush, compaction)은 MANIFEST 편집 한 건으로 원자적이다.
 *
 * flush·compaction은 쓰기 스레드가 직접 수행하고, public 메서드는 @Synchronized다.
 */
class LsmEngine private constructor(
    private val options: LsmOptions,
    private val lock: DirectoryLock,
    private val counters: IoCounters,
    private val manifest: Manifest,
    private val readers: MutableMap<Long, SSTableReader>,
    private var current: Version,
    private var wal: AppendOnlyFile,
    private var walId: Long,
) : KvEngine {
    private val dir = options.dataDir
    private var memtable = MemTable()
    private val picker = SizeTieredPicker(options.compactionMinThreshold, options.compactionMaxThreshold)

    /** 참조가 남은 Version들. 여기 없는 테이블 파일만 지울 수 있다. */
    private val liveVersions = mutableSetOf(current)

    /** MANIFEST에서 빠졌지만 아직 어떤 Version이 참조할 수 있는 파일. */
    private val obsoleteFiles = mutableSetOf<Long>()

    private var userBytesWritten = 0L
    private var closed = false

    @Synchronized
    override fun get(key: ByteArray): ByteArray? {
        checkOpen()
        memtable.get(key)?.let { return (it as? MemValue.Put)?.value?.copyOf() }
        for (table in current.tables) {
            if (ByteKeys.compare(key, table.minKey) < 0 || ByteKeys.compare(key, table.maxKey) > 0) continue
            when (val value = reader(table).get(key)) {
                is MemValue.Put -> return value.value
                MemValue.Tombstone -> return null
                null -> Unit
            }
        }
        return null
    }

    @Synchronized
    override fun put(key: ByteArray, value: ByteArray) {
        checkOpen()
        write(LogRecord.Put(key, value))
        userBytesWritten += key.size + value.size
    }

    /** 없는 키도 tombstone을 쓴다. 존재를 확인하려면 디스크를 읽어야 해서, 확인 없이 쓰는 편이 싸다. */
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
                is WriteOp.Put -> LogRecord.Put(op.key, op.value)
                is WriteOp.Delete -> LogRecord.Delete(op.key)
            }
        }
        write(LogRecord.Batch(records))
        userBytesWritten += ops.sumOf { op ->
            when (op) {
                is WriteOp.Put -> (op.key.size + op.value.size).toLong()
                is WriteOp.Delete -> op.key.size.toLong()
            }
        }
    }

    /**
     * 생성 시점의 memtable 범위를 복사하고 current Version을 붙잡는다.
     * close()를 불러야 Version이 풀려 compaction 입력 파일을 지울 수 있다.
     */
    @Synchronized
    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        checkOpen()
        val version = current
        version.retain()
        val sources = ArrayList<Iterator<Entry>>()
        sources += memtable.rangeSnapshot(from, to).iterator()
        for (table in version.tables) {
            if (to != null && ByteKeys.compare(table.minKey, to) >= 0) continue
            if (ByteKeys.compare(table.maxKey, from) < 0) continue
            sources += reader(table).iterator(from, to)
        }
        val merged = MergingIterator(sources, dropTombstones = true)
        return object : KvIterator {
            private var released = false

            override fun hasNext() = synchronized(this@LsmEngine) { merged.hasNext() }

            override fun next(): KvEntry = synchronized(this@LsmEngine) {
                val entry = merged.next()
                KvEntry(entry.key, (entry.value as MemValue.Put).value)
            }

            override fun close() = synchronized(this@LsmEngine) {
                if (released) return@synchronized
                released = true
                releaseVersion(version)
            }
        }
    }

    @Synchronized
    override fun flush() {
        checkOpen()
        wal.sync()
    }

    /** 크기가 비슷하고 나이가 연속인 테이블들을 합친다. 고를 구간이 없으면 null. */
    @Synchronized
    fun compact(): CompactionResult? {
        checkOpen()
        val pick = picker.pick(current.tables) ?: return null
        return runCompaction(pick.tables, dropTombstones = pick.includesOldest)
    }

    /** memtable을 flush하고 모든 테이블을 하나로 합친다. 가장 오래된 테이블을 포함하므로 tombstone을 모두 버린다. */
    @Synchronized
    fun compactAll(): CompactionResult? {
        checkOpen()
        flushMemtable()
        if (current.tables.isEmpty()) return null
        return runCompaction(current.tables, dropTombstones = true)
    }

    /** 테스트·측정용: 현재 살아 있는 테이블 수. */
    @Synchronized
    fun tableCount(): Int = current.tables.size

    @Synchronized
    override fun stats(): EngineStats = EngineStats(
        userBytesWritten = userBytesWritten,
        diskBytesWritten = counters.bytesWritten.get(),
        diskBytesRead = counters.bytesRead.get(),
        diskBytesUsed = current.tables.sumOf { it.fileSize } + wal.size + manifest.size,
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        wal.close()
        readers.values.forEach { it.close() }
        manifest.close()
        lock.close()
    }

    private fun write(record: LogRecord) {
        wal.append(LogRecordCodec.encode(record))
        if (options.syncPolicy == SyncPolicy.EVERY_WRITE) wal.sync()
        memtable.apply(record)
        if (memtable.approximateBytes >= options.memtableSizeBytes) {
            flushMemtable()
            compact()
        }
    }

    /**
     * 1. 새 WAL 생성  2. memtable → SSTable  3. MANIFEST { 테이블 추가, LOG_NUMBER = 새 WAL } ← commit point
     * 4. 이전 WAL 삭제, 새 Version 설치
     */
    private fun flushMemtable() {
        if (memtable.isEmpty()) return
        val newWalId = allocateFileId()
        val newWal = AppendOnlyFile.openForAppend(FileNames.wal(dir, newWalId), counters)
        Fsync.directory(dir)

        val meta = writeTable(memtable.entries(), age = manifest.state.nextAge)
        options.faultInjector.reached(LsmStep.FLUSH_TABLE_WRITTEN)
        val changes = ArrayList<Change>()
        meta?.let { changes += Change.AddTable(it) }
        changes += Change.NextAge(manifest.state.nextAge + 1)
        changes += Change.LogNumber(newWalId)
        changes += Change.NextFileId(nextFileId)
        manifest.log(ManifestEdit(changes))
        options.faultInjector.reached(LsmStep.FLUSH_COMMITTED)

        wal.close()
        Files.deleteIfExists(FileNames.wal(dir, walId))
        wal = newWal
        walId = newWalId
        memtable = MemTable()
        if (meta != null) installVersion(current.tables + meta, removed = emptyList())
    }

    private fun runCompaction(inputs: List<TableMeta>, dropTombstones: Boolean): CompactionResult {
        val byAgeDesc = inputs.sortedByDescending { it.age }
        val bytesBefore = inputs.sumOf { it.fileSize }
        val merged = MergingIterator(byAgeDesc.map { reader(it).iterator(ByteArray(0), null) }, dropTombstones)
        val output = writeTable(merged, age = byAgeDesc.first().age)
        options.faultInjector.reached(LsmStep.COMPACTION_TABLE_WRITTEN)

        val changes = ArrayList<Change>()
        output?.let { changes += Change.AddTable(it) }
        inputs.forEach { changes += Change.RemoveTable(it.fileId) }
        changes += Change.NextFileId(nextFileId)
        manifest.log(ManifestEdit(changes))
        options.faultInjector.reached(LsmStep.COMPACTION_COMMITTED)

        val remaining = current.tables.filter { table -> inputs.none { it.fileId == table.fileId } }
        installVersion(remaining + listOfNotNull(output), removed = inputs.map { it.fileId })
        return CompactionResult(inputs.map { it.fileId }, output?.fileId, dropTombstones, bytesBefore, output?.fileSize ?: 0)
    }

    private fun writeTable(entries: Iterator<Entry>, age: Long): TableMeta? {
        val fileId = allocateFileId()
        val writer = SSTableWriter(FileNames.sst(dir, fileId), fileId, age, counters, options.blockSizeBytes, options.bloomBitsPerKey)
        try {
            entries.forEach { writer.add(it) }
        } catch (e: Exception) {
            writer.abort()
            throw e
        }
        val meta = writer.finish() ?: return null
        readers[fileId] = SSTableReader.open(FileNames.sst(dir, fileId), counters)
        return meta
    }

    private fun installVersion(tables: List<TableMeta>, removed: List<Long>) {
        val old = current
        current = Version(tables)
        liveVersions += current
        obsoleteFiles += removed
        releaseVersion(old)
    }

    private fun releaseVersion(version: Version) {
        if (!version.release()) return
        liveVersions -= version
        deleteObsoleteFiles()
    }

    /** 어떤 살아 있는 Version도 참조하지 않는 파일만 지운다. */
    private fun deleteObsoleteFiles() {
        val referenced = liveVersions.flatMap { v -> v.tables.map { it.fileId } }.toSet()
        val deletable = obsoleteFiles.filter { it !in referenced }
        for (fileId in deletable) {
            readers.remove(fileId)?.close()
            Files.deleteIfExists(FileNames.sst(dir, fileId))
            obsoleteFiles -= fileId
            options.faultInjector.reached(LsmStep.INPUT_DELETED)
        }
    }

    private val nextFileId: Long
        get() = manifest.state.nextFileId

    /** 다음 편집에 NextFileId가 기록되기 전이라도, 재시작 시 디스크의 최대 번호보다 크게 시작하므로 겹치지 않는다. */
    private fun allocateFileId(): Long {
        val id = manifest.state.nextFileId
        manifest.state.nextFileId = id + 1
        return id
    }

    private fun reader(table: TableMeta): SSTableReader =
        readers[table.fileId] ?: throw CorruptionException("열린 테이블이 없습니다: $table")

    private fun checkOpen() {
        check(!closed) { "닫힌 엔진입니다: $dir" }
    }

    companion object {
        /**
         * 1. 잠금  2. MANIFEST 재생  3. 고아 파일 정리 (.tmp, MANIFEST가 모르는 .sst, LOG_NUMBER 미만 .wal)
         * 4. 테이블 열기  5. 남은 WAL을 memtable로 재생  6. 비어 있지 않으면 flush  7. 새 WAL로 시작
         */
        fun open(options: LsmOptions): LsmEngine {
            val dir = options.dataDir
            Files.createDirectories(dir)
            val lock = DirectoryLock.acquire(dir)
            val counters = IoCounters()
            val readers = HashMap<Long, SSTableReader>()
            var manifest: Manifest? = null
            try {
                manifest = Manifest.open(dir, counters, options.recoveryMode)
                val state = manifest.state

                FileNames.listTmp(dir).forEach { Files.delete(it) }
                FileNames.listTables(dir).filter { (id, _) -> id !in state.tables }.forEach { (_, path) -> Files.delete(path) }
                val wals = FileNames.listWals(dir)
                wals.filter { (id, _) -> id < state.logNumber }.forEach { (_, path) -> Files.delete(path) }
                // MANIFEST에 NextFileId가 기록되기 전에 죽었어도 디스크에 있는 번호와 겹치지 않게 한다
                val maxOnDisk = (wals.map { it.first } + state.tables.keys).maxOrNull() ?: 0L
                state.nextFileId = maxOf(state.nextFileId, maxOnDisk + 1)

                for (meta in state.tables.values) {
                    val reader = SSTableReader.open(FileNames.sst(dir, meta.fileId), counters)
                    readers[meta.fileId] = reader
                    if (reader.footer.age != meta.age) {
                        throw CorruptionException("테이블 ${meta.fileId}의 age가 MANIFEST(${meta.age})와 다릅니다: ${reader.footer.age}")
                    }
                }

                val replayed = MemTable()
                val liveWals = wals.filter { (id, _) -> id >= state.logNumber }
                liveWals.forEachIndexed { index, (_, path) ->
                    AppendOnlyFile.openForAppend(path, counters).use { file ->
                        replayWal(file, isLast = index == liveWals.lastIndex, options.recoveryMode, replayed)
                    }
                }

                val walId = state.nextFileId
                state.nextFileId = walId + 1
                val wal = AppendOnlyFile.openForAppend(FileNames.wal(dir, walId), counters)
                Fsync.directory(dir)
                val engine = LsmEngine(options, lock, counters, manifest, readers, Version(state.tables.values), wal, walId)
                engine.finishRecovery(replayed, liveWals.map { it.first })
                return engine
            } catch (e: Exception) {
                readers.values.forEach { it.close() }
                manifest?.close()
                lock.close()
                throw e
            }
        }

        private fun replayWal(file: AppendOnlyFile, isLast: Boolean, mode: RecoveryMode, memtable: MemTable) {
            for ((offset, result) in file.frames()) {
                when (result) {
                    is FrameReadResult.Ok -> memtable.apply(LogRecordCodec.decode(result.payload))
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
    }

    /**
     * 재생한 WAL 내용을 SSTable로 내리고 LOG_NUMBER를 새 WAL로 옮긴 뒤 옛 WAL을 지운다.
     * WAL 하나 = memtable 하나가 항상 성립하도록 이어 쓰지 않는다.
     */
    private fun finishRecovery(replayed: MemTable, oldWalIds: List<Long>) {
        val meta = if (replayed.isEmpty()) null else writeTable(replayed.entries(), age = manifest.state.nextAge)
        val changes = ArrayList<Change>()
        if (meta != null) {
            changes += Change.AddTable(meta)
            changes += Change.NextAge(manifest.state.nextAge + 1)
        }
        changes += Change.LogNumber(walId)
        changes += Change.NextFileId(nextFileId)
        manifest.log(ManifestEdit(changes))
        oldWalIds.forEach { Files.deleteIfExists(FileNames.wal(dir, it)) }
        if (meta != null) installVersion(current.tables + meta, removed = emptyList())
    }
}
