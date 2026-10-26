package dev.mydb.document

import dev.mydb.document.DocumentStore.Bound
import dev.mydb.document.DocumentStore.Filter
import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 문서 계층은 KvEngine에만 의존하므로 세 엔진 위에서 똑같이 동작해야 한다. */
abstract class DocumentStoreContractTest {
    @TempDir
    lateinit var dir: Path

    abstract fun openEngine(dir: Path): KvEngine

    /** true면 같은 테스트를 MVCC 트랜잭션 모드(연산마다 자동 커밋)에서 돌린다. 결과는 같아야 한다. */
    open val transactional: Boolean = false

    private fun obj(json: String) = Json.parseObject(json)

    private fun num(n: Number) = JsonValue.Num(n.toDouble())

    private fun str(s: String) = JsonValue.Str(s)

    private fun ids(docs: List<DocumentStore.Document>) = docs.map { it.id }

    /** Unit만 받는다. 테스트 메서드가 값을 돌려주면 JUnit이 테스트로 인식하지 않고 조용히 건너뛴다. */
    private fun withStore(block: (DocumentStore) -> Unit) = openEngine(dir).use { block(DocumentStore(it, transactional)) }

    @Test
    fun `구조가 서로 다른 문서들을 한 컬렉션에서 필터 조회한다`() = withStore { store ->
        val users = store.createCollection("users")
        users.insert("u1", obj("""{"name":"kim","age":31,"addr":{"city":"Seoul"}}"""))
        users.insert("u2", obj("""{"name":"lee","tags":["admin"],"addr":{"city":"Busan","zip":"48058"}}"""))
        users.insert("u3", obj("""{"nickname":"park","age":"unknown"}"""))
        users.insert("u4", obj("""{"name":"choi","age":27,"addr":"Seoul"}"""))

        assertEquals(listOf("u1"), ids(users.find(Filter.Eq("addr.city", str("Seoul")))))
        assertEquals(listOf("u1", "u4"), ids(users.find(Filter.Range("age", Bound(num(20), true), null))))
        assertEquals(listOf("u2"), ids(users.find(Filter.Exists("tags"))))
        assertEquals(listOf("u1"), ids(users.find(Filter.And(listOf(Filter.Exists("name"), Filter.Range("age", Bound(num(30), false), null))))))
    }

    @Test
    fun `스키마 없는 필터 의미 - 없는 필드, null, 타입 불일치, 경로 중간이 객체가 아님`() = withStore { store ->
        val c = store.createCollection("c")
        c.insert("missing", obj("""{"other":1}"""))
        c.insert("null", obj("""{"v":null}"""))
        c.insert("string", obj("""{"v":"20"}"""))
        c.insert("number", obj("""{"v":20}"""))
        c.insert("array", obj("""{"v":[20]}"""))
        c.insert("nested-string", obj("""{"a":"x"}"""))

        assertEquals(listOf("null"), ids(c.find(Filter.Eq("v", JsonValue.Null))))
        assertEquals(listOf("number"), ids(c.find(Filter.Range("v", Bound(num(10), true), Bound(num(30), true)))))
        assertEquals(listOf("string"), ids(c.find(Filter.Range("v", Bound(str("1"), true), null))))
        assertEquals(listOf("array"), ids(c.find(Filter.Eq("v", JsonValue.Arr(listOf(num(20)))))))
        assertEquals(emptyList(), ids(c.find(Filter.Eq("a.b", str("x")))))
        assertEquals(listOf("array", "null", "number", "string"), ids(c.find(Filter.Exists("v"))))
    }

    @Test
    fun `인덱스가 있으면 IXSCAN, 없으면 COLLSCAN이고 결과는 같다`() = withStore { store ->
        val plain = store.createCollection("plain")
        val indexed = store.createCollection("indexed")
        repeat(300) { i ->
            val doc = obj("""{"age":${i % 50},"city":"${listOf("Seoul", "Busan", "Incheon")[i % 3]}"}""")
            plain.insert("d%03d".format(i), doc)
            indexed.insert("d%03d".format(i), doc)
        }
        indexed.createIndex("age")
        indexed.createIndex("city")

        val filters = listOf(
            Filter.Eq("city", str("Busan")),
            Filter.Range("age", Bound(num(10), true), Bound(num(20), false)),
            Filter.Range("age", null, Bound(num(5), true)),
            Filter.And(listOf(Filter.Eq("city", str("Seoul")), Filter.Range("age", Bound(num(40), false), null))),
        )
        for (f in filters) {
            assertEquals(ids(plain.find(f)), ids(indexed.find(f)), "$f")
            assertEquals("COLLSCAN", plain.explain(f))
            assertTrue(indexed.explain(f).startsWith("IXSCAN"), indexed.explain(f))
        }
        assertEquals("IXSCAN city [= \"Seoul\"]", indexed.explain(filters[3]))
        assertEquals(300, indexed.indexEntryCount("age"))
    }

