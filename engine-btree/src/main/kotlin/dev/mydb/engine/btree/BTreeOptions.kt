package dev.mydb.engine.btree

import dev.mydb.storage.RecoveryMode
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

data class BTreeOptions(
    val dataDir: Path,
    /** 512 ~ 32768의 2의 거듭제곱. 헤더의 u16 필드에 페이지 안 오프셋이 들어가야 한다. */
    val pageSize: Int = 4096,
    /** 4 KiB × 1024 = 4 MiB. 체크포인트 사이에 바뀔 수 있는 페이지 수의 상한이기도 하다 (no-steal). */
    val bufferPoolPages: Int = 1024,
    /** WAL이 이만큼 쌓이면 체크포인트한다. 재시작 시 재생할 양의 상한. */
    val checkpointWalBytes: Long = 16L * 1024 * 1024,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
    /** 테스트 전용: 체크포인트 단계별 크래시 흉내. */
    val faultInjector: BTreeFaultInjector = BTreeFaultInjector.NONE,
) {
    init {
        require(pageSize in 512..32768 && pageSize and (pageSize - 1) == 0) { "pageSize는 512~32768의 2의 거듭제곱이어야 합니다: $pageSize" }
        require(bufferPoolPages >= 16) { "bufferPoolPages는 16 이상이어야 합니다: $bufferPoolPages" }
        require(checkpointWalBytes > 0) { "checkpointWalBytes는 0보다 커야 합니다" }
    }
}

fun interface BTreeFaultInjector {
    fun reached(step: BTreeStep)

    companion object {
        val NONE = BTreeFaultInjector { }
    }
}

enum class BTreeStep {
    /** doublewrite를 sync했다. 이 지점부터 체크포인트는 commit된 것이다. */
    DW_SYNCED,

    /** 페이지 한 장을 제자리에 쓴 직후. 페이지마다 호출된다. */
    PAGE_WRITTEN,

    /** doublewrite·옛 WAL 삭제까지 끝났다. */
    CHECKPOINT_DONE,
}
