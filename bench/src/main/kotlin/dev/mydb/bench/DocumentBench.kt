package dev.mydb.bench

import dev.mydb.document.DocumentStore
import dev.mydb.document.DocumentStore.Bound
import dev.mydb.document.DocumentStore.Filter
import dev.mydb.document.Json
import dev.mydb.document.JsonValue
import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.system.measureNanoTime

/** M5 측정 (docs/detailed/m5-document.md §8). 실행: ./gradlew :bench:run --args=m5 */
fun runDocumentBench() {
    println("# M5 문서 계층 측정\n")
    insertCost()
    queryPaths()
}

private val STORES = listOf<Pair<String, (Path) -> KvEngine>>(
    "M1 Log" to { dir -> LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
    "M2 LSM" to { dir -> LsmEngine.open(LsmOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
    "M3 B+tree" to { dir -> BTreeEngine.open(BTreeOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
)

private val CITIES = listOf("Seoul", "Busan", "Incheon", "Daegu", "Daejeon", "Gwangju", "Ulsan", "Suwon", "Jeju", "Sejong")

private fun doc(i: Int) = Json.parseObject(
    """{"name":"user-$i","age":${i % 80},"city":"${CITIES[i % 10]}","score":${i * 7 % 1000},"tags":["a","b"],"active":${i % 2 == 0}}""",
)

private fun insertCost() {
    println("## 1. 보조 인덱스 수에 따른 삽입 비용 (문서 2만 개, 약 100 B)\n")
    println("| 엔진 | 인덱스 | 삽입 docs/s | 쓰기 증폭 (문서 JSON 바이트 기준) | 디스크에 쓴 바이트 |")
    println("|---|---|---:|---:|---:|")
    val indexSets = listOf(emptyList(), listOf("city"), listOf("city", "age", "score"))
    for ((name, open) in STORES) {
        for (indexes in indexSets) {
            withTempDir { dir ->
                open(dir).use { engine ->
                    val store = DocumentStore(engine)
                    val users = store.createCollection("users")
                    indexes.forEach { users.createIndex(it) }
                    val docBytes = (0 until 20_000).sumOf { Json.stringify(doc(it)).length.toLong() }
                    val before = engine.stats().diskBytesWritten
                    val nanos = measureNanoTime { repeat(20_000) { users.insert("u%06d".format(it), doc(it)) } }
                    val written = engine.stats().diskBytesWritten - before
                    println("| $name | ${if (indexes.isEmpty()) "없음" else indexes.joinToString(", ")} | ${fmt((20_000 / (nanos / 1e9)).toLong())} | ${"%.2f".format(written.toDouble() / docBytes)} | ${mib(written)} |")
                }
            }
        }
    }
    println()
}

private fun queryPaths() {
    println("## 2. IXSCAN vs COLLSCAN (문서 5만 개, 질의마다 20회 평균)\n")
    println("| 엔진 | 질의 | 결과 수 | COLLSCAN | IXSCAN | 배 |")
    println("|---|---|---:|---:|---:|---:|")
    val queries = listOf(
        "city = Jeju (10%)" to Filter.Eq("city", JsonValue.Str("Jeju")),
        "age 30~31 (2.5%)" to Filter.Range("age", Bound(JsonValue.Num(30.0), true), Bound(JsonValue.Num(31.0), true)),
        "score = 7 (0.1%)" to Filter.Eq("score", JsonValue.Num(7.0)),
    )
    for ((name, open) in STORES) {
        withTempDir { dir ->
            open(dir).use { engine ->
                val store = DocumentStore(engine)
                val plain = store.createCollection("plain")
                val indexed = store.createCollection("indexed")
                repeat(50_000) { plain.insert("u%06d".format(it), doc(it)); indexed.insert("u%06d".format(it), doc(it)) }
                listOf("city", "age", "score").forEach { indexed.createIndex(it) }
                for ((label, filter) in queries) {
                    check(indexed.explain(filter).startsWith("IXSCAN")) { indexed.explain(filter) }
                    val count = plain.find(filter).size
                    check(count == indexed.find(filter).size)
                    val coll = measureNanoTime { repeat(20) { plain.find(filter) } } / 20
                    val ix = measureNanoTime { repeat(20) { indexed.find(filter) } } / 20
                    println("| $name | $label | ${fmt(count)} | ${ms(coll)} | ${ms(ix)} | ${"%.1f".format(coll.toDouble() / ix)} |")
                }
            }
        }
    }
    println()
}
