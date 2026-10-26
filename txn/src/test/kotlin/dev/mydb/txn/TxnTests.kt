package dev.mydb.txn

import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.ByteKeys
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEngineContractTest
import dev.mydb.kv.KvEngineRandomTest
import dev.mydb.kv.crash.CrashEngineFactory
import dev.mydb.kv.crash.KvEngineCrashTest
import dev.mydb.storage.SyncPolicy
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

private fun b(s: String) = s.toByteArray()

class VersionKeyTest {
    @Test
    fun `같은 키의 버전은 최신이 먼저이고 다른 키와 섞이지 않는다`() {
        val keys = listOf("", "a", "a\u0000", "a\u0000b", "ab", "b").map { it.toByteArray() }
        val encoded = keys.flatMap { k -> listOf(1L, 7L, 3L).map { ts -> Triple(k, ts, VersionKey.encode(k, ts)) } }
        val sorted = encoded.sortedWith { x, y -> ByteKeys.compare(x.third, y.third) }

        val expected = keys.flatMap { k -> listOf(7L, 3L, 1L).map { k.toList() to it } }
        assertEquals(expected, sorted.map { it.first.toList() to it.second })
        encoded.forEach { (k, ts, e) -> assertEquals(k.toList() to ts, VersionKey.decode(e).let { it.first.toList() to it.second }) }
    }
}

/** 트랜잭션 의미는 아래 엔진과 무관해야 한다. 기본 테스트는 LSM 위에서, 동시 이체는 세 엔진 모두에서. */
abstract class TxnTestBase {
    @TempDir
    lateinit var dir: Path

    abstract fun openEngine(dir: Path): KvEngine

    private fun withStore(block: (TxnStore) -> Unit) = openEngine(dir).use { block(TxnStore(it)) }

    private fun withCappedStore(maxRecords: Int, maxKeys: Int = 1_000, block: (TxnStore) -> Unit) =
        openEngine(dir).use { block(TxnStore(it, maxRetainedRecords = maxRecords, maxKeysPerRecord = maxKeys)) }

    private fun TxnStore.set(key: String, value: String) = run { it.put(b(key), b(value)) }

    private fun Txn.str(key: String) = get(b(key))?.let(::String)

    @Test
    fun `스냅샷은 시작 이후의 커밋을 보지 않는다`() = withStore { store ->
        store.set("k", "v1")
        val reader = store.begin()
        store.set("k", "v2")
        store.set("new", "x")

        assertEquals("v1", reader.str("k"))
        assertNull(reader.str("new"))
        assertEquals(listOf("k"), reader.scan(ByteArray(0), null).map { String(it.first) })
        reader.close()
        assertEquals("v2", store.begin().use { it.str("k") })
    }

    @Test
    fun `자기 쓰기는 커밋 전에도 보이고 다른 트랜잭션에는 안 보인다`() = withStore { store ->
        store.set("a", "1")
        val t1 = store.begin()
        t1.put(b("a"), b("2"))
        t1.delete(b("zz"))
        t1.put(b("b"), b("3"))
        val t2 = store.begin()

        assertEquals("2", t1.str("a"))
        assertEquals(listOf("a" to "2", "b" to "3"), t1.scan(ByteArray(0), null).map { String(it.first) to String(it.second) })
        assertEquals("1", t2.str("a"))
        assertNull(t2.str("b"))
        t1.rollback()
        assertEquals("1", store.begin().use { it.str("a") })
        t2.close()
    }

    @Test
    fun `lost update - 같은 키를 쓴 두 트랜잭션 중 나중 커밋은 실패한다`() = withStore { store ->
        store.set("balance", "100")
        val t1 = store.begin()
        val t2 = store.begin()
        t1.put(b("balance"), b("${t1.str("balance")!!.toInt() + 10}"))
        t2.put(b("balance"), b("${t2.str("balance")!!.toInt() + 20}"))

        t1.commit()
        assertFailsWith<WriteConflictException> { t2.commit() }
        assertEquals("110", store.begin().use { it.str("balance") })
    }

    @Test
    fun `읽기 전용 트랜잭션은 충돌하지 않는다`() = withStore { store ->
        store.set("k", "v")
        val reader = store.begin()
        store.set("k", "changed")
        reader.str("k")
        reader.commit()
    }

