package dev.mydb.kv.crash

/** 부모와 자식이 같은 키·값 규칙을 공유한다. 키는 0 채움이라 바이트 순서 = 번호 순서다. */
object CrashWorkload {
    const val BATCH_SIZE = 3
    const val MAINTENANCE_EVERY = 20L

    /** 자식이 유지 작업 직전에 출력하는 신호. 부모는 이것을 받는 즉시 죽여 유지 작업 구간을 노린다. */
    const val MAINTENANCE_SIGNAL = "MAINTENANCE"

    fun key(n: Long): ByteArray = "key-%010d".format(n).toByteArray()

    fun value(n: Long): ByteArray = "value-$n-".padEnd(100, '#').toByteArray()

    fun batchKeys(batch: Long): List<Long> = (0 until BATCH_SIZE).map { batch * BATCH_SIZE + it }

    fun indexOf(key: ByteArray): Long = String(key).removePrefix("key-").toLong()
}

enum class CrashMode {
    /** put 한 건마다 ACK. */
    SINGLE,

    /** 키 BATCH_SIZE개짜리 writeBatch마다 ACK. */
    BATCH,

    /** put 하다가 MAINTENANCE_EVERY건마다 신호를 출력하고 유지 작업을 돌린다. */
    MAINTENANCE,
}
