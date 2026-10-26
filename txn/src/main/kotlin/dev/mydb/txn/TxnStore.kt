package dev.mydb.txn

import dev.mydb.kv.ByteKeys
import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong

/** 커밋할 수 없다. 새 스냅샷으로 다시 시도해야 한다. 재시도 헬퍼들은 이 예외(와 하위 타입)를 잡아 다시 실행한다. */
open class WriteConflictException(message: String) : RuntimeException(message)

/** SERIALIZABLE 트랜잭션이 직렬화 불가능한 실행(위험 구조)의 일부가 될 뻔했다. 다시 시도하면 된다. */
class SerializationFailureException(message: String) : WriteConflictException(message)

enum class Isolation {
    /** 스냅샷 격리: 쓰기-쓰기 충돌만 막는다. write skew가 일어날 수 있다. */
    SNAPSHOT,

    /** 직렬화 가능 스냅샷 격리 (SSI, Cahill et al. 2008): 읽기 집합을 추적해 rw 의존 위험 구조를 끊는다. write skew를 막는다. */
    SERIALIZABLE,
}

/**
 * KvEngine 위의 MVCC (설계 docs/detailed/m7-transactions.md).
 *
 * - 읽기: 시작 시점(readTs) 이하로 커밋된 가장 최신 버전. 읽기는 기다리지 않는다.
 * - 쓰기: 트랜잭션 안에 버퍼링했다가 커밋 때 한꺼번에.
 * - 커밋 (모든 격리 수준): first-committer-wins. 쓴 키 중 하나라도 readTs 이후에 다른 커밋이 있으면 WriteConflictException.
 * - 커밋 (SERIALIZABLE 추가): rw 의존 간선을 계산해 위험 구조면 SerializationFailureException (§11).
 * 충돌 검사와 쓰기는 하나의 잠금 안에서 일어나고, 쓰기는 writeBatch 한 번이라 원자적이다.
 *
 * 스레드 안전하다. Txn 하나는 한 스레드에서만 쓴다.
 */
class TxnStore(private val engine: KvEngine, val defaultIsolation: Isolation = Isolation.SNAPSHOT) {
    private val lock = Any()

    /** 마지막으로 커밋된 시각. 커밋 배치가 다 쓰인 **뒤에** 올린다 → 이 값으로 시작한 스냅샷은 배치 전체를 본다. */
    @Volatile
    private var lastCommittedTs: Long = engine.get(LAST_TS)?.let { ByteBuffer.wrap(it).getLong() } ?: 0L

    private val txnIds = AtomicLong()
    private val activeReadTs = HashMap<Long, Long>()

    /** 진행 중인 SERIALIZABLE 트랜잭션. 다른 트랜잭션이 커밋할 때 이들이 읽은 것과 겹치는지 본다. */
    private val activeSerializable = HashMap<Long, Txn>()

    /**
     * 커밋된 SERIALIZABLE 트랜잭션의 기록 (읽기 전용 포함). 아직 진행 중인 트랜잭션과 시간이 겹치는 동안만 필요하다 (§11.4).
     * 읽기 전용도 남겨야 한다: 읽기 전용 R이 먼저 커밋한 뒤 피벗 T가 커밋하는 경우(read-only anomaly), T로 들어오는
     * 간선 R → T를 계산하려면 R이 읽은 것이 필요하다.
     */
    private val committedSerializable = ArrayList<CommittedTxn>()

    /**
     * 시작·종료마다 하나씩 오르는 순번. "두 트랜잭션의 실행 기간이 겹쳤는가"를 판정한다.
     * 커밋 시각(ts)으로는 안 된다: 읽기 전용은 새 ts를 받지 않고, 사이에 커밋이 없으면 겹친 두 트랜잭션의 ts가 같다.
     */
    private var eventSeq = 0L

    internal class CommittedTxn(
        val id: Long,
        val readTs: Long,
        /** 이 트랜잭션의 쓰기가 보이기 시작한 시각 (읽기 전용은 커밋 당시 lastCommittedTs). */
        val commitTs: Long,
        val endSeq: Long,
        val readKeys: TreeSet<ByteArray>,
        val readRanges: List<Pair<ByteArray, ByteArray?>>,
        val writeKeys: TreeSet<ByteArray>,
        var inConflict: Boolean,
        var outConflict: Boolean,
    )