    /**
     * write skew (DDIA 7장): "당직은 최소 한 명". 서로 다른 키를 쓰므로 스냅샷 격리는 충돌로 보지 않고 둘 다 커밋된다.
     * 우회: 읽은 조건을 키 하나로 materialize해 둘 다 그 키를 쓰게 하면 쓰기-쓰기 충돌이 되어 하나가 실패한다.
     */
    @Test
    fun `write skew는 스냅샷 격리에서 일어나고 충돌을 materialize하면 막힌다`() = withStore { store ->
        fun onCall(t: Txn) = listOf("alice", "bob").count { t.str("oncall/$it") == "true" }
        store.run { it.put(b("oncall/alice"), b("true")); it.put(b("oncall/bob"), b("true")) }

        val alice = store.begin()
        val bob = store.begin()
        if (onCall(alice) >= 2) alice.put(b("oncall/alice"), b("false"))
        if (onCall(bob) >= 2) bob.put(b("oncall/bob"), b("false"))
        alice.commit()
        bob.commit()
        assertEquals(0, store.begin().use { onCall(it) }, "write skew: 둘 다 빠져 당직이 0명이 된다")

        // 같은 상황에서 둘 다 "당직 명단" 키를 함께 쓰면 나중 커밋이 충돌로 실패한다
        store.run { it.put(b("oncall/alice"), b("true")); it.put(b("oncall/bob"), b("true")) }
        val alice2 = store.begin()
        val bob2 = store.begin()
        for ((t, who) in listOf(alice2 to "alice", bob2 to "bob")) {
            if (onCall(t) >= 2) {
                t.put(b("oncall/$who"), b("false"))
                t.put(b("oncall-roster"), b("changed-by-$who"))
            }
        }
        alice2.commit()
        assertFailsWith<WriteConflictException> { bob2.commit() }
        assertEquals(1, store.begin().use { onCall(it) })
    }

    @Test
    fun `GC는 아무도 못 보는 버전만 지우고 모든 스냅샷의 결과는 그대로다`() = withStore { store ->
        repeat(5) { round -> store.set("k", "v$round") }
        store.set("gone", "x")
        store.run { it.delete(b("gone")) }
        val oldReader = store.begin() // k=v4, gone 없음
        store.set("k", "v5")
        store.set("k", "v6")

        val removed = store.gc()

        // oldReader가 보는 v4와 그 이후 v5, v6만 남는다. v0~v3, gone의 PUT·삭제 표시는 지운다
        assertEquals(4 + 2, removed)
        assertEquals("v4", oldReader.str("k"))
        assertNull(oldReader.str("gone"))
        oldReader.close()
        assertEquals("v6", store.begin().use { it.str("k") })
        store.gc()
        assertEquals(1, store.versionCount(), "모든 트랜잭션이 끝나면 최신 버전 하나만 남는다")
    }

    @Test
    fun `재시작 후 시계와 데이터가 복원된다`() {
        withStore { store ->
            store.set("k", "v1")
            store.set("k", "v2")
        }
        withStore { store ->
            val reader = store.begin()
            assertEquals("v2", reader.str("k"))
            store.set("k", "v3")
            assertEquals("v2", reader.str("k"), "재시작 후에도 새 커밋은 이전 스냅샷에 안 보여야 한다 (시계가 되돌아가면 깨진다)")
            reader.close()
        }
    }

    // ---- SERIALIZABLE (SSI) ----

    @Test
    fun `SERIALIZABLE은 write skew를 막는다 - 당직 의사`() = withStore { store ->
        fun onCall(t: Txn) = listOf("alice", "bob").count { t.str("oncall/$it") == "true" }
        store.run { it.put(b("oncall/alice"), b("true")); it.put(b("oncall/bob"), b("true")) }

        val alice = store.begin(Isolation.SERIALIZABLE)
        val bob = store.begin(Isolation.SERIALIZABLE)
        if (onCall(alice) >= 2) alice.put(b("oncall/alice"), b("false"))
        if (onCall(bob) >= 2) bob.put(b("oncall/bob"), b("false"))
        alice.commit()
        assertFailsWith<SerializationFailureException> { bob.commit() }

        assertEquals(1, store.begin().use { onCall(it) }, "당직이 최소 한 명 남는다")
        assertEquals(0, store.retainedSerializableRecords(), "진행 중인 트랜잭션이 없으면 SSI 기록도 없어야 한다")
    }

