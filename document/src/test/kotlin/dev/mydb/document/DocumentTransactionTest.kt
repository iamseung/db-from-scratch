package dev.mydb.document

import dev.mydb.document.DocumentStore.Filter
import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import dev.mydb.txn.WriteConflictException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 다중 문서 트랜잭션. 스냅샷 격리 + 인덱스 정의에 대한 읽기 검증. */
abstract class DocumentTransactionTest {
    @TempDir
    lateinit var dir: Path

    abstract fun openEngine(dir: Path): KvEngine

    private fun withStore(block: (DocumentStore) -> Unit) = openEngine(dir).use { block(DocumentStore(it, transactional = true)) }

    private fun obj(json: String) = Json.parseObject(json)

    private fun str(s: String) = JsonValue.Str(s)

    @Test
    fun `여러 문서를 원자적으로 커밋하고 커밋 전에는 다른 쪽에 보이지 않는다`() = withStore { store ->
        val orders = store.createCollection("orders")
        val tx = store.begin()
        val txOrders = tx.collection("orders")!!
        txOrders.insert("o1", obj("""{"total":100}"""))
        txOrders.insert("o2", obj("""{"total":200}"""))

        assertEquals(obj("""{"total":100}"""), txOrders.get("o1"), "자기 쓰기는 보인다")
        assertNull(orders.get("o1"))
        tx.commit()
        assertEquals(2, orders.find(Filter.Exists("total")).size)
    }

    @Test
    fun `ROLLBACK은 문서·컬렉션·인덱스 생성을 모두 되돌린다`() = withStore { store ->
        store.createCollection("c").insert("a", obj("""{"x":1}"""))
        store.begin().use { tx ->
            tx.collection("c")!!.delete("a")
            tx.collection("c")!!.createIndex("x")
            tx.createCollection("new").insert("n", obj("{}"))
            tx.rollback()
        }
        assertEquals(obj("""{"x":1}"""), store.collection("c")!!.get("a"))
        assertNull(store.collection("new"))
        assertEquals("COLLSCAN", store.collection("c")!!.explain(Filter.Eq("x", JsonValue.Num(1.0))))
    }

    @Test
    fun `트랜잭션은 시작 시점의 스냅샷을 본다`() = withStore { store ->
        val c = store.createCollection("c")
        c.insert("a", obj("""{"v":1}"""))
        val tx = store.begin()
        c.replace("a", obj("""{"v":2}"""))
        c.insert("b", obj("""{"v":3}"""))

        assertEquals(obj("""{"v":1}"""), tx.collection("c")!!.get("a"))
        assertEquals(listOf("a"), tx.collection("c")!!.find(Filter.Exists("v")).map { it.id })
        tx.close()
    }

    @Test
    fun `같은 문서를 바꾼 두 트랜잭션 중 나중 커밋은 충돌하고 transaction은 재시도한다`() = withStore { store ->
        store.createCollection("acct").insert("a", obj("""{"balance":100}"""))
        val t1 = store.begin()
        val t2 = store.begin()
        t1.collection("acct")!!.replace("a", obj("""{"balance":110}"""))
        t2.collection("acct")!!.replace("a", obj("""{"balance":120}"""))
        t1.commit()
        assertFailsWith<WriteConflictException> { t2.commit() }

        var attempts = 0
        val blocker = store.begin() // 첫 시도를 일부러 충돌시킨다
        blocker.collection("acct")!!.replace("a", obj("""{"balance":0}"""))
        store.transaction { tx ->
            attempts++
            val c = tx.collection("acct")!!
            val balance = (c.get("a")!!.at("balance") as JsonValue.Num).value
            if (attempts == 1) blocker.commit()
            c.replace("a", obj("""{"balance":${(balance + 5).toInt()}}"""))
        }
        assertEquals(2, attempts)
        assertEquals(obj("""{"balance":5}"""), store.collection("acct")!!.get("a"), "재시도는 blocker가 쓴 0을 보고 +5")
    }

