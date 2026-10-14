package dev.mydb.engine.lsm

import dev.mydb.storage.RecoveryMode
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

data class LsmOptions(
    val dataDir: Path,
    /** RocksDB 기본은 64 MiB. 학습 중 flush를 자주 보려고 작게 잡는다. */
    val memtableSizeBytes: Long = 1L * 1024 * 1024,
    val blockSizeBytes: Int = 4 * 1024,
    /** 0이면 bloom filter를 만들지 않는다 (측정 비교용). */
    val bloomBitsPerKey: Int = 10,
    val compactionMinThreshold: Int = 4,
    val compactionMaxThreshold: Int = 32,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
    /** 테스트 전용: flush·compaction 단계별 크래시 흉내. */
    val faultInjector: LsmFaultInjector = LsmFaultInjector.NONE,
) {
    init {
        require(memtableSizeBytes > 0) { "memtableSizeBytes는 0보다 커야 합니다" }
        require(blockSizeBytes > 0) { "blockSizeBytes는 0보다 커야 합니다" }
        require(bloomBitsPerKey >= 0) { "bloomBitsPerKey는 0 이상이어야 합니다" }
    }
}

fun interface LsmFaultInjector {
    fun reached(step: LsmStep)

    companion object {
        val NONE = LsmFaultInjector { }
    }
}

enum class LsmStep {
    /** flush 결과 SSTable을 썼지만 MANIFEST에 기록하기 전. */
    FLUSH_TABLE_WRITTEN,

    /** flush 편집을 MANIFEST에 sync했다. 이전 WAL은 아직 남아 있다. */
    FLUSH_COMMITTED,

    /** compaction 결과 SSTable을 썼지만 MANIFEST에 기록하기 전. */
    COMPACTION_TABLE_WRITTEN,

    /** compaction 편집을 MANIFEST에 sync했다. 입력 파일은 아직 남아 있다. */
    COMPACTION_COMMITTED,

    /** 더 이상 참조되지 않는 테이블 파일 하나를 지운 직후. */
    INPUT_DELETED,
}

data class CompactionResult(
    val inputFileIds: List<Long>,
    /** 결과가 비었으면 (모든 엔트리가 버려진 tombstone) null. */
    val outputFileId: Long?,
    val droppedTombstones: Boolean,
    val bytesBefore: Long,
    val bytesAfter: Long,
)
