package dev.mydb.txn

import dev.mydb.kv.ByteKeys
import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/** 다른 트랜잭션이 readTs 이후에 같은 키를 커밋했다. 새 스냅샷으로 다시 시도해야 한다. */
class WriteConflictException(message: String) : RuntimeException(message)

/**
 * KvEngine 위의 MVCC 스냅샷 격리 (설계 docs/detailed/m7-transactions.md).
 *
 * - 읽기: 시작 시점(readTs) 이하로 커밋된 가장 최신 버전. 읽기는 기다리지도 실패하지도 않는다.
 * - 쓰기: 트랜잭션 안에 버퍼링했다가 커밋 때 한꺼번에.
 * - 커밋: first-committer-wins. 쓴 키 중 하나라도 readTs 이후에 다른 커밋이 있으면 WriteConflictException.
 *   충돌 검사와 쓰기는 하나의 잠금 안에서 일어나고, 쓰기는 writeBatch 한 번이라 원자적이다.
 *
 * 스레드 안전하다. Txn 하나는 한 스레드에서만 쓴다.
 */
class TxnStore(private val engine: KvEngine) {
    private val lock = Any()

    /** 마지막으로 커밋된 시각. 커밋 배치가 다 쓰인 **뒤에** 올린다 → 이 값으로 시작한 스냅샷은 배치 전체를 본다. */
    @Volatile
    private var lastCommittedTs: Long = engine.get(LAST_TS)?.let { ByteBuffer.wrap(it).getLong() } ?: 0L

    private val txnIds = AtomicLong()
    private val activeReadTs = HashMap<Long, Long>()

    fun begin(): Txn = synchronized(lock) {
        val id = txnIds.incrementAndGet()
        val readTs = lastCommittedTs
        activeReadTs[id] = readTs
        Txn(this, id, readTs)
    }

    /** 충돌이면 새 스냅샷으로 다시 실행한다. [block]은 다시 실행해도 되는 코드여야 한다. */
    fun <T> run(maxAttempts: Int = 50, block: (Txn) -> T): T {
        var lastConflict: WriteConflictException? = null
        repeat(maxAttempts) {
            val txn = begin()
            try {
                val result = block(txn)
                txn.commit()
                return result
            } catch (e: WriteConflictException) {
                lastConflict = e
            } finally {
                txn.close()
            }
        }
        throw lastConflict!!
    }

    /**
     * 아무 트랜잭션도 볼 수 없는 버전을 지운다. 지운 버전 수를 돌려준다.
     * 키마다: readTs 최솟값보다 새 버전은 남기고, 그 이하 중 가장 최신 하나도 남기되(삭제 표시면 지움) 나머지는 지운다.
     */
    fun gc(): Int = synchronized(lock) {
        val minReadTs = activeReadTs.values.minOrNull() ?: lastCommittedTs
        val deletes = ArrayList<WriteOp>()
        var currentKey: ByteArray? = null
        var keptVisible = false
        engine.scan(VersionKey.ALL, prefixEnd(VersionKey.ALL)).use { iterator ->
            for (entry in iterator) {
                val (userKey, ts) = VersionKey.decode(entry.key)
                if (currentKey == null || !currentKey.contentEquals(userKey)) {
                    currentKey = userKey
                    keptVisible = false
                }
                when {
                    ts > minReadTs -> Unit
                    !keptVisible -> {
                        keptVisible = true
                        if (VersionValue.isTombstone(entry.value)) deletes += WriteOp.Delete(entry.key)
                    }
                    else -> deletes += WriteOp.Delete(entry.key)
                }
            }
        }
        // GC가 지우는 것은 아무도 볼 수 없는 버전뿐이라 원자적일 필요가 없다. 배치 크기 상한 때문에 나눠 쓴다
        deletes.chunked(10_000).forEach { engine.writeBatch(it) }
        deletes.size
    }

    /** 테스트·측정용: 저장된 버전 수. */
    fun versionCount(): Int = engine.scan(VersionKey.ALL, prefixEnd(VersionKey.ALL)).use { it.asSequence().count() }

    internal fun read(key: ByteArray, readTs: Long): ByteArray? {
        val from = VersionKey.encode(key, readTs)
        val to = prefixEnd(VersionKey.userPrefix(key))
        return engine.scan(from, to).use { iterator ->
            if (iterator.hasNext()) VersionValue.decode(iterator.next().value) else null
        }
    }

