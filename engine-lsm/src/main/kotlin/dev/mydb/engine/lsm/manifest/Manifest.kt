package dev.mydb.engine.lsm.manifest

import dev.mydb.engine.lsm.sstable.TableMeta
import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.Fsync
import dev.mydb.storage.IoCounters
import dev.mydb.storage.RecoveryMode
import java.nio.file.Files
import java.nio.file.Path

/** MANIFEST를 재생한 결과. */
class ManifestState {
    val tables = LinkedHashMap<Long, TableMeta>()
    var logNumber = 0L
    var nextFileId = 1L
    var nextAge = 1L

    fun apply(edit: ManifestEdit) {
        for (change in edit.changes) {
            when (change) {
                is ManifestEdit.Change.AddTable -> tables[change.meta.fileId] = change.meta
                is ManifestEdit.Change.RemoveTable -> tables.remove(change.fileId)
                is ManifestEdit.Change.LogNumber -> logNumber = change.walFileId
                is ManifestEdit.Change.NextFileId -> nextFileId = maxOf(nextFileId, change.fileId)
                is ManifestEdit.Change.NextAge -> nextAge = maxOf(nextAge, change.age)
            }
        }
    }
}

/**
 * 편집 로그. 편집 한 건 = 프레임 하나 = commit point 하나.
 * 엔진의 SyncPolicy와 무관하게 편집마다 sync한다. sync되지 않은 편집은 commit되지 않은 것이다.
 */
class Manifest private constructor(private val file: AppendOnlyFile, val state: ManifestState) : AutoCloseable {

    fun log(edit: ManifestEdit) {
        file.append(edit.encode())
        file.sync()
        state.apply(edit)
    }

    val size: Long
        get() = file.size

    override fun close() = file.close()

    companion object {
        const val FILE_NAME = "MANIFEST"

        fun open(dir: Path, counters: IoCounters, mode: RecoveryMode): Manifest {
            val path = dir.resolve(FILE_NAME)
            val isNew = !Files.exists(path)
            val file = AppendOnlyFile.openForAppend(path, counters)
            if (isNew) Fsync.directory(dir)
            val state = ManifestState()
            try {
                for ((offset, result) in file.frames()) {
                    when (result) {
                        is FrameReadResult.Ok -> state.apply(ManifestEdit.decode(result.payload))
                        is FrameReadResult.Corrupted -> {
                            // sync 전에 죽은 마지막 편집 = commit되지 않은 변경
                            if (mode == RecoveryMode.STRICT) throw CorruptionException("MANIFEST $offset 에서 손상: ${result.reason}")
                            file.truncate(offset)
                            file.sync()
                        }
                        FrameReadResult.EndOfFile -> Unit
                    }
                }
            } catch (e: Exception) {
                file.close()
                throw e
            }
            return Manifest(file, state)
        }
    }
}
