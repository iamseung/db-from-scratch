package dev.mydb.storage

import java.util.concurrent.atomic.AtomicLong

/** 엔진이 공유하는 디스크 I/O 누적값. 쓰기 증폭 등 EngineStats의 재료가 된다. */
class IoCounters {
    /** 프레임 헤더를 포함해 디스크에 실제로 쓴 바이트. */
    val bytesWritten = AtomicLong()
    val bytesRead = AtomicLong()
    val syncCount = AtomicLong()

    /** 복구 중 손상된 꼬리를 잘라 버린 바이트. */
    val truncatedBytes = AtomicLong()
}
