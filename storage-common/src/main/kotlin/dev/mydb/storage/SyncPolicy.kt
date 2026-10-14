package dev.mydb.storage

enum class SyncPolicy {
    /** 쓰기마다 fsync. 쓰기가 반환되면 OS 크래시·전원 차단 후에도 남는다. */
    EVERY_WRITE,

    /** flush()를 부를 때만 fsync. 프로세스 크래시까지만 견딘다. */
    MANUAL,
}
