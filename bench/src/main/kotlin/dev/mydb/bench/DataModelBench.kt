package dev.mydb.bench

import dev.mydb.document.DocumentStore
import dev.mydb.document.DocumentStore.Filter
import dev.mydb.document.Json
import dev.mydb.document.JsonValue
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.EngineStats
import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEntry
import dev.mydb.kv.KvIterator
import dev.mydb.kv.WriteOp
import dev.mydb.sql.Database
import dev.mydb.storage.SyncPolicy
import kotlin.system.measureNanoTime

/**
 * M8 데이터 모델 비교 (docs/detailed/m8-data-models.md). 실행: ./gradlew :bench:run --args=m8
 * 같은 도메인을 같은 LSM 엔진 위의 관계형(M6)·문서형(M5)에 저장하고, 저장 엔진에 요청하는 KV 연산 수와 시간을 비교한다.
 */
fun runDataModelBench() {
    println("# M8 데이터 모델 비교: 관계형 vs 문서형 (같은 LSM 엔진)\n")
    println("데이터: 고객 $CUSTOMERS, 주문 $ORDERS, 주문당 항목 1~5개. 문서형은 주문 문서에 고객 정보와 항목을 임베딩한다.\n")
    withTempDir { relDir ->
        withTempDir { docDir ->
            CountingKvEngine(LsmEngine.open(LsmOptions(relDir, syncPolicy = SyncPolicy.MANUAL))).use { relEngine ->
                CountingKvEngine(LsmEngine.open(LsmOptions(docDir, syncPolicy = SyncPolicy.MANUAL))).use { docEngine ->
                    val db = Database(relEngine)
                    val docs = DocumentStore(docEngine)
                    load(db, docs)
                    storageSize(relEngine, docEngine)
                    readOrder(db, docs, relEngine, docEngine)
                    renameCustomer(db, docs, relEngine, docEngine)
                    addColumn(db, docs, relEngine, docEngine)
                }
            }
        }
    }
}

private const val CUSTOMERS = 2_000
private const val ORDERS = 20_000
private val CITY = listOf("Seoul", "Busan", "Incheon", "Daegu", "Jeju")

private fun itemCount(order: Int) = 1 + order * 7 % 5

private fun customerOf(order: Int) = order % CUSTOMERS

private fun price(order: Int, line: Int) = 100 + (order * 31 + line * 17) % 900

private fun total(order: Int) = (0 until itemCount(order)).sumOf { price(order, it) * (1 + it % 3) }

private fun load(db: Database, docs: DocumentStore) {
    db.execute("CREATE TABLE customers (id INT PRIMARY KEY, name TEXT NOT NULL, city TEXT)")
    db.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL, total INT)")
    db.execute("CREATE TABLE items (id INT PRIMARY KEY, order_id INT NOT NULL, product TEXT, qty INT, price INT)")
    db.execute("CREATE INDEX idx_items_order ON items (order_id)")
    db.execute("CREATE INDEX idx_orders_customer ON orders (customer_id)")
    (0 until CUSTOMERS).chunked(500).forEach { chunk ->
        db.execute("INSERT INTO customers VALUES " + chunk.joinToString(", ") { "($it, 'customer-$it', '${CITY[it % 5]}')" })
    }
    (0 until ORDERS).chunked(500).forEach { chunk ->
        db.execute("INSERT INTO orders VALUES " + chunk.joinToString(", ") { "($it, ${customerOf(it)}, ${total(it)})" })
        db.execute(
            "INSERT INTO items VALUES " + chunk.flatMap { o ->
                (0 until itemCount(o)).map { l -> "(${o * 10 + l}, $o, 'p-${(o + l) % 50}', ${1 + l % 3}, ${price(o, l)})" }
            }.joinToString(", "),
        )
    }

    val orders = docs.createCollection("orders")
    orders.createIndex("customer.id")
    repeat(ORDERS) { o ->
        val c = customerOf(o)
        val items = (0 until itemCount(o)).joinToString(",") { l -> """{"product":"p-${(o + l) % 50}","qty":${1 + l % 3},"price":${price(o, l)}}""" }
        orders.insert(
            "%06d".format(o),
            Json.parseObject("""{"customer":{"id":$c,"name":"customer-$c","city":"${CITY[c % 5]}"},"total":${total(o)},"items":[$items]}"""),
        )
    }
}

