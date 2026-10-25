package dev.mydb.bench

import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.system.measureNanoTime

/**
 * M1 LogEngine과 M2 LsmEngine을 같은 시나리오로 잰다 (docs/detailed/m2-engine-lsm.md §15).
 * 두 엔진 모두 기본 설정: Log 세그먼트 64 MiB / LSM memtable 1 MiB, 블록 4 KiB, bloom 10 bits/key, size-tiered 4개.
 */
fun runCompareBench() {
    println("# M1 LogEngine vs M2 LsmEngine\n")
    putThroughput()
    writeAmplification()
    restartTime()
    scanCost()
    missingKeyGet()
    heapPerKey()
}

private class Bench(val name: String, val open: (Path, SyncPolicy) -> KvEngine)

private val ENGINES = listOf(
    Bench("M1 Log") { dir, policy -> LogEngine.open(LogEngineOptions(dir, 64L * 1024 * 1024, policy)) },
    Bench("M2 LSM") { dir, policy -> LsmEngine.open(LsmOptions(dir, syncPolicy = policy)) },
)

private val VALUE_100 = ByteArray(100) { 'v'.code.toByte() }
private val VALUE_1K = ByteArray(1024) { 'v'.code.toByte() }

private fun KvEngine.load(count: Int, value: ByteArray = VALUE_100) = repeat(count) { put(key(it), value) }

private fun KvEngine.tables(): String = (this as? LsmEngine)?.tableCount()?.toString() ?: "-"

private fun putThroughput() {
    println("## 1. put 처리량 (값 100 B)\n")
    println("| 엔진 | 정책 | 건수 | 회차별 ops/s | 대표 ops/s |")
    println("|---|---|---:|---|---:|")
    for (bench in ENGINES) {
        for ((policy, count, runs) in listOf(Triple(SyncPolicy.MANUAL, 1_000_000, 3), Triple(SyncPolicy.EVERY_WRITE, 5_000, 1))) {
            val results = List(runs) {
                withTempDir { dir ->
                    bench.open(dir, policy).use { engine -> count / (measureNanoTime { engine.load(count) } / 1e9) }
                }
            }
            val median = results.sorted()[results.size / 2]
            println("| ${bench.name} | $policy | ${fmt(count)} | ${results.joinToString(" / ") { fmt(it.toLong()) }} | ${fmt(median.toLong())} |")
        }
    }
    println()
}

private fun writeAmplification() {
    println("## 2. 쓰기 증폭과 디스크 사용량\n")
    println("| 엔진 | 워크로드 | 사용자 바이트 | 디스크에 쓴 바이트 | 쓰기 증폭 | 디스크 사용량 | 테이블 수 |")
    println("|---|---|---:|---:|---:|---:|---:|")
    for (bench in ENGINES) {
        withTempDir { dir ->
            bench.open(dir, SyncPolicy.MANUAL).use { engine ->
                engine.load(1_000_000)
                row(bench, "서로 다른 키 100만", engine)
            }
        }
        withTempDir { dir ->
            bench.open(dir, SyncPolicy.MANUAL).use { engine ->
                repeat(10) { round -> repeat(10_000) { engine.put(key(it), ByteArray(100) { ('a' + round).code.toByte() }) } }
                row(bench, "키 1만 × 10회 덮어쓰기", engine)
            }
        }
    }
    println("\nM1은 merge를 부르지 않은 상태(자동 merge 없음), M2는 쓰기 중 자동 flush·compaction이 일어난 상태다.\n")
}

private fun row(bench: Bench, workload: String, engine: KvEngine) {
    val s = engine.stats()
    println("| ${bench.name} | $workload | ${mib(s.userBytesWritten)} | ${mib(s.diskBytesWritten)} | ${"%.2f".format(s.writeAmplification)} | ${mib(s.diskBytesUsed)} | ${engine.tables()} |")
}