    /** 범위를 읽고(없음을 확인하고) 서로 다른 새 키를 쓰는 write skew. 키 단위 추적만으로는 못 잡고 범위 추적이 필요하다. */
    @Test
    fun `SERIALIZABLE은 phantom write skew를 막는다 - 회의실 이중 예약`() = withStore { store ->
        fun booked(t: Txn) = t.scan(b("room/1/10:00/"), b("room/1/10:00/~")).size
        val t1 = store.begin(Isolation.SERIALIZABLE)
        val t2 = store.begin(Isolation.SERIALIZABLE)
        if (booked(t1) == 0) t1.put(b("room/1/10:00/alice"), b("x"))
        if (booked(t2) == 0) t2.put(b("room/1/10:00/bob"), b("x"))
        t1.commit()
        assertFailsWith<SerializationFailureException> { t2.commit() }
        assertEquals(1, store.begin().use { booked(it) })

        // 같은 시나리오를 SNAPSHOT으로: 둘 다 커밋되어 이중 예약
        val s1 = store.begin(Isolation.SNAPSHOT)
        val s2 = store.begin(Isolation.SNAPSHOT)
        if (s1.scan(b("room/2/"), b("room/2/~")).isEmpty()) s1.put(b("room/2/alice"), b("x"))
        if (s2.scan(b("room/2/"), b("room/2/~")).isEmpty()) s2.put(b("room/2/bob"), b("x"))
        s1.commit()
        s2.commit()
        assertEquals(2, store.begin().use { it.scan(b("room/2/"), b("room/2/~")).size }, "스냅샷 격리에서는 이중 예약이 일어난다")
    }

    /**
     * 읽기 전용 이상 현상 (Fekete, O'Neil, O'Neil 2004). checking x = 0, savings y = 0.
     * T2: x, y를 읽고 x + y < 10이면 수수료 1 포함 11을 뺀다 / T3: y에 20 입금 / T1(읽기 전용): x, y 보고서.
     * 순서: T2 읽기 → T3 커밋 → T1 읽기·커밋 (보고서 x=0, y=20) → T2 쓰기·커밋 (x=-11).
     * 보고서가 "입금 후·출금 전"을 보여 줬다면 출금은 입금을 봤어야 해서 수수료가 붙으면 안 된다 → 어떤 직렬 순서로도 설명되지 않는다.
     * T2가 피벗(T1 → T2 → T3)이다.
     */
    @Test
    fun `SERIALIZABLE은 읽기 전용 이상 현상도 막는다`() = withStore { store ->
        fun scenario(isolation: Isolation): Pair<String, Boolean> {
            store.run { it.put(b("x"), b("0")); it.put(b("y"), b("0")) }
            val t2 = store.begin(isolation)
            val total = t2.str("x")!!.toInt() + t2.str("y")!!.toInt()
            store.begin(isolation).also { t3 -> t3.put(b("y"), b("${t3.str("y")!!.toInt() + 20}")); t3.commit() }
            val report = store.begin(isolation).let { t1 -> "x=${t1.str("x")}, y=${t1.str("y")}".also { t1.commit() } }
            t2.put(b("x"), b("${-(if (total < 10) 11 else 10)}"))
            val committed = runCatching { t2.commit() }.isSuccess
            return report to committed
        }
        val (snapshotReport, snapshotCommitted) = scenario(Isolation.SNAPSHOT)
        assertEquals("x=0, y=20" to true, snapshotReport to snapshotCommitted, "스냅샷 격리: 보고서와 맞지 않는 수수료 출금이 커밋된다")
        val (serialReport, serialCommitted) = scenario(Isolation.SERIALIZABLE)
        assertEquals("x=0, y=20", serialReport)
        assertEquals(false, serialCommitted, "SSI: 피벗 T2가 중단된다")
        assertEquals("0", store.begin().use { it.str("x") })
    }

