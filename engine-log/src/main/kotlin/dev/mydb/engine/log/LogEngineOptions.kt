package dev.mydb.engine.log

import dev.mydb.storage.RecoveryMode
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

data class LogEngineOptions(
    val dataDir: Path,
    /** 학습 중 rollover를 자주 보려고 작게 잡는다. 실제 Bitcask 기본은 2 GiB. */
    val segmentSizeBytes: Long = 4L * 1024 * 1024,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
) {
    init {
        require(segmentSizeBytes > 0) { "segmentSizeBytes는 0보다 커야 합니다: $segmentSizeBytes" }
    }
}
