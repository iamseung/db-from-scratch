package dev.mydb.txn

import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp

/**
 * 트랜잭션을 KvEngine처럼 보이게 하는 어댑터. SQL 실행기와 문서 계층은 이것과 저장 엔진을 구분하지 않는다.
 * 읽기는 트랜잭션의 스냅샷 + 자기 쓰기, 쓰기는 커밋 전까지 트랜잭션 안에 버퍼링된다.
 */
class TxnKvView(private val txn: Txn) : KvEngine {
    override fun get(key: ByteArray): ByteArray? = txn.get(key)

    override fun put(key: ByteArray, value: ByteArray) = txn.put(key, value)

    override fun delete(key: ByteArray) = txn.delete(key)

    override fun writeBatch(ops: List<WriteOp>) = ops.forEach { op ->
        when (op) {
            is WriteOp.Put -> txn.put(op.key, op.value)
            is WriteOp.Delete -> txn.delete(op.key)
        }
    }

    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        val entries = txn.scan(from, to).iterator()
        return object : KvIterator {
            override fun hasNext() = entries.hasNext()

            override fun next() = entries.next().let { (k, v) -> KvEntry(k, v) }

            override fun close() = Unit
        }
    }

    /** 커밋 때 이 키가 트랜잭션 시작 이후 바뀌지 않았는지 검증한다 ([Txn.watch]). */
    fun watch(key: ByteArray) = txn.watch(key)

    /** 커밋 때 이 범위가 트랜잭션 시작 이후 바뀌지 않았는지 검증한다 ([Txn.watchRange]). */
    fun watchRange(from: ByteArray, to: ByteArray?) = txn.watchRange(from, to)

    override fun flush() = Unit

    override fun stats(): EngineStats = throw UnsupportedOperationException("트랜잭션 뷰에는 엔진 통계가 없습니다")

    override fun close() = Unit
}