    @Test
    fun `교체와 삭제가 인덱스 항목을 함께 바꾼다`() = withStore { store ->
        val c = store.createCollection("c")
        c.createIndex("city")
        c.insert("a", obj("""{"city":"Seoul"}"""))
        c.replace("a", obj("""{"city":"Busan"}"""))
        c.insert("b", obj("""{"city":"Seoul"}"""))
        c.replace("b", obj("""{"name":"no-city"}"""))
        c.insert("d", obj("""{"city":"Jeju"}"""))
        assertTrue(c.delete("d"))

        assertEquals(emptyList(), ids(c.find(Filter.Eq("city", str("Seoul")))))
        assertEquals(listOf("a"), ids(c.find(Filter.Eq("city", str("Busan")))))
        assertEquals(1, c.indexEntryCount("city"), "고아 인덱스 항목이 없어야 한다")
        assertNull(c.get("d"))
    }

    @Test
    fun `재오픈 후 컬렉션·인덱스·문서가 남는다`() {
        withStore { store ->
            val c = store.createCollection("c")
            c.createIndex("k")
            c.insert("x", obj("""{"k":"v","n":{"deep":[1,2,{"z":null}]}}"""))
        }
        withStore { store ->
            val c = store.collection("c")!!
            assertEquals(obj("""{"k":"v","n":{"deep":[1,2,{"z":null}]}}"""), c.get("x"))
            assertEquals("IXSCAN k [= \"v\"]", c.explain(Filter.Eq("k", str("v"))))
            assertEquals(listOf("x"), ids(c.find(Filter.Eq("k", str("v")))))
            assertNull(store.collection("nope"))
        }
    }

    @Test
    fun `중복 삽입과 중복 생성은 거부한다`() = withStore { store ->
        val c = store.createCollection("c")
        c.insert("x", obj("{}"))
        assertFailsWith<IllegalStateException> { c.insert("x", obj("{}")) }
        assertFailsWith<IllegalStateException> { store.createCollection("c") }
        c.createIndex("a")
        assertFailsWith<IllegalStateException> { c.createIndex("a") }
    }

    /**
     * 무작위 삽입·교체·삭제 뒤, 같은 질의를 인덱스 있는 컬렉션과 없는 컬렉션에 던져 결과가 같아야 한다.
     * 인덱스 항목 수 = 그 필드가 스칼라인 문서 수 (고아 항목·누락 항목 없음).
     */
    @Test
    fun `무작위 변경 후에도 인덱스 질의와 전체 스캔이 같다`() = withStore { store ->
        val random = Random(7)
        val plain = store.createCollection("plain")
        val indexed = store.createCollection("indexed")
        indexed.createIndex("age")
        indexed.createIndex("addr.city")
        val cities = listOf("Seoul", "Busan", "Incheon", "Daegu")

        fun randomDoc(): JsonObject {
            val fields = LinkedHashMap<String, JsonValue>()
            when (random.nextInt(4)) {
                0 -> fields["age"] = num(random.nextInt(0, 60))
                1 -> fields["age"] = str("${random.nextInt(0, 60)}")
                2 -> fields["age"] = JsonValue.Null
                else -> Unit
            }
            when (random.nextInt(3)) {
                0 -> fields["addr"] = JsonValue.Obj(mapOf("city" to str(cities.random(random))))
                1 -> fields["addr"] = str("flat")
                else -> Unit
            }
            return JsonValue.Obj(fields)
        }

        repeat(1_500) {
            val id = "d%03d".format(random.nextInt(200))
            when (random.nextInt(10)) {
                in 0..6 -> randomDoc().let { plain.replace(id, it); indexed.replace(id, it) }
                else -> { plain.delete(id); indexed.delete(id) }
            }
        }

        repeat(200) {
            val f = when (random.nextInt(3)) {
                0 -> Filter.Eq("addr.city", str(cities.random(random)))
                1 -> Filter.Range("age", Bound(num(random.nextInt(60)), random.nextBoolean()), Bound(num(random.nextInt(60)), random.nextBoolean()))
                else -> Filter.Range("age", Bound(str("${random.nextInt(6)}"), true), null)
            }
            assertEquals(ids(plain.find(f)), ids(indexed.find(f)), "$f")
        }
        val all = plain.find(Filter.Exists("age")) + plain.find(Filter.Exists("addr"))
        val scalarAge = plain.find(Filter.Exists("age")).count { it.body.at("age") !is JsonValue.Arr && it.body.at("age") !is JsonValue.Obj }
        val scalarCity = plain.find(Filter.Exists("addr.city")).size
        assertTrue(all.isNotEmpty())
        assertEquals(scalarAge, indexed.indexEntryCount("age"))
        assertEquals(scalarCity, indexed.indexEntryCount("addr.city"))
    }
}

class LogDocumentStoreTest : DocumentStoreContractTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}

class LsmDocumentStoreTest : DocumentStoreContractTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class BTreeDocumentStoreTest : DocumentStoreContractTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 32, syncPolicy = SyncPolicy.MANUAL))
}

// ---- 같은 계약을 트랜잭션 모드(MVCC 위, 연산마다 자동 커밋)에서 ----

class TxLogDocumentStoreTest : DocumentStoreContractTest() {
    override val transactional = true

    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}

class TxLsmDocumentStoreTest : DocumentStoreContractTest() {
    override val transactional = true

    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class TxBTreeDocumentStoreTest : DocumentStoreContractTest() {
    override val transactional = true

    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 32, syncPolicy = SyncPolicy.MANUAL))
}
