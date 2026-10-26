package dev.mydb.txn

import dev.mydb.kv.ByteKeys
import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp
import java.util.TreeMap
import kotlin.random.Random

/** 탐색용 메모리 KV 엔진. 스케줄 수만 개를 빠르게 돌리려고 파일을 쓰지 않는다. */
class MemoryKvEngine : KvEngine {
    private val map = TreeMap<ByteArray, ByteArray>(ByteKeys.COMPARATOR)

    @Synchronized
    override fun get(key: ByteArray): ByteArray? = map[key]?.copyOf()

    @Synchronized
    override fun put(key: ByteArray, value: ByteArray) {
        map[key.copyOf()] = value.copyOf()
    }

    @Synchronized
    override fun delete(key: ByteArray) {
        map.remove(key)
    }

    @Synchronized
    override fun writeBatch(ops: List<WriteOp>) = ops.forEach { op ->
        when (op) {
            is WriteOp.Put -> put(op.key, op.value)
            is WriteOp.Delete -> delete(op.key)
        }
    }

    @Synchronized
    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        val entries = (if (to == null) map.tailMap(from, true) else map.subMap(from, true, to, false)).map { KvEntry(it.key.copyOf(), it.value.copyOf()) }.iterator()
        return object : KvIterator {
            override fun hasNext() = entries.hasNext()

            override fun next() = entries.next()

            override fun close() = Unit
        }
    }

    override fun flush() = Unit

    override fun stats() = EngineStats(0, 0, 0, 0)

    override fun close() = Unit
}

/**
 * SSI 스케줄 탐색기. 작은 트랜잭션들의 읽기·쓰기를 섞은 스케줄을 실제 TxnStore에서 실행하고,
 * 커밋된 트랜잭션들의 기록이 어떤 직렬 순서로도 설명되지 않으면 직렬화 위반으로 판정한다.
 *
 * 각 트랜잭션은 begin → 읽기들 → 쓰기들 → commit 순서이고, 쓰는 값은 "t<번호>"로 고유하다.
 * 그래서 읽은 값으로 "누구의 버전을 읽었는지"를 정확히 알 수 있다.
 */
object SsiScheduleExplorer {
    data class Op(val txn: Int, val kind: Kind, val key: Int = -1) {
        override fun toString() = when (kind) {
            Kind.BEGIN -> "T$txn.begin"
            Kind.READ -> "T$txn.r(k$key)"
            Kind.WRITE -> "T$txn.w(k$key)"
            Kind.COMMIT -> "T$txn.commit"
        }
    }

    enum class Kind { BEGIN, READ, WRITE, COMMIT }

    class Outcome(val committed: Set<Int>, val reads: Map<Int, Map<Int, String>>, val writes: Map<Int, Set<Int>>, val finalState: Map<Int, String>) {
        /** 커밋된 트랜잭션의 어떤 직렬 순서가 같은 읽기 값과 최종 상태를 만드는가. */
        fun isSerializable(): Boolean = permutations(committed.toList()).any { order ->
            val state = HashMap<Int, String>()
            order.all { t ->
                val ok = reads.getValue(t).all { (k, v) -> (state[k] ?: "init") == v }
                writes.getValue(t).forEach { k -> state[k] = "t$t" }
                ok
            } && finalState.all { (k, v) -> (state[k] ?: "init") == v }
        }
    }

    /** 트랜잭션마다 읽기 집합·쓰기 집합을 무작위로 고른다. 쓰기가 없으면 읽기 전용 트랜잭션이다 (규칙 C 시나리오에 필요). */
    fun randomSchedule(random: Random, txns: Int, keys: Int): List<Op> {
        val perTxn = (0 until txns).map { t ->
            val reads = (0 until keys).filter { random.nextInt(3) > 0 }
            val writes = if (random.nextInt(4) == 0) emptyList() else (0 until keys).filter { random.nextInt(2) == 0 }.ifEmpty { listOf(random.nextInt(keys)) }
            ArrayDeque(listOf(Op(t, Kind.BEGIN)) + reads.map { Op(t, Kind.READ, it) } + writes.map { Op(t, Kind.WRITE, it) } + Op(t, Kind.COMMIT))
        }
        val schedule = ArrayList<Op>()
        while (perTxn.any { it.isNotEmpty() }) schedule += perTxn.filter { it.isNotEmpty() }.random(random).removeFirst()
        return schedule
    }

    fun run(schedule: List<Op>, isolation: Isolation = Isolation.SERIALIZABLE, maxRecords: Int = 1_000): Outcome {
        val store = TxnStore(MemoryKvEngine(), maxRetainedRecords = maxRecords)
        val open = HashMap<Int, Txn>()
        val aborted = HashSet<Int>()
        val committed = HashSet<Int>()
        val reads = HashMap<Int, MutableMap<Int, String>>()
        val writes = HashMap<Int, MutableSet<Int>>()
        for (op in schedule) {
            if (op.txn in aborted) continue
            when (op.kind) {
                Kind.BEGIN -> open[op.txn] = store.begin(isolation)
                Kind.READ -> reads.getOrPut(op.txn) { HashMap() }[op.key] = open.getValue(op.txn).get(key(op.key))?.let(::String) ?: "init"
                Kind.WRITE -> {
                    open.getValue(op.txn).put(key(op.key), "t${op.txn}".toByteArray())
                    writes.getOrPut(op.txn) { HashSet() } += op.key
                }
                Kind.COMMIT -> try {
                    open.getValue(op.txn).commit()
                    committed += op.txn
                } catch (e: WriteConflictException) {
                    aborted += op.txn
                }
            }
        }
        val keys = schedule.filter { it.key >= 0 }.map { it.key }.toSet()
        val final = store.begin().use { t -> keys.associateWith { k -> t.get(key(k))?.let(::String) ?: "init" } }
        return Outcome(committed, committed.associateWith { reads[it] ?: emptyMap() }, committed.associateWith { writes[it] ?: emptySet() }, final)
    }

    private fun key(k: Int) = "k$k".toByteArray()

    private fun <T> permutations(items: List<T>): Sequence<List<T>> = if (items.size <= 1) sequenceOf(items) else sequence {
        for (i in items.indices) for (rest in permutations(items.take(i) + items.drop(i + 1))) yield(listOf(items[i]) + rest)
    }
}