    /**
     * 같은 이상 현상인데 보고서 T1이 **마지막에** 커밋하는 순서: T2(피벗)는 T1이 x를 읽기 전에 커밋해 그 시점엔 들어오는 간선이 없다.
     * T1이 커밋할 때 T1 → T2 간선이 생기고 T2는 이미 나가는 간선(T2 → T3)을 가진 채 커밋됐으므로, 규칙 (C)만 T1을 중단할 수 있다.
     * 읽기 전용 T1을 중단해 "보고서가 직렬화 불가능한 상태를 보여 주는" 것을 막는다.
     */
    @Test
    fun `SERIALIZABLE은 읽기 전용 이상 현상을 보고서가 마지막에 커밋해도 막는다 - 규칙 C`() = withStore { store ->
        store.run { it.put(b("x"), b("0")); it.put(b("y"), b("0")) }
        val t2 = store.begin(Isolation.SERIALIZABLE)
        val total = t2.str("x")!!.toInt() + t2.str("y")!!.toInt()
        store.begin(Isolation.SERIALIZABLE).also { t3 -> t3.put(b("y"), b("${t3.str("y")!!.toInt() + 20}")); t3.commit() }
        val t1 = store.begin(Isolation.SERIALIZABLE)
        t2.put(b("x"), b("${-(if (total < 10) 11 else 10)}"))
        t2.commit()
        val report = "x=${t1.str("x")}, y=${t1.str("y")}"

        assertEquals("x=0, y=20", report, "스냅샷이 보여 주는 값 — 최종 상태 x=-11과 어떤 직렬 순서로도 맞지 않는다")
        assertFailsWith<SerializationFailureException> { t1.commit() }
        assertEquals(0, store.retainedSerializableRecords())
    }

    @Test
    fun `SERIALIZABLE도 겹치지 않는 트랜잭션은 중단하지 않는다`() = withStore { store ->
        val a = store.begin(Isolation.SERIALIZABLE)
        val c = store.begin(Isolation.SERIALIZABLE)
        a.str("a/1")
        a.put(b("a/1"), b("1"))
        c.str("c/1")
        c.put(b("c/1"), b("1"))
        a.commit()
        c.commit()
        store.begin(Isolation.SERIALIZABLE).use { it.str("a/1"); it.commit() }
        assertEquals(0, store.retainedSerializableRecords())
    }

