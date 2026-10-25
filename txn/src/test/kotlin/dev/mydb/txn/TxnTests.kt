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