    fun begin(isolation: Isolation = defaultIsolation): Txn = synchronized(lock) {
        val id = txnIds.incrementAndGet()
        val readTs = lastCommittedTs
        activeReadTs[id] = readTs
        Txn(this, id, readTs, isolation, ++eventSeq).also { if (isolation == Isolation.SERIALIZABLE) activeSerializable[id] = it }
    }

    /** 충돌(직렬화 실패 포함)이면 새 스냅샷으로 다시 실행한다. [block]은 다시 실행해도 되는 코드여야 한다. */
    fun <T> run(maxAttempts: Int = 50, isolation: Isolation = defaultIsolation, block: (Txn) -> T): T {
        var lastConflict: WriteConflictException? = null
        repeat(maxAttempts) {
            val txn = begin(isolation)
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

    /** 테스트·측정용: SSI가 들고 있는 커밋된 트랜잭션 기록 수 (진행 중인 트랜잭션이 없으면 0이어야 한다). */
    fun retainedSerializableRecords(): Int = synchronized(lock) { committedSerializable.size }

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

    internal fun commit(
        txn: Txn,
        writes: TreeMap<ByteArray, ByteArray?>,
        watchedKeys: Collection<ByteArray>,
        watchedRanges: List<Pair<ByteArray, ByteArray?>>,
    ) = synchronized(lock) {
        try {
            if (writes.isNotEmpty()) {
                // 쓴 키 + 지켜본 키: [(K, ∞), (K, readTs)) 에 버전이 있으면 readTs 이후에 누군가 커밋한 것이다
                for (key in writes.keys + watchedKeys) {
                    val newer = engine.scan(VersionKey.userPrefix(key), VersionKey.encode(key, txn.readTs)).use { it.hasNext() }
                    if (newer) throw WriteConflictException("키 ${String(key)}가 이 트랜잭션 시작(ts=${txn.readTs}) 이후에 커밋되었습니다")
                }
                // 지켜본 범위: 범위 안 어떤 키든 readTs 이후 버전이 있으면 충돌 (범위 크기에 비례하는 검사)
                for ((from, to) in watchedRanges) {
                    val storageTo = to?.let { VersionKey.rangeBound(it) } ?: prefixEnd(VersionKey.ALL)
                    val changed = engine.scan(VersionKey.rangeBound(from), storageTo).use { iterator ->
                        iterator.asSequence().any { VersionKey.decode(it.key).second > txn.readTs }
                    }
                    if (changed) throw WriteConflictException("범위 [${String(from)}, ${to?.let { String(it) } ?: "끝"})가 이 트랜잭션 시작(ts=${txn.readTs}) 이후에 바뀌었습니다")
                }
            }

            val ssi = if (txn.isolation == Isolation.SERIALIZABLE) checkSerializable(txn, writes.navigableKeySet()) else null

            var commitTs = lastCommittedTs
            if (writes.isNotEmpty()) {
                commitTs += 1
                val ops = writes.map { (key, value) ->
                    WriteOp.Put(VersionKey.encode(key, commitTs), value?.let(VersionValue::put) ?: VersionValue.TOMBSTONE_BYTES)
                } + WriteOp.Put(LAST_TS, ByteBuffer.allocate(8).putLong(commitTs).array())
                engine.writeBatch(ops)
                lastCommittedTs = commitTs
            }

            if (ssi != null) {
                ssi.apply()
                committedSerializable += txn.reads { keys, ranges ->
                    // TreeSet(Collection) 생성자는 비교자를 복사하지 않아 ByteArray를 자연 순서로 정렬하려다 실패한다 → 비교자를 명시
                    CommittedTxn(
                        txn.id, txn.readTs, commitTs, eventSeq + 1, byteKeySet(keys), ranges.toList(),
                        byteKeySet(writes.keys), ssi.inConflict, ssi.outConflict,
                    )
                }
            }
        } finally {
            finish(txn)
        }
    }

    internal fun end(txn: Txn) = synchronized(lock) { finish(txn) }

    /** 트랜잭션을 활성 목록에서 빼고, 더 이상 진행 중인 SERIALIZABLE 트랜잭션과 겹치지 않는 기록을 버린다. */
    private fun finish(txn: Txn) {
        eventSeq++
        activeReadTs.remove(txn.id)
        activeSerializable.remove(txn.id)
        val oldestActiveBegin = activeSerializable.values.minOfOrNull { it.beginSeq }
        if (oldestActiveBegin == null) committedSerializable.clear() else committedSerializable.removeAll { it.endSeq <= oldestActiveBegin }
    }

    /** 커밋이 확정되면 다른 트랜잭션에 반영할 간선 표시. 중단하기로 하면 아무것도 반영하지 않는다. */
    private inner class SsiOutcome(val inConflict: Boolean, val outConflict: Boolean, private val effects: List<() -> Unit>) {
        fun apply() = effects.forEach { it() }
    }

    /**
     * §11: rw 의존 간선 R → W = "R이 읽은 것을 동시에 실행된 W가 바꿨다 (R은 W의 쓰기를 못 봤다)".
     * 스냅샷 격리의 직렬화 불가능한 실행에는 들어오는·나가는 rw 간선을 모두 가진 피벗이 반드시 있다 (Fekete et al. 2005).
     * 이 트랜잭션 T의 커밋 시점에 T와 관련된 간선을 모두 계산하고, 아래 중 하나면 T를 중단한다.
     *   (A) T 자신이 피벗: T로 들어오는 간선과 T에서 나가는 간선이 모두 있다
     *   (B) T → 가 아닌 R → T 간선에서 R이 이미 커밋됐고 R로 들어오는 간선이 있다 (R이 피벗이 되는데 R은 되돌릴 수 없다)
     *   (C) T → W 간선에서 W가 이미 커밋됐고 W에서 나가는 간선이 있다 (W가 피벗이 되는데 W는 되돌릴 수 없다)
     * 실제 순환이 없어도 중단할 수 있다(거짓 양성). 대신 순환은 하나도 놓치지 않는다.
     */
    private fun checkSerializable(txn: Txn, writeKeys: java.util.NavigableSet<ByteArray>): SsiOutcome {
        var inConflict = txn.inConflict
        var outConflict = txn.outConflict
        val effects = ArrayList<() -> Unit>()

        // 나가는 간선 T → W: T가 읽은 것을, T 시작 이후 커밋한 W가 바꿨다
        for (w in committedSerializable) {
            if (w.commitTs <= txn.readTs || !txn.reads { keys, ranges -> overlaps(keys, ranges, w.writeKeys) }) continue
            if (w.outConflict) throw SerializationFailureException("직렬화 실패: 이미 커밋된 트랜잭션 ${w.id}이(가) 피벗이 됩니다 (C)")
            outConflict = true
            effects += { w.inConflict = true }
        }

        if (writeKeys.isNotEmpty()) {
            // 들어오는 간선 R → T (진행 중인 R): R이 읽은 것을 T가 바꾼다. R은 T의 쓰기를 못 본다
            for (r in activeSerializable.values) {
                if (r === txn || !r.reads { keys, ranges -> overlaps(keys, ranges, writeKeys) }) continue
                inConflict = true
                effects += { r.outConflict = true }
            }
            // 들어오는 간선 R → T (T가 진행되는 동안 커밋한 R — 읽기 전용 포함). R은 T의 쓰기를 못 봤다
            for (r in committedSerializable) {
                if (r.endSeq <= txn.beginSeq || !overlaps(r.readKeys, r.readRanges, writeKeys)) continue
                if (r.inConflict) throw SerializationFailureException("직렬화 실패: 이미 커밋된 트랜잭션 ${r.id}이(가) 피벗이 됩니다 (B)")
                inConflict = true
                effects += { r.outConflict = true }
            }
        }

        if (inConflict && outConflict) throw SerializationFailureException("직렬화 실패: 이 트랜잭션이 rw 의존의 피벗입니다 (A)")
        return SsiOutcome(inConflict, outConflict, effects)
    }

    /** 읽은 키·범위가 쓴 키와 겹치는가. 범위 [from, to)는 쓴 키 집합의 부분 집합으로 검사한다. */
    private fun overlaps(readKeys: Set<ByteArray>, readRanges: List<Pair<ByteArray, ByteArray?>>, writeKeys: java.util.NavigableSet<ByteArray>): Boolean {
        if (readKeys.any { it in writeKeys }) return true
        return readRanges.any { (from, to) ->
            (if (to == null) writeKeys.tailSet(from, true) else writeKeys.subSet(from, true, to, false)).isNotEmpty()
        }
    }

    private companion object {
        val LAST_TS = "t/last".toByteArray()

        fun byteKeySet(keys: Collection<ByteArray>) = TreeSet(ByteKeys.COMPARATOR).apply { addAll(keys) }
    }
}

class Txn internal constructor(
    private val store: TxnStore,
    internal val id: Long,
    val readTs: Long,
    val isolation: Isolation,
    /** 실행 기간 겹침 판정용 시작 순번 (TxnStore.eventSeq). */
    internal val beginSeq: Long,
) : AutoCloseable {
    /** 쓰기 집합. null = 삭제. 커밋 전까지 다른 트랜잭션에 보이지 않는다. */
    private val writes = TreeMap<ByteArray, ByteArray?>(ByteKeys.COMPARATOR)

    /**
     * 커밋 때 "시작 이후 아무도 바꾸지 않았는지" 검증할 키·범위 (낙관적 동시성 제어의 읽기 검증).
     * SNAPSHOT 트랜잭션이 특정 읽기에만 의존성을 걸 때 쓴다 (예: 인덱스 정의를 읽고 문서를 쓰기).
     * SERIALIZABLE은 모든 읽기를 추적하므로 따로 필요 없지만, 함께 써도 된다.
     */
    private val watchedKeys = TreeMap<ByteArray, Unit>(ByteKeys.COMPARATOR)
    private val watchedRanges = ArrayList<Pair<ByteArray, ByteArray?>>()

    /**
     * SERIALIZABLE의 읽기 집합 (SSI의 SIREAD 표시). 키 단위 + scan 범위 단위 (범위가 있어야 phantom을 잡는다).
     * 다른 스레드의 커밋이 이 집합을 읽으므로 이 객체로 동기화한다.
     */
    private val readKeys = TreeSet<ByteArray>(ByteKeys.COMPARATOR)
    private val readRanges = ArrayList<Pair<ByteArray, ByteArray?>>()

    /** 다른 트랜잭션의 커밋이 표시하는 rw 간선 (TxnStore의 잠금 안에서만 바뀐다). */
    @Volatile
    internal var inConflict = false

    @Volatile
    internal var outConflict = false

    private var finished = false

    fun get(key: ByteArray): ByteArray? {
        checkActive()
        if (writes.containsKey(key)) return writes[key]?.copyOf()
        if (isolation == Isolation.SERIALIZABLE) synchronized(this) { readKeys += key.copyOf() }
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

    /** 스냅샷과 자기 쓰기를 합친 [from, to) 범위. SERIALIZABLE이면 범위 전체를 읽은 것으로 표시한다 (없던 키가 생겨도 걸린다). */
    fun scan(from: ByteArray, to: ByteArray?): List<Pair<ByteArray, ByteArray>> {
        checkActive()
        if (isolation == Isolation.SERIALIZABLE) synchronized(this) { readRanges += from.copyOf() to to?.copyOf() }
        val visible = store.scan(from, to, readTs)
        val ownWrites = if (to == null) writes.tailMap(from, true) else writes.subMap(from, true, to, false)
        for ((key, value) in ownWrites) if (value == null) visible.remove(key) else visible[key] = value
        return visible.map { (k, v) -> k to v.copyOf() }
    }

    /** 커밋 때 [key]가 이 트랜잭션 시작 이후 바뀌었으면 WriteConflictException. */
    fun watch(key: ByteArray) {
        checkActive()
        watchedKeys[key.copyOf()] = Unit
    }

    /** 커밋 때 [from, to) 안의 어떤 키라도 시작 이후 바뀌었으면 WriteConflictException. */
    fun watchRange(from: ByteArray, to: ByteArray?) {
        checkActive()
        watchedRanges += from.copyOf() to to?.copyOf()
    }

    /**
     * SNAPSHOT 읽기 전용은 검증할 것이 없다. SERIALIZABLE 읽기 전용은 검증한다:
     * 읽기 전용 트랜잭션도 직렬화 불가능한 상태를 관찰할 수 있다 (Fekete et al. 2004의 read-only anomaly).
     */
    fun commit() {
        checkActive()
        finished = true
        if (writes.isEmpty() && isolation == Isolation.SNAPSHOT) store.end(this) else store.commit(this, writes, watchedKeys.keys, watchedRanges)
    }

    fun rollback() {
        if (finished) return
        finished = true
        store.end(this)
    }

    /** 커밋하지 않고 닫으면 rollback이다. */
    override fun close() = rollback()

    /** 읽기 집합을 안전하게 들여다본다 (다른 스레드의 커밋이 부른다). */
    internal fun <T> reads(block: (Set<ByteArray>, List<Pair<ByteArray, ByteArray?>>) -> T): T = synchronized(this) { block(readKeys, readRanges) }

    private fun checkActive() = check(!finished) { "끝난 트랜잭션입니다 (id=$id)" }
}