    /** 제약 "x + y ≥ 0"을 지키며 x나 y에서 출금. 스냅샷 격리에서는 write skew로 깨질 수 있는 제약이다. */
    @Test
    fun `SERIALIZABLE 동시 출금 - 여러 행에 걸친 제약이 깨지지 않는다`() = withStore { store ->
        store.run { it.put(b("acct/x"), b("100")); it.put(b("acct/y"), b("100")) }
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { worker ->
            pool.submit {
                val random = Random(worker)
                repeat(40) {
                    val target = if (random.nextBoolean()) "acct/x" else "acct/y"
                    val amount = random.nextInt(1, 30)
                    var attempts = 0
                    store.run(isolation = Isolation.SERIALIZABLE, maxAttempts = 500) { t ->
                        if (attempts++ > 0) failures.incrementAndGet()
                        val x = t.str("acct/x")!!.toInt()
                        val y = t.str("acct/y")!!.toInt()
                        if (x + y >= amount) t.put(b(target), b("${t.str(target)!!.toInt() - amount}"))
                    }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))
        store.begin().use { t -> assertTrue(t.str("acct/x")!!.toInt() + t.str("acct/y")!!.toInt() >= 0, "x + y ≥ 0") }
        assertEquals(0, store.retainedSerializableRecords())
        println("SSI 동시 출금 재시도: ${failures.get()}회")
    }

    // ---- 커밋 기록 상한 (§11.7) ----

    @Test
    fun `오래 열린 트랜잭션이 있어도 보관 기록 수가 상한에서 멈춘다`() = withCappedStore(maxRecords = 50) { store ->
        val idle = store.begin(Isolation.SERIALIZABLE).also { it.str("unrelated") }
        repeat(1_000) { i -> store.run(isolation = Isolation.SERIALIZABLE) { t -> t.str("k$i"); t.put(b("k$i"), b("v")) } }

        assertTrue(store.retainedSerializableRecords() <= 51, "보관 ${store.retainedSerializableRecords()} (상한 50 + 요약 1)")
        assertTrue(store.summarizedRecords() > 900)
        idle.close()
        assertEquals(0, store.retainedSerializableRecords())
    }

    /**
     * 상대(alice)의 기록이 요약 기록으로 합쳐진 뒤에도 write skew가 막혀야 한다 — 요약이 간선을 잃지 않는다는 확인.
     * bob보다 **먼저 끝난** 오래된 기록도 같은 요약에 섞이게 해서, 요약이 겹침 판정(종료 순번)을 늦은 쪽으로 잡는지까지 본다.
     */
    @Test
    fun `요약된 기록으로도 write skew를 막는다`() = withCappedStore(maxRecords = 4) { store ->
        fun onCall(t: Txn) = listOf("alice", "bob").count { t.str("oncall/$it") == "true" }
        store.run { it.put(b("oncall/alice"), b("true")); it.put(b("oncall/bob"), b("true")) }
        val keeper = store.begin(Isolation.SERIALIZABLE).also { it.str("keep") } // 오래된 기록이 버려지지 않게 붙잡는다
        repeat(3) { i -> store.run(isolation = Isolation.SERIALIZABLE) { t -> t.str("old/$i"); t.put(b("old/$i"), b("v")) } }

        val alice = store.begin(Isolation.SERIALIZABLE)
        val bob = store.begin(Isolation.SERIALIZABLE)
        if (onCall(alice) >= 2) alice.put(b("oncall/alice"), b("false"))
        if (onCall(bob) >= 2) bob.put(b("oncall/bob"), b("false"))
        alice.commit()
        repeat(20) { i -> store.run(isolation = Isolation.SERIALIZABLE) { t -> t.str("other/$i"); t.put(b("other/$i"), b("v")) } }
        assertTrue(store.summarizedRecords() > 0, "alice의 기록이 요약되어야 이 테스트가 의미 있다")

        assertFailsWith<SerializationFailureException> { bob.commit() }
        assertEquals(1, store.begin().use { onCall(it) })
        keeper.close()
    }

    /**
     * 규칙 (C) + 요약: 읽기 전용 이상 현상에서 피벗 T2와 입금 T3의 기록이 모두 요약된 뒤 보고서 T1이 커밋한다.
     * T1을 막으려면 요약이 T2의 쓰기(x), T2의 나가는 충돌 표시, 그리고 T1 시작 이후의 커밋 시각(늦은 쪽)을 모두 보존해야 한다.
     * 요약에는 T1 시작 전에 커밋한 T3도 섞이므로, 시각을 이른 쪽으로 잡으면 간선을 놓친다.
     */
    @Test
    fun `요약된 기록으로도 읽기 전용 이상 현상을 막는다 - 규칙 C`() = withCappedStore(maxRecords = 4) { store ->
        store.run { it.put(b("x"), b("0")); it.put(b("y"), b("0")) }
        val keeper = store.begin(Isolation.SERIALIZABLE).also { it.str("keep") }
        val t2 = store.begin(Isolation.SERIALIZABLE)
        val total = t2.str("x")!!.toInt() + t2.str("y")!!.toInt()
        store.begin(Isolation.SERIALIZABLE).also { t3 -> t3.put(b("y"), b("${t3.str("y")!!.toInt() + 20}")); t3.commit() }
        val t1 = store.begin(Isolation.SERIALIZABLE)
        t2.put(b("x"), b("${-(if (total < 10) 11 else 10)}"))
        t2.commit()
        repeat(20) { i -> store.run(isolation = Isolation.SERIALIZABLE) { t -> t.str("other/$i"); t.put(b("other/$i"), b("v")) } }
        assertTrue(store.summarizedRecords() >= 2, "T3, T2의 기록이 요약되어야 이 테스트가 의미 있다")

        assertEquals("x=0, y=20", "x=${t1.str("x")}, y=${t1.str("y")}")
        assertFailsWith<SerializationFailureException> { t1.commit() }
        keeper.close()
    }

    /** 읽기 집합이 키 수 상한을 넘어 범위로 거칠게 바뀐 기록도 write skew를 막는다. */
    @Test
    fun `키 수 상한으로 거칠게 만든 기록으로도 write skew를 막는다`() = withCappedStore(maxRecords = 1_000, maxKeys = 5) { store ->
        val doctors = (0 until 20).map { "oncall/d%02d".format(it) }
        fun onCall(t: Txn) = doctors.count { t.str(it) == "true" }
        store.run { t -> doctors.take(2).forEach { t.put(b(it), b("true")) }; doctors.drop(2).forEach { t.put(b(it), b("false")) } }

        val a = store.begin(Isolation.SERIALIZABLE)
        val c = store.begin(Isolation.SERIALIZABLE)
        if (onCall(a) >= 2) a.put(b(doctors[0]), b("false"))
        if (onCall(c) >= 2) c.put(b(doctors[1]), b("false"))
        a.commit() // a의 읽기 20개 > 상한 5 → 기록은 범위 하나
        assertFailsWith<SerializationFailureException> { c.commit() }
        assertEquals(1, store.begin().use { onCall(it) })
    }

    /** 로드맵 M7 완료 조건: 동시 이체에서 잔액 합계 보존. */
    @Test
    fun `동시 이체 - 스레드 8개가 이체해도 합계가 보존되고 모든 스냅샷이 같은 합계를 본다`() = withStore { store ->
        val accounts = 20
        val initial = 1_000L
        fun key(i: Int) = b("acct/%02d".format(i))
        fun Txn.balance(i: Int) = ByteBuffer.wrap(get(key(i))!!).getLong()
        fun Txn.setBalance(i: Int, v: Long) = put(key(i), ByteBuffer.allocate(8).putLong(v).array())
        store.run { t -> repeat(accounts) { t.setBalance(it, initial) } }

        val pool = Executors.newFixedThreadPool(8)
        val snapshotSums = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        repeat(8) { worker ->
            pool.submit {
                val random = Random(worker)
                repeat(200) {
                    val from = random.nextInt(accounts)
                    val to = (from + 1 + random.nextInt(accounts - 1)) % accounts
                    val amount = random.nextLong(1, 50)
                    store.run { t ->
                        val fromBalance = t.balance(from)
                        if (fromBalance >= amount) {
                            t.setBalance(from, fromBalance - amount)
                            t.setBalance(to, t.balance(to) + amount)
                        }
                    }
                    if (it % 20 == 0) store.begin().use { t -> snapshotSums += (0 until accounts).sumOf { a -> t.balance(a) } }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))

        store.begin().use { t ->
            assertEquals(accounts * initial, (0 until accounts).sumOf { t.balance(it) })
            assertTrue((0 until accounts).all { t.balance(it) >= 0 })
        }
        assertTrue(snapshotSums.isNotEmpty())
        assertTrue(snapshotSums.all { it == accounts * initial }, "모든 스냅샷이 처음 합계를 봐야 한다: ${snapshotSums.toSet()}")
    }
}

class LsmTxnTest : TxnTestBase() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class LogTxnTest : TxnTestBase() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}

class BTreeTxnTest : TxnTestBase() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 64, syncPolicy = SyncPolicy.MANUAL))
}