    /** [from, to) 사용자 키 범위에서 readTs 스냅샷이 보는 (키, 값). 키마다 최신순으로 오므로 ts ≤ readTs인 첫 버전만 취한다. */
    internal fun scan(from: ByteArray, to: ByteArray?, readTs: Long): TreeMap<ByteArray, ByteArray> {
        val result = TreeMap<ByteArray, ByteArray>(ByteKeys.COMPARATOR)
        val storageFrom = VersionKey.rangeBound(from)
        val storageTo = to?.let { VersionKey.rangeBound(it) } ?: prefixEnd(VersionKey.ALL)
        var decided: ByteArray? = null
        engine.scan(storageFrom, storageTo).use { iterator ->
            for (entry in iterator) {
                val (userKey, ts) = VersionKey.decode(entry.key)
                if (decided != null && decided.contentEquals(userKey)) continue
                if (ts > readTs) continue
                decided = userKey
                VersionValue.decode(entry.value)?.let { result[userKey] = it }
            }
        }
        return result
    }

    internal fun commit(txn: Txn, writes: TreeMap<ByteArray, ByteArray?>) = synchronized(lock) {
        try {
            for (key in writes.keys) {
                // [(K, ∞), (K, readTs)) 에 버전이 있으면 readTs 이후에 누군가 커밋한 것이다
                val newer = engine.scan(VersionKey.userPrefix(key), VersionKey.encode(key, txn.readTs)).use { it.hasNext() }
                if (newer) throw WriteConflictException("키 ${String(key)}가 이 트랜잭션 시작(ts=${txn.readTs}) 이후에 커밋되었습니다")
            }
            val commitTs = lastCommittedTs + 1
            val ops = writes.map { (key, value) ->
                WriteOp.Put(VersionKey.encode(key, commitTs), value?.let(VersionValue::put) ?: VersionValue.TOMBSTONE_BYTES)
            } + WriteOp.Put(LAST_TS, ByteBuffer.allocate(8).putLong(commitTs).array())
            engine.writeBatch(ops)
            lastCommittedTs = commitTs
        } finally {
            activeReadTs.remove(txn.id)
        }
    }

    internal fun end(txn: Txn) = synchronized(lock) { activeReadTs.remove(txn.id) }

    private companion object {
        val LAST_TS = "t/last".toByteArray()
    }
}

class Txn internal constructor(private val store: TxnStore, internal val id: Long, val readTs: Long) : AutoCloseable {
    /** 쓰기 집합. null = 삭제. 커밋 전까지 다른 트랜잭션에 보이지 않는다. */
    private val writes = TreeMap<ByteArray, ByteArray?>(ByteKeys.COMPARATOR)
    private var finished = false

    fun get(key: ByteArray): ByteArray? {
        checkActive()
        if (writes.containsKey(key)) return writes[key]?.copyOf()
        return store.read(key, readTs)
    }

    fun put(key: ByteArray, value: ByteArray) {
        checkActive()
        writes[key.copyOf()] = value.copyOf()
    }

    fun delete(key: ByteArray) {
        checkActive()
        writes[key.copyOf()] = null
    }

    /** 스냅샷과 자기 쓰기를 합친 [from, to) 범위. */
    fun scan(from: ByteArray, to: ByteArray?): List<Pair<ByteArray, ByteArray>> {
        checkActive()
        val visible = store.scan(from, to, readTs)
        val ownWrites = if (to == null) writes.tailMap(from, true) else writes.subMap(from, true, to, false)
        for ((key, value) in ownWrites) if (value == null) visible.remove(key) else visible[key] = value
        return visible.map { (k, v) -> k to v.copyOf() }
    }

    fun commit() {
        checkActive()
        finished = true
        if (writes.isEmpty()) store.end(this) else store.commit(this, writes)
    }

    fun rollback() {
        if (finished) return
        finished = true
        store.end(this)
    }

    /** 커밋하지 않고 닫으면 rollback이다. */
    override fun close() = rollback()

    private fun checkActive() = check(!finished) { "끝난 트랜잭션입니다 (id=$id)" }
}
