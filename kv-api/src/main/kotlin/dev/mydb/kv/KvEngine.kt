package dev.mydb.kv

/**
 * 모든 저장 엔진(log, LSM, B+tree)이 구현하는 공통 경계.
 * 키는 부호 없는 바이트 사전순으로 정렬된다 ([ByteKeys.compare]).
 */
interface KvEngine : AutoCloseable {
    fun get(key: ByteArray): ByteArray?

    fun put(key: ByteArray, value: ByteArray)

    fun delete(key: ByteArray)

    /** [from, to) 범위를 키 오름차순으로 순회한다. to가 null이면 끝까지. */
    fun scan(from: ByteArray, to: ByteArray?): KvIterator

    /** 여러 쓰기를 원자적으로 적용한다. */
    fun writeBatch(ops: List<WriteOp>)

    /** 이 호출이 반환되면 그 전까지의 쓰기는 크래시 후에도 남아 있어야 한다. */
    fun flush()

    fun stats(): EngineStats
}
