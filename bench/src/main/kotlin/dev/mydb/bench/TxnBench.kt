package dev.mydb.bench

import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import dev.mydb.txn.TxnStore
import java.nio.file.Path
import kotlin.system.measureNanoTime

/** M7 측정 (docs/detailed/m7-transactions.md §10). 실행: ./gradlew :bench:run --args=m7 */
fun runTxnBench() {
    println("# M7 트랜잭션 측정\n")
    commitCost()
    versionPileUp()
}

private val TXN_ENGINES = listOf<Pair<String, (Path) -> KvEngine>>(
    "M1 Log" to { dir -> LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
    "M2 LSM" to { dir -> LsmEngine.open(LsmOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
    "M3 B+tree" to { dir -> BTreeEngine.open(BTreeOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
)

private val V = ByteArray(100) { 'v'.code.toByte() }

private fun commitCost() {
    println("## 1. 트랜잭션 크기별 커밋 비용 (MANUAL, 총 키 쓰기 2만 건, 키 공간 10만)\n")
    println("| 엔진 | 트랜잭션당 키 | 트랜잭션/s | 키 쓰기/s | 커밋당 충돌 검사 scan |")
    println("|---|---:|---:|---:|---:|")
    for ((name, open) in TXN_ENGINES) {
        for (size in listOf(1, 10, 100)) {
            withTempDir { dir ->
                open(dir).use { engine ->
                    val store = TxnStore(engine)
                    val txns = 20_000 / size
                    val nanos = measureNanoTime {
                        repeat(txns) { t -> store.run { txn -> repeat(size) { k -> txn.put(key((t * size + k) * 7919 % 100_000), V) } } }
                    }
                    println("| $name | $size | ${fmt((txns / (nanos / 1e9)).toLong())} | ${fmt((20_000 / (nanos / 1e9)).toLong())} | $size |")
                }
            }
        }
    }
    println()
}

private fun versionPileUp() {
    println("## 2. 버전 누적과 GC (키 1,000개를 각각 N번 갱신)\n")
    println("GC 후 정리: LSM은 compactAll(tombstone과 지운 버전을 물리적으로 제거), B+tree는 리프 평균 채움률(merge가 없어 빈 리프가 남는지).\n")
    println("| 엔진 | 키당 버전 | 저장된 버전 | get 1회 (GC 전) | GC가 지운 버전 | get 1회 (GC 후) | 남은 버전 | GC 후 정리 | get 1회 (정리 후) |")
    println("|---|---:|---:|---:|---:|---:|---:|---|---:|")
    for ((name, open) in TXN_ENGINES) {
        for (versions in listOf(1, 10, 100)) {
            withTempDir { dir ->
                open(dir).use { engine ->
                    val store = TxnStore(engine)
                    repeat(versions) { store.run { txn -> repeat(1_000) { txn.put(key(it), V) } } }
                    val stored = store.versionCount()
                    fun getNanos() = measureNanoTime { store.begin().use { txn -> repeat(10) { repeat(1_000) { txn.get(key(it)) } } } } / 10_000
                    getNanos() // 예열
                    val before = getNanos()
                    val removed = store.gc()
                    val after = getNanos()
                    val cleanup = when (engine) {
                        is LsmEngine -> engine.compactAll().let { "compactAll → 테이블 ${engine.tableCount()}개" }
                        is BTreeEngine -> engine.verify().let { "리프 ${it.leaves}장, 평균 채움 ${"%.2f".format(it.averageLeafFill)}" }
                        else -> "-"
                    }
                    val afterCleanup = getNanos()
                    println(
                        "| $name | $versions | ${fmt(stored)} | ${"%.2f".format(before / 1e3)} µs | ${fmt(removed)} | ${"%.2f".format(after / 1e3)} µs | " +
                            "${fmt(store.versionCount())} | $cleanup | ${"%.2f".format(afterCleanup / 1e3)} µs |",
                    )
                }
            }
        }
    }
    println()
}