private fun storageSize(rel: CountingKvEngine, doc: CountingKvEngine) {
    println("## D4. 저장 크기 (KV에 쓴 키 + 값 바이트, 적재 직후)\n")
    println("| 모델 | 구분 | KV 항목 수 | 키 바이트 | 값 바이트 | 합계 |")
    println("|---|---|---:|---:|---:|---:|")
    val kinds = mapOf('r' to "행", 'i' to "보조 인덱스", 's' to "카탈로그", 'd' to "문서", 'x' to "보조 인덱스", 'm' to "메타데이터")
    for ((name, engine) in listOf("관계형" to rel, "문서형" to doc)) {
        val groups = engine.scan(ByteArray(0), null).use { iterator ->
            iterator.asSequence().groupBy({ kinds[it.key[0].toInt().toChar()] ?: "기타" }, { it.key.size.toLong() to it.value.size.toLong() })
        }
        var totalCount = 0
        var totalBytes = 0L
        for ((kind, entries) in groups.entries.sortedByDescending { it.value.size }) {
            val keyBytes = entries.sumOf { it.first }
            val valueBytes = entries.sumOf { it.second }
            totalCount += entries.size
            totalBytes += keyBytes + valueBytes
            println("| $name | $kind | ${fmt(entries.size)} | ${mib(keyBytes)} | ${mib(valueBytes)} | ${mib(keyBytes + valueBytes)} |")
        }
        println("| $name | **합계** | ${fmt(totalCount)} | | | **${mib(totalBytes)}** |")
    }
    println()
}

private fun readOrder(db: Database, docs: DocumentStore, rel: CountingKvEngine, doc: CountingKvEngine) {
    println("## D1. 주문 하나 + 그 항목 읽기 (주문 1,000건 평균)\n")
    val targets = (0 until 1_000).map { it * 19 % ORDERS }
    val relSql = { o: Int -> "SELECT o.id, o.total, i.product, i.qty, i.price FROM orders o JOIN items i ON o.id = i.order_id WHERE o.id = $o" }
    println("관계형 플랜:\n```\n" + (db.execute("EXPLAIN ${relSql(0)}") as dev.mydb.sql.Result.Plan).text + "\n```\n")
    rel.reset()
    val relNanos = measureNanoTime { targets.forEach { db.query(relSql(it)) } }
    val relOps = rel.snapshot()
    val orders = docs.collection("orders")!!
    doc.reset()
    val docNanos = measureNanoTime { targets.forEach { orders.get("%06d".format(it)) } }
    val docOps = doc.snapshot()
    println("| 모델 | 1건당 get | 1건당 scan | 1건당 scan 항목 | 1건당 시간 |")
    println("|---|---:|---:|---:|---:|")
    println("| 관계형 (JOIN) | ${relOps.per(1_000) { gets }} | ${relOps.per(1_000) { scans }} | ${relOps.per(1_000) { scanned }} | ${"%.1f".format(relNanos / 1e3 / 1_000)} µs |")
    println("| 문서형 (임베딩) | ${docOps.per(1_000) { gets }} | ${docOps.per(1_000) { scans }} | ${docOps.per(1_000) { scanned }} | ${"%.1f".format(docNanos / 1e3 / 1_000)} µs |")
    println()
}

private fun renameCustomer(db: Database, docs: DocumentStore, rel: CountingKvEngine, doc: CountingKvEngine) {
    println("## D2. 고객 이름 바꾸기 (고객 100명 평균, 고객당 주문 ${ORDERS / CUSTOMERS}건)\n")
    val customers = (0 until 100).map { it * 13 % CUSTOMERS }
    rel.reset()
    val relNanos = measureNanoTime { customers.forEach { db.execute("UPDATE customers SET name = 'renamed-$it' WHERE id = $it") } }
    val relOps = rel.snapshot()
    val orders = docs.collection("orders")!!
    doc.reset()
    val docNanos = measureNanoTime {
        customers.forEach { c ->
            // 고객 정보를 주문마다 복제해 두었으므로 그 고객의 주문 문서를 전부 고쳐 써야 한다
            orders.find(Filter.Eq("customer.id", JsonValue.Num(c.toDouble()))).forEach { d ->
                val customer = (d.body.fields["customer"] as JsonValue.Obj).fields + ("name" to JsonValue.Str("renamed-$c"))
                orders.replace(d.id, JsonValue.Obj(d.body.fields + ("customer" to JsonValue.Obj(customer))))
            }
        }
    }
    val docOps = doc.snapshot()
    println("| 모델 | 1명당 쓴 KV 연산 | 1명당 쓴 바이트 | 1명당 읽기 (get + scan 항목) | 1명당 시간 |")
    println("|---|---:|---:|---:|---:|")
    println("| 관계형 | ${relOps.per(100) { writes }} | ${fmt(relOps.bytesWritten / 100)} B | ${relOps.per(100) { gets + scanned }} | ${"%.1f".format(relNanos / 1e3 / 100)} µs |")
    println("| 문서형 | ${docOps.per(100) { writes }} | ${fmt(docOps.bytesWritten / 100)} B | ${docOps.per(100) { gets + scanned }} | ${"%.1f".format(docNanos / 1e3 / 100)} µs |")
    println()
}

