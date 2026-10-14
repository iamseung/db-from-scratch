package dev.mydb.engine.log

import dev.mydb.storage.AppendOnlyFile
import dev.mydb.storage.FrameReadResult
import dev.mydb.storage.Fsync
import dev.mydb.storage.IoCounters
import dev.mydb.storage.LogRecord
import dev.mydb.storage.LogRecordCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * merge의 파일 단계. 엔진의 merge와 재시작 복구가 같은 코드를 쓴다.
 *
 * commit point는 merge 파일 끝의 MERGE_FOOTER다. footer가 온전하면 merge는 일어난 일이고,
 * 없거나 깨졌으면 없던 일이다. 아래 함수들은 멱등이라 어느 지점에서 죽어도 다시 실행하면 같은 상태로 수렴한다.
 */
internal object Merger {
    /** 모든 프레임이 온전하고, 마지막이 PUT 개수와 일치하는 footer인지 확인한다. */
    fun isComplete(path: Path, counters: IoCounters): Boolean =
        AppendOnlyFile.openReadOnly(path, counters).use { file ->
            var puts = 0L
            var footer: LogRecord.MergeFooter? = null
            for ((_, result) in file.frames()) {
                if (result !is FrameReadResult.Ok || footer != null) return false
                when (val record = LogRecordCodec.decode(result.payload)) {
                    is LogRecord.Put -> puts++
                    is LogRecord.MergeFooter -> footer = record
                    else -> return false
                }
            }
            footer?.entryCount == puts
        }

    /**
     * 확정된 merge 파일을 설치한다: id ≤ [mergedId]인 세그먼트를 지우고 merge 파일을 [mergedId] 세그먼트로 바꾼다.
     * 활성 세그먼트 id는 [mergedId]보다 크므로 "id 순서 = 시간 순서" 불변식이 유지된다.
     */
    fun install(dir: Path, mergedId: Int, fault: FaultInjector) {
        SegmentFiles.listLogs(dir)
            .filter { (id, _) -> id <= mergedId }
            .forEach { (_, path) ->
                Files.delete(path)
                fault.reached(MergeStep.OLD_LOG_DELETED)
            }
        Files.move(SegmentFiles.mergePath(dir, mergedId), SegmentFiles.logPath(dir, mergedId), ATOMIC_MOVE)
        fault.reached(MergeStep.RENAMED)
        Fsync.directory(dir)
    }

    /** 시작 시 남아 있는 merge 파일을 처리한다. 세그먼트 재생보다 먼저 불러야 한다. */
    fun recover(dir: Path, counters: IoCounters) {
        for ((mergedId, path) in SegmentFiles.listMerges(dir)) {
            if (isComplete(path, counters)) {
                install(dir, mergedId, FaultInjector.NONE)
            } else {
                Files.delete(path)
                Fsync.directory(dir)
            }
        }
    }
}
