package dev.mydb.bench

import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * M4 비교 실험 (docs/detailed/m4-engine-comparison.md). 실행: ./gradlew :bench:run --args=m4
 * 기본 비교 표(--args=compare)가 답하지 못하는 네 가지: 무작위 순서 적재, 공간 증폭 시계열, 그룹 커밋, 읽기 증폭.
 */
fun runEngineExperiments(only: String? = null) {
    println("# M4 저장 엔진 비교 실험\n")
    if (only == null || only == "e1") randomOrderLoad()
    if (only == null || only == "e2") spaceOverTime()
    if (only == null || only == "e3") groupCommit()
    if (only == null || only == "e4") readAmplification()
}

private class Kind(val name: String, val open: (Path, SyncPolicy) -> KvEngine)

private val KINDS = listOf(
    Kind("M1 Log") { dir, policy -> LogEngine.open(LogEngineOptions(dir, 64L * 1024 * 1024, policy)) },
    Kind("M2 LSM") { dir, policy -> LsmEngine.open(LsmOptions(dir, syncPolicy = policy)) },
    Kind("M3 B+tree") { dir, policy -> BTreeEngine.open(BTreeOptions(dir, syncPolicy = policy)) },
)

private val VALUE = ByteArray(100) { 'v'.code.toByte() }

private fun randomKeys(count: Int, seed: Int) = (0 until count).shuffled(Random(seed))

private fun randomOrderLoad() {
    println("## E1. 무작위 순서 적재 (키 100만, 값 100 B)\n")
    println("| 엔진 | 적재 ops/s | 쓰기 증폭 | 디스크 사용량 | 구조 | 존재 키 get | 없는 키 get | 없는 키 get당 읽기 |")
    println("|---|---:|---:|---:|---|---:|---:|---:|")
    val variants = KINDS.map { it.name to { dir: Path -> it.open(dir, SyncPolicy.MANUAL) } } +
        ("M2 LSM (bloom 없음)" to { dir: Path -> LsmEngine.open(LsmOptions(dir, bloomBitsPerKey = 0, syncPolicy = SyncPolicy.MANUAL)) })
    val order = randomKeys(1_000_000, 42)
    for ((name, open) in variants) {
        withTempDir { dir ->
            open(dir).use { engine ->
                val nanos = measureNanoTime { order.forEach { engine.put(key(it), VALUE) } }
                val stats = engine.stats()
                val structure = when (engine) {
                    is LsmEngine -> "테이블 ${engine.tableCount()}개"
                    is BTreeEngine -> engine.verify().let { "높이 ${it.height}, 리프 채움 ${"%.2f".format(it.averageLeafFill)}" }
                    else -> "-"
                }
                val existing = randomKeys(1_000_000, 7).take(100_000)
                val getNanos = measureNanoTime { existing.forEach { engine.get(key(it)) } }
                val missing = List(100_000) { "key-%010d-x".format(it * 10).toByteArray() }
                val readBefore = engine.stats().diskBytesRead
                val missNanos = measureNanoTime { missing.forEach { engine.get(it) } }
                val missRead = (engine.stats().diskBytesRead - readBefore) / missing.size
                println(
                    "| $name | ${fmt((1_000_000 / (nanos / 1e9)).toLong())} | ${"%.2f".format(stats.writeAmplification)} | ${mib(stats.diskBytesUsed)} | " +
                        "$structure | ${"%.2f".format(getNanos / 1e3 / 100_000)} µs | ${"%.2f".format(missNanos / 1e3 / 100_000)} µs | $missRead B |",
                )
            }
        }
    }
    println()
}

private fun spaceOverTime() {
    println("## E2. 공간 증폭 시계열 (키 1만 개 × 20회 덮어쓰기, 1만 회마다 기록)\n")
    val live = 10_000L * (14 + 100)
    println("살아 있는 데이터 ≈ ${mib(live)}. 표의 값은 공간 증폭 = 디스크 사용량 ÷ 살아 있는 데이터.\n")
    // Log는 세그먼트 1 MiB로 연다. 64 MiB면 23 MiB 전체가 활성 세그먼트에 있어 merge할 대상이 없다 (첫 측정의 실수)
    val smallSegmentLog = Kind("M1 Log") { dir, policy -> LogEngine.open(LogEngineOptions(dir, 1L * 1024 * 1024, policy)) }
    val variants = listOf(
        Triple("M1 Log (세그먼트 1 MiB)", smallSegmentLog, false),
        Triple("M1 Log (세그먼트 1 MiB, 5만 회마다 merge)", smallSegmentLog, true),
        Triple("M2 LSM", KINDS[1], false),
        Triple("M3 B+tree", KINDS[2], false),
    )
    val header = (1..20).joinToString(" | ") { "${it}만" }
    println("| 엔진 | $header |")
    println("|---|" + "---:|".repeat(20))
    for ((name, kind, merge) in variants) {
        withTempDir { dir ->
            kind.open(dir, SyncPolicy.MANUAL).use { engine ->
                val samples = ArrayList<Double>()
                repeat(200_000) { i ->
                    engine.put(key(i % 10_000), VALUE)
                    if (merge && (i + 1) % 50_000 == 0) (engine as LogEngine).merge()
                    if ((i + 1) % 10_000 == 0) samples += engine.stats().diskBytesUsed.toDouble() / live
                }
                println("| $name | ${samples.joinToString(" | ") { "%.1f".format(it) }} |")
            }
        }
    }
    println()
}

private fun groupCommit() {
    println("## E3. 그룹 커밋 (EVERY_WRITE, 총 5,000건)\n")
    println("| 엔진 | 배치 1 | 배치 10 | 배치 100 |")
    println("|---|---:|---:|---:|")
    for (kind in KINDS) {
        val results = listOf(1, 10, 100).map { batch ->
            withTempDir { dir ->
                kind.open(dir, SyncPolicy.EVERY_WRITE).use { engine ->
                    val nanos = measureNanoTime {
                        (0 until 5_000).chunked(batch).forEach { chunk ->
                            if (batch == 1) engine.put(key(chunk[0]), VALUE) else engine.writeBatch(chunk.map { WriteOp.Put(key(it), VALUE) })
                        }
                    }
                    5_000 / (nanos / 1e9)
                }
            }
        }
        println("| ${kind.name} | ${results.joinToString(" | ") { fmt(it.toLong()) + " ops/s" }} |")
    }
    println()
}

private fun readAmplification() {
    println("## E4. 읽기 증폭 (키 10만 적재 → 재시작 → 무작위 존재 키 get 1만 회)\n")
    println("| 엔진 | get당 엔진이 읽은 바이트 | get 1회 |")
    println("|---|---:|---:|")
    for (kind in KINDS) {
        withTempDir { dir ->
            kind.open(dir, SyncPolicy.MANUAL).use { engine -> repeat(100_000) { engine.put(key(it), VALUE) } }
            kind.open(dir, SyncPolicy.MANUAL).use { engine ->
                val targets = randomKeys(100_000, 3).take(10_000)
                val before = engine.stats().diskBytesRead
                val nanos = measureNanoTime { targets.forEach { engine.get(key(it)) } }
                val perGet = (engine.stats().diskBytesRead - before) / targets.size
                println("| ${kind.name} | $perGet B | ${"%.2f".format(nanos / 1e3 / targets.size)} µs |")
            }
        }
    }
    println("\n운영체제 페이지 캐시는 비우지 않았다. 바이트는 엔진이 요청한 양이고 실제 장치 I/O가 아니다.\n")
}