// ---- 공통 하네스: MVCC 계층을 KvEngine으로 보고 M1부터 쓴 계약·무작위·크래시 테스트를 그대로 적용 ----

class MvccOnLsmContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = MvccKvEngine(LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 512)))
}

class MvccOnBTreeContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = MvccKvEngine(BTreeEngine.open(BTreeOptions(dir, pageSize = 512, bufferPoolPages = 16)))
}

class MvccOnLogContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = MvccKvEngine(LogEngine.open(LogEngineOptions(dir, segmentSizeBytes = 512)))
}

class MvccRandomTest : KvEngineRandomTest() {
    override fun openEngine(dir: Path): KvEngine = MvccKvEngine(LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 2048, syncPolicy = SyncPolicy.MANUAL)))

    override fun maintenance(engine: KvEngine) {
        (engine as MvccKvEngine).store.gc()
    }
}

class MvccCrashTest : KvEngineCrashTest() {
    override val factory = MvccCrashFactory::class
}

/** MVCC over LSM. 유지 작업 = GC. 커밋 배치 하나 = 트랜잭션 하나가 원자적인지 kill -9로 확인한다. */
class MvccCrashFactory : CrashEngineFactory {
    override fun open(dataDir: Path, durable: Boolean): KvEngine = MvccKvEngine(
        LsmEngine.open(LsmOptions(dataDir, memtableSizeBytes = 2048, syncPolicy = if (durable) SyncPolicy.EVERY_WRITE else SyncPolicy.MANUAL)),
    )

    override fun maintenance(engine: KvEngine) {
        (engine as MvccKvEngine).store.gc()
    }
}

class MvccStatsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `MVCC 어댑터를 거친 값도 아래 엔진에 버전 키로 저장된다`() {
        val engine = MvccKvEngine(LsmEngine.open(LsmOptions(dir)))
        engine.use {
            it.put(b("k"), b("v1"))
            it.put(b("k"), b("v2"))
            assertContentEquals(b("v2"), it.get(b("k")))
            assertEquals(2, it.store.versionCount())
        }
    }
}