    /** M8 D2: 고객 정보를 주문마다 복제한 경우, 이름 변경을 트랜잭션으로 묶으면 읽는 쪽이 섞인 상태를 보지 않는다. */
    @Test
    fun `복제된 고객 이름 변경 - 읽는 쪽은 옛 이름 또는 새 이름만 보고 섞인 상태를 보지 않는다`() = withStore { store ->
        val orders = store.createCollection("orders")
        orders.createIndex("customer.id")
        repeat(10) { orders.insert("o$it", obj("""{"customer":{"id":7,"name":"n0"},"total":$it}""")) }
        val stop = AtomicBoolean(false)
        val mixed = AtomicInteger()
        val reads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        pool.submit {
            for (round in 1..60) {
                store.transaction { tx ->
                    val c = tx.collection("orders")!!
                    c.find(Filter.Eq("customer.id", JsonValue.Num(7.0))).forEach { d ->
                        val customer = (d.body.fields["customer"] as JsonValue.Obj).fields + ("name" to str("n$round"))
                        c.replace(d.id, JsonValue.Obj(d.body.fields + ("customer" to JsonValue.Obj(customer))))
                    }
                }
            }
            stop.set(true)
        }
        repeat(2) {
            pool.submit {
                while (!stop.get()) {
                    store.begin().use { tx ->
                        val names = tx.collection("orders")!!.find(Filter.Eq("customer.id", JsonValue.Num(7.0))).map { it.body.at("customer.name") }.toSet()
                        if (names.size != 1) mixed.incrementAndGet()
                        reads.incrementAndGet()
                    }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))
        assertTrue(reads.get() > 0)
        assertEquals(0, mixed.get(), "${reads.get()}번 읽는 동안 이름이 섞인 스냅샷이 있었다")
        assertEquals(setOf<JsonValue?>(str("n60")), orders.find(Filter.Exists("total")).map { it.body.at("customer.name") }.toSet())
    }

    @Test
    fun `동시 수정 후에도 인덱스 항목과 문서가 일치한다`() = withStore { store ->
        val c = store.createCollection("c")
        c.createIndex("city")
        val cities = listOf("Seoul", "Busan", "Jeju")
        val pool = Executors.newFixedThreadPool(6)
        repeat(6) { worker ->
            pool.submit {
                val random = Random(worker)
                repeat(80) {
                    store.transaction { tx ->
                        val col = tx.collection("c")!!
                        repeat(2) {
                            val id = "d%02d".format(random.nextInt(30))
                            if (random.nextInt(4) == 0) col.delete(id) else col.replace(id, obj("""{"city":"${cities.random(random)}"}"""))
                        }
                    }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))

        val all = c.find(Filter.Exists("city"))
        assertEquals(all.size, c.indexEntryCount("city"), "고아·누락 인덱스 항목이 없어야 한다")
        for (city in cities) {
            assertEquals(all.filter { it.body.at("city") == str(city) }.map { it.id }, c.find(Filter.Eq("city", str(city))).map { it.id })
        }
    }

    /**
     * 스냅샷 격리만으로는 생기는 이상 현상: 인덱스를 만드는 트랜잭션과 문서를 쓰는 트랜잭션은 서로 다른 키를 쓰므로
     * 둘 다 커밋되고, 그 문서가 인덱스에서 빠진다. 인덱스 버전 키·문서 범위를 지켜봐서 나중 커밋을 충돌로 만든다.
     */
    @Test
    fun `인덱스 생성 중 다른 트랜잭션이 쓴 문서가 인덱스에서 빠지지 않는다 - 양방향`() = withStore { store ->
        val c = store.createCollection("c")
        c.insert("old", obj("""{"city":"Seoul"}"""))

        // 1) 인덱스를 만드는 동안 다른 쪽이 문서를 커밋 → 인덱스 쪽 커밋이 충돌
        val indexer = store.begin()
        indexer.collection("c")!!.createIndex("city")
        c.insert("new", obj("""{"city":"Seoul"}"""))
        assertFailsWith<WriteConflictException> { indexer.commit() }
        store.transaction { it.collection("c")!!.createIndex("city") }
        assertEquals(2, c.indexEntryCount("city"))

        // 2) 문서를 쓰는 동안 다른 쪽이 인덱스를 커밋 → 문서 쪽 커밋이 충돌
        val writer = store.begin()
        writer.collection("c")!!.insert("late", obj("""{"city":"Seoul","zip":"1"}"""))
        c.createIndex("zip")
        assertFailsWith<WriteConflictException> { writer.commit() }
        store.transaction { it.collection("c")!!.insert("late", obj("""{"city":"Seoul","zip":"1"}""")) }
        assertEquals(1, c.indexEntryCount("zip"))
        assertEquals(listOf("late"), c.find(Filter.Eq("zip", str("1"))).map { it.id })
    }

    @Test
    fun `비트랜잭션 모드에서는 begin을 쓸 수 없다`() {
        LsmEngine.open(LsmOptions(dir.resolve("raw"))).use { engine ->
            assertFailsWith<IllegalStateException> { DocumentStore(engine).begin() }
        }
    }
}

class LsmDocumentTransactionTest : DocumentTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class BTreeDocumentTransactionTest : DocumentTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 64, syncPolicy = SyncPolicy.MANUAL))
}

class LogDocumentTransactionTest : DocumentTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}