private fun addColumn(db: Database, docs: DocumentStore, rel: CountingKvEngine, doc: CountingKvEngine) {
    println("## D3. 필드 추가 (주문에 배송 메모 추가)\n")
    rel.reset()
    val relNanos = measureNanoTime { db.execute("ALTER TABLE orders ADD COLUMN memo TEXT") }
    val relOps = rel.snapshot()
    val orders = docs.collection("orders")!!
    doc.reset()
    val docNanos = measureNanoTime {
        // 스키마가 없으니 할 일이 없다. 새 문서만 memo를 가지고, 읽는 쪽이 "필드 없음"을 처리한다
        orders.insert("new-order", Json.parseObject("""{"customer":{"id":1,"name":"x","city":"Seoul"},"total":0,"items":[],"memo":"문 앞"}"""))
    }
    val docOps = doc.snapshot()
    println("| 모델 | 쓴 KV 연산 | 쓴 바이트 | 시간 |")
    println("|---|---:|---:|---:|")
    println("| 관계형 (ALTER TABLE, 모든 행 재작성) | ${fmt(relOps.writes)} | ${mib(relOps.bytesWritten)} | ${"%.1f".format(relNanos / 1e6)} ms |")
    println("| 문서형 (새 문서 1개에만 필드) | ${fmt(docOps.writes)} | ${fmt(docOps.bytesWritten)} B | ${"%.1f".format(docNanos / 1e6)} ms |")
    println("\n대신 문서형은 이후 모든 읽기가 memo가 없는 문서(${fmt(ORDERS)}건)와 있는 문서를 함께 다뤄야 한다.\n")
}

/** 저장 엔진에 요청한 연산을 센다. 두 데이터 모델을 같은 단위로 비교하기 위한 측정 도구다. */
class CountingKvEngine(private val engine: KvEngine) : KvEngine {
    class Counts(val gets: Long, val scans: Long, val scanned: Long, val writes: Long, val bytesWritten: Long) {
        fun per(n: Int, metric: Counts.() -> Long) = "%.1f".format(metric().toDouble() / n)
    }

    private var gets = 0L
    private var scans = 0L
    private var scanned = 0L
    private var writes = 0L
    private var bytesWritten = 0L

    fun reset() {
        gets = 0; scans = 0; scanned = 0; writes = 0; bytesWritten = 0
    }

    fun snapshot() = Counts(gets, scans, scanned, writes, bytesWritten)

    override fun get(key: ByteArray): ByteArray? = engine.get(key).also { gets++ }

    override fun put(key: ByteArray, value: ByteArray) {
        writes++
        bytesWritten += key.size + value.size
        engine.put(key, value)
    }

    override fun delete(key: ByteArray) {
        writes++
        bytesWritten += key.size
        engine.delete(key)
    }

    override fun writeBatch(ops: List<WriteOp>) {
        writes += ops.size
        bytesWritten += ops.sumOf { if (it is WriteOp.Put) (it.key.size + it.value.size).toLong() else it.key.size.toLong() }
        engine.writeBatch(ops)
    }

    override fun scan(from: ByteArray, to: ByteArray?): KvIterator {
        scans++
        val inner = engine.scan(from, to)
        return object : KvIterator {
            override fun hasNext() = inner.hasNext()

            override fun next(): KvEntry = inner.next().also { scanned++ }

            override fun close() = inner.close()
        }
    }

    override fun flush() = engine.flush()

    override fun stats(): EngineStats = engine.stats()

    override fun close() = engine.close()
}
