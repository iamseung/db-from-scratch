package dev.mydb.engine.log

import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp
import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.BatchableRecord
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.DirectoryLock
import dev.mydb.storage.FrameCodec
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.Fsync
import dev.mydb.storage.IoCounters
import dev.mydb.storage.LogRecord
import dev.mydb.storage.LogRecordCodec
import dev.mydb.storage.RecoveryMode
import dev.mydb.storage.SyncPolicy
import java.nio.file.Files
import java.util.TreeMap

/**
 * Bitcask형 로그 KV.
 * 모든 쓰기를 활성 세그먼트 끝에 추가하고, [Keydir]가 키마다 최신 값의 위치를 기억한다.
 *
 * 쓰기 순서는 항상 "디스크 먼저, 메모리 나중"이다. append가 실패했는데 keydir가 먼저 바뀌면
 * 메모리가 존재하지 않는 데이터를 가리키게 된다.
 *
 * 단일 쓰기 스레드 결정(M7 전까지)을 public 메서드의 @Synchronized로 강제한다.
 */
class LogEngine private constructor(
    private val options: LogEngineOptions,
    private val lock: DirectoryLock,
    private val counters: IoCounters,
    /** 불변 세그먼트와 활성 세그먼트 전부. 활성은 항상 가장 큰 id다. */
    private val segments: TreeMap<Int, AppendOnlyFile>,
    private val keydir: Keydir,
) : KvEngine {
    private var activeId: Int = segments.lastKey()
    private val active: AppendOnlyFile
        get() = segments.getValue(activeId)

    private var userBytesWritten = 0L
    private var closed = false

    @Synchronized
    override fun get(key: ByteArray): ByteArray? {
        checkOpen()
        return keydir.get(key)?.let(::readValue)
    }

    @Synchronized
    override fun put(key: ByteArray, value: ByteArray) {
        checkOpen()
        val record = LogRecord.Put(key, value)
        keydir.apply(record, append(record))
        userBytesWritten += key.size + value.size
    }

    @Synchronized
    override fun delete(key: ByteArray) {
        checkOpen()
        // 없는 키의 tombstone은 되살아날 PUT이 없으므로 쓸 필요가 없다
        if (keydir.get(key) == null) return
        val record = LogRecord.Delete(key)
        keydir.apply(record, append(record))
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
        val batch = LogRecord.Batch(records)
        keydir.apply(batch, append(batch))
        userBytesWritten += records.sumOf { it.userBytes() }
    }

    /** 반복자는 생성 시점의 키와 위치를 고정한다. 이후 쓰기는 보이지 않는다. */
    @Synchronized
    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        checkOpen()
        val snapshot = keydir.rangeSnapshot(from, to).iterator()
        return object : KvIterator {
            override fun hasNext() = snapshot.hasNext()

            override fun next(): KvEntry {
                val (key, location) = snapshot.next()
                return KvEntry(key, synchronized(this@LogEngine) { readValue(location) })
            }

            override fun close() = Unit
        }
    }

    /**
     * 활성 세그먼트를 뺀 모든 불변 세그먼트를 살아 있는 값만 담은 세그먼트 하나로 합친다.
     *
     * 대상이 활성 세그먼트를 제외한 전부이므로 tombstone을 버려도 된다. 출력보다 오래된 세그먼트가 남지 않아
     * 지워진 키의 옛 PUT도 함께 사라진다. (일부 세그먼트만 병합하면 이 논리가 깨진다 → M2 compaction)
     */
    @Synchronized
    fun merge(): MergeResult {
        checkOpen()
        val targets = segments.headMap(activeId).keys.toList()
        if (targets.isEmpty()) return MergeResult(emptyList(), 0, 0, 0)
        val mergedId = targets.last()
        val bytesBefore = targets.sumOf { segments.getValue(it).size }
        val fault = options.faultInjector

        val live = keydir.entriesUpTo(mergedId)
        val relocations = ArrayList<Triple<ByteArray, Location, Location>>(live.size)
        val mergePath = SegmentFiles.mergePath(options.dataDir, mergedId)
        // 같은 프로세스에서 앞선 merge가 실패하고 남긴 파일일 수 있다. 확정 전이므로 버려도 된다.
        Files.deleteIfExists(mergePath)
        AppendOnlyFile.openForAppend(mergePath, counters).use { out ->
            for ((key, old) in live) {
                val payload = LogRecordCodec.encode(LogRecord.Put(key, readValue(old)))
                val offset = out.append(payload)
                relocations += Triple(key, old, Location(mergedId, offset, FrameCodec.frameSize(payload.size)))
                fault.reached(MergeStep.ENTRY_WRITTEN)
            }
            out.append(LogRecordCodec.encode(LogRecord.MergeFooter(live.size.toLong())))
            fault.reached(MergeStep.FOOTER_WRITTEN)
            out.sync()
        }
        Fsync.directory(options.dataDir)
        fault.reached(MergeStep.COMMITTED)

        targets.forEach { segments.remove(it)?.close() }
        Merger.install(options.dataDir, mergedId, fault)
        val merged = AppendOnlyFile.openReadOnly(SegmentFiles.logPath(options.dataDir, mergedId), counters)
        segments[mergedId] = merged

        // 단일 스레드라 지금은 항상 참이지만, 백그라운드 merge로 바꾸면 merge 중 덮어쓴 키를 되돌리지 않게 해 준다
        for ((key, old, new) in relocations) {
            if (keydir.get(key) == old) keydir.put(key, new)
        }
        return MergeResult(targets, live.size, bytesBefore, merged.size)
    }

    @Synchronized
    override fun flush() {
        checkOpen()
        active.sync()
    }

    @Synchronized
    override fun stats(): EngineStats = EngineStats(
        userBytesWritten = userBytesWritten,
        diskBytesWritten = counters.bytesWritten.get(),
        diskBytesRead = counters.bytesRead.get(),
        diskBytesUsed = segments.values.sumOf { it.size },
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        segments.values.forEach { it.close() }
        lock.close()
    }

    private fun append(record: LogRecord): Location {
        val payload = LogRecordCodec.encode(record)
        val frameSize = FrameCodec.frameSize(payload.size)
        rolloverIfNeeded(frameSize)
        val offset = active.append(payload)
        if (options.syncPolicy == SyncPolicy.EVERY_WRITE) active.sync()
        return Location(activeId, offset, frameSize)
    }

    /** 세그먼트 크기보다 큰 레코드는 빈 세그먼트에 혼자 들어간다. */
    private fun rolloverIfNeeded(nextFrameSize: Int) {
        if (active.size == 0L || active.size + nextFrameSize <= options.segmentSizeBytes) return

        active.sync()
        active.close()
        segments[activeId] = AppendOnlyFile.openReadOnly(SegmentFiles.logPath(options.dataDir, activeId), counters)

        activeId += 1
        segments[activeId] = AppendOnlyFile.openForAppend(SegmentFiles.logPath(options.dataDir, activeId), counters)
        Fsync.directory(options.dataDir)
    }

    private fun readValue(location: Location): ByteArray {
        val segment = segments[location.segmentId]
            ?: throw CorruptionException("keydir가 없는 세그먼트를 가리킵니다: $location")
        val result = segment.readFrame(location.frameOffset)
        if (result !is FrameReadResult.Ok) {
            throw CorruptionException("keydir가 가리키는 프레임을 읽을 수 없습니다: $location → $result")
        }
        val record = LogRecordCodec.decode(result.payload)
        val target = if (location.indexInBatch < 0) record else (record as? LogRecord.Batch)?.ops?.getOrNull(location.indexInBatch)
        return (target as? LogRecord.Put)?.value
            ?: throw CorruptionException("keydir 위치에 PUT 레코드가 없습니다: $location → $target")
    }

    private fun BatchableRecord.userBytes(): Long = when (this) {
        is LogRecord.Put -> (key.size + value.size).toLong()
        is LogRecord.Delete -> key.size.toLong()
    }

    private fun checkOpen() {
        check(!closed) { "닫힌 엔진입니다: ${options.dataDir}" }
    }

    companion object {
        /**
         * 디렉터리를 잠그고, 세그먼트를 id 순서대로 재생해 keydir를 다시 만든다.
         * 마지막 세그먼트를 활성 세그먼트로 이어서 쓴다.
         */
        fun open(options: LogEngineOptions): LogEngine {
            Files.createDirectories(options.dataDir)
            val lock = DirectoryLock.acquire(options.dataDir)
            val counters = IoCounters()
            val segments = TreeMap<Int, AppendOnlyFile>()
            try {
                Merger.recover(options.dataDir, counters)
                val keydir = Keydir()
                val logs = SegmentFiles.listLogs(options.dataDir)
                logs.forEachIndexed { index, (id, path) ->
                    val isLast = index == logs.lastIndex
                    val file = if (isLast) AppendOnlyFile.openForAppend(path, counters) else AppendOnlyFile.openReadOnly(path, counters)
                    segments[id] = file
                    replay(id, file, isLast, options.recoveryMode, keydir)
                }
                if (segments.isEmpty()) {
                    segments[1] = AppendOnlyFile.openForAppend(SegmentFiles.logPath(options.dataDir, 1), counters)
                    Fsync.directory(options.dataDir)
                }
                return LogEngine(options, lock, counters, segments, keydir)
            } catch (e: Exception) {
                segments.values.forEach { it.close() }
                lock.close()
                throw e
            }
        }

        private fun replay(id: Int, file: AppendOnlyFile, isLast: Boolean, mode: RecoveryMode, keydir: Keydir) {
            for ((offset, result) in file.frames()) {
                when (result) {
                    is FrameReadResult.Ok -> {
                        val location = Location(id, offset, FrameCodec.frameSize(result.payload.size))
                        keydir.apply(LogRecordCodec.decode(result.payload), location)
                    }
                    is FrameReadResult.Corrupted -> {
                        // 불변 세그먼트는 sync 후 닫혔으므로 잘린 쓰기로 손상될 수 없다
                        if (!isLast || mode == RecoveryMode.STRICT) {
                            throw CorruptionException("세그먼트 ${file.path} 의 $offset 에서 손상: ${result.reason}")
                        }
                        file.truncate(offset)
                        file.sync()
                    }
                    FrameReadResult.EndOfFile -> Unit
                }
            }
        }
    }
}
