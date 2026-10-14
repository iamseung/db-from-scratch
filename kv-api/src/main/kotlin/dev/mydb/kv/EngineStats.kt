package dev.mydb.kv

/**
 * 비교 실험(M4, M8)용 카운터.
 * 쓰기 증폭 = diskBytesWritten / userBytesWritten
 */
data class EngineStats(
    val userBytesWritten: Long,
    val diskBytesWritten: Long,
    val diskBytesRead: Long,
    val diskBytesUsed: Long,
) {
    val writeAmplification: Double
        get() = if (userBytesWritten == 0L) 0.0 else diskBytesWritten.toDouble() / userBytesWritten
}