private fun restartTime() {
    println("## 3. 재시작 시간 (값 1 KiB, warm cache)\n")
    println("| 엔진 | 데이터 | open 시간 |")
    println("|---|---:|---:|")
    for (bench in ENGINES) {
        for (count in listOf(100_000, 1_000_000)) {
            withTempDir { dir ->
                var used = 0L
                bench.open(dir, SyncPolicy.MANUAL).use { engine ->
                    engine.load(count, VALUE_1K)
                    engine.flush()
                    used = engine.stats().diskBytesUsed
                }
                val nanos = measureNanoTime { bench.open(dir, SyncPolicy.MANUAL).close() }
                println("| ${bench.name} | ${mib(used)} | ${"%.3f".format(nanos / 1e9)} s |")
            }
        }
    }
    println()
}

private fun scanCost() {
    println("## 4. scan 100건 vs 전체 키 수 (200회 평균)\n")
    println("| 엔진 | 전체 키 | scan 1회 | get 100회 (같은 키) | 테이블 수 |")
    println("|---|---:|---:|---:|---:|")
    for (bench in ENGINES) {
        for (count in listOf(10_000, 100_000, 1_000_000)) {
            withTempDir { dir ->
                bench.open(dir, SyncPolicy.MANUAL).use { engine ->
                    engine.load(count)
                    val starts = List(200) { (it * 7919L % (count - 100)).toInt() }
                    starts.take(20).forEach { start -> engine.scan(key(start), key(start + 100)).use { it.forEach { } } }
                    val scanNanos = measureNanoTime { starts.forEach { start -> engine.scan(key(start), key(start + 100)).use { it.forEach { } } } }
                    val getNanos = measureNanoTime { starts.forEach { start -> repeat(100) { engine.get(key(start + it)) } } }
                    println("| ${bench.name} | ${fmt(count)} | ${ms(scanNanos / 200)} | ${ms(getNanos / 200)} | ${engine.tables()} |")
                }
            }
        }
    }
    println()
}

private fun missingKeyGet() {
    println("## 5. 없는 키 get (키 100만 개 적재 후 10만 회)\n")
    println("| 엔진 | bloom | 테이블 수 | get 1회 | get당 디스크 읽기 |")
    println("|---|---|---:|---:|---:|")
    val variants = listOf(
        Triple("M1 Log", "-") { dir: Path -> LogEngine.open(LogEngineOptions(dir, 64L * 1024 * 1024, SyncPolicy.MANUAL)) as KvEngine },
        Triple("M2 LSM", "10 bits/key") { dir: Path -> LsmEngine.open(LsmOptions(dir, syncPolicy = SyncPolicy.MANUAL)) },
        Triple("M2 LSM", "없음") { dir: Path -> LsmEngine.open(LsmOptions(dir, bloomBitsPerKey = 0, syncPolicy = SyncPolicy.MANUAL)) },
    )
    for ((name, bloom, open) in variants) {
        withTempDir { dir ->
            open(dir).use { engine ->
                engine.load(1_000_000)
                // 적재 키 범위 안쪽이지만 없는 키: 범위 검사로는 걸러지지 않고 bloom이나 블록 읽기로만 판정된다
                val missing = List(100_000) { "key-%010d-x".format(it * 10).toByteArray() }
                val readBefore = engine.stats().diskBytesRead
                val nanos = measureNanoTime { missing.forEach { engine.get(it) } }
                val bytesPerGet = (engine.stats().diskBytesRead - readBefore) / missing.size
                println("| $name | $bloom | ${engine.tables()} | ${"%.2f".format(nanos / 1e3 / missing.size)} µs | $bytesPerGet B |")
            }
        }
    }
    println()
}

private fun heapPerKey() {
    println("## 6. 키 100만 개일 때 힙 사용량 (키 14 B, 값 100 B)\n")
    println("| 엔진 | 힙 증가 | 키당 | 테이블 수 |")
    println("|---|---:|---:|---:|")
    for (bench in ENGINES) {
        withTempDir { dir ->
            bench.open(dir, SyncPolicy.MANUAL).use { engine ->
                val before = usedHeapAfterGc()
                engine.load(1_000_000)
                val total = usedHeapAfterGc() - before
                println("| ${bench.name} | ${mib(total)} | ${total / 1_000_000} B | ${engine.tables()} |")
            }
        }
    }
    println("\nM2는 memtable(최대 1 MiB 근사)을 포함한다.\n")
}
