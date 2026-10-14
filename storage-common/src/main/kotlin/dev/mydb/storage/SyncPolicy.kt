package dev.mydb.storage

enum class SyncPolicy {
    /** 쓰기마다 fsync. 쓰기가 반환되면 OS 크래시 후에도 남는다 (macOS는 디스크 캐시 제외). */
    EVERY_WRITE,

    /** flush()를 부를 때만 fsync. 프로세스 크래시까지만 견딘다. */
    MANUAL,
}
