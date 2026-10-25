package dev.mydb.txn

import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp
import java.util.concurrent.atomic.AtomicLong

/**
 * 연산 하나 = 트랜잭션 하나로 자동 커밋하는 KvEngine. MVCC 계층에 M1의 계약·무작위·크래시 하네스를 그대로 쓰기 위한 어댑터다.
 * 버전 키가 붙으므로 아래 엔진이 세는 바이트와 사용자 바이트가 달라, 사용자 바이트는 여기서 따로 센다.
 */
class MvccKvEngine(private val engine: KvEngine) : KvEngine {
    val store = TxnStore(engine)
    private val userBytes = AtomicLong()

    override fun get(key: ByteArray): ByteArray? = store.begin().use { it.get(key) }

    override fun put(key: ByteArray, value: ByteArray) {
        store.run { it.put(key, value) }
        userBytes.addAndGet((key.size + value.size).toLong())
    }

    override fun delete(key: ByteArray) {
        store.run { it.delete(key) }
        userBytes.addAndGet(key.size.toLong())
    }

    override fun writeBatch(ops: List<WriteOp>) {
        if (ops.isEmpty()) return
        store.run { txn ->
            ops.forEach { op ->
                when (op) {
                    is WriteOp.Put -> txn.put(op.key, op.value)
                    is WriteOp.Delete -> txn.delete(op.key)
                }
            }
        }
        userBytes.addAndGet(ops.sumOf { if (it is WriteOp.Put) (it.key.size + it.value.size).toLong() else it.key.size.toLong() })
    }

    /** 스냅샷 하나에서 범위를 읽어 둔다. 생성 이후의 커밋은 보이지 않는다. */
    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        val entries = store.begin().use { it.scan(from, to) }.iterator()
        return object : KvIterator {
            override fun hasNext() = entries.hasNext()

            override fun next() = entries.next().let { (k, v) -> KvEntry(k, v) }

            override fun close() = Unit
        }
    }

    override fun flush() = engine.flush()

    override fun stats(): EngineStats = engine.stats().copy(userBytesWritten = userBytes.get())

    override fun close() = engine.close()
}
