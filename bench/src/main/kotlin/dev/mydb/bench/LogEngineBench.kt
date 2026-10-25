package dev.mydb.bench

import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.storage.SyncPolicy
import kotlin.system.measureNanoTime

/**
 * M1 측정 (docs/detailed/m1-engine-log.md §12). 결과를 Markdown 표로 출력한다.
 * 실행: ./gradlew :bench:run --args=m1
 *
 * 디스크 I/O가 지배하는 초 단위 작업이라 JMH 대신 직접 시간을 잰다. 처리량은 3회 측정해 중앙값을 쓴다.
 */
fun runM1Bench() {
    println("# M1 LogEngine 측정\n")
    putThroughput()
    writeAmplification()
    restartTime()
    scanCost()
    heapPerKey()
}

private const val SEGMENT_SIZE = 64L * 1024 * 1024

private fun putThroughput() {
    println("## 1. put 처리량 (값 100 B)\n")
    println("| 정책 | 건수 | 회차별 ops/s | 중앙값 ops/s | 건당 µs |")
    println("|---|---:|---|---:|---:|")
    for ((policy, count) in listOf(SyncPolicy.EVERY_WRITE to 10_000, SyncPolicy.MANUAL to 1_000_000)) {
        val runs = List(3) {
            withTempDir { dir ->
                LogEngine.open(LogEngineOptions(dir, SEGMENT_SIZE, policy)).use { engine ->
                    val nanos = measureNanoTime { repeat(count) { engine.put(key(it), VALUE_100) } }
                    count / (nanos / 1e9)
                }
            }
        }
        val median = runs.sorted()[1]
        println("| $policy | ${fmt(count)} | ${runs.joinToString(" / ") { fmt(it.toLong()) }} | ${fmt(median.toLong())} | ${"%.2f".format(1e6 / median)} |")
    }
    println()
}

private fun writeAmplification() {
    println("## 2. 쓰기 증폭과 merge\n")
    println("키 1만 개를 10번씩 덮어써 10만 건 put (값 100 B), 그 뒤 merge. 세그먼트 1 MiB.\n")
    withTempDir { dir ->
        LogEngine.open(LogEngineOptions(dir, 1L shl 20, SyncPolicy.MANUAL)).use { engine ->
            repeat(10) { round -> repeat(10_000) { engine.put(key(it), value(round)) } }
            val before = engine.stats()
            val result = engine.merge()
            val after = engine.stats()
            println("| 시점 | 사용자 바이트 | 디스크에 쓴 바이트 | 쓰기 증폭 | 디스크 사용량 |")
            println("|---|---:|---:|---:|---:|")
            println("| merge 전 | ${mib(before.userBytesWritten)} | ${mib(before.diskBytesWritten)} | ${"%.3f".format(before.writeAmplification)} | ${mib(before.diskBytesUsed)} |")
            println("| merge 후 | ${mib(after.userBytesWritten)} | ${mib(after.diskBytesWritten)} | ${"%.3f".format(after.writeAmplification)} | ${mib(after.diskBytesUsed)} |")
            println()
            println("merge: 세그먼트 ${result.mergedSegmentIds.size}개 → 1개, 살아 있는 엔트리 ${fmt(result.liveEntries)}, ${mib(result.bytesBefore)} → ${mib(result.bytesAfter)}\n")
        }
    }
}

private fun restartTime() {
    println("## 3. 재시작 시간 (keydir 재구성)\n")
    println("값 1 KiB. 쓰고 닫은 직후 다시 열기까지의 시간이라 OS 페이지 캐시가 데이터를 들고 있는 상태(warm)다.\n")
    println("| 데이터 크기 | 키 수 | open 시간 | MiB/s |")
    println("|---:|---:|---:|---:|")
    for (count in listOf(100_000, 1_000_000)) {
        withTempDir { dir ->
            LogEngine.open(LogEngineOptions(dir, SEGMENT_SIZE, SyncPolicy.MANUAL)).use { engine ->
                repeat(count) { engine.put(key(it), VALUE_1K) }
                engine.flush()
            }
            var bytes = 0L
            val nanos = measureNanoTime {
                LogEngine.open(LogEngineOptions(dir, SEGMENT_SIZE, SyncPolicy.MANUAL)).use { bytes = it.stats().diskBytesUsed }
            }
            println("| ${mib(bytes)} | ${fmt(count)} | ${"%.2f".format(nanos / 1e9)} s | ${fmt((bytes / (1 shl 20) / (nanos / 1e9)).toLong())} |")
        }
    }
    println()
}

private fun scanCost() {
    println("## 4. scan 비용 vs 전체 키 수 (범위 100건 고정)\n")
    println("keydir가 정렬되지 않은 HashMap이라 범위가 작아도 전체 키를 훑는다. scan 200회 평균.\n")
    println("| 전체 키 수 | scan 1회 평균 | get 100회 (같은 키들) 평균 |")
    println("|---:|---:|---:|")
    for (count in listOf(10_000, 100_000, 1_000_000)) {
        withTempDir { dir ->
            LogEngine.open(LogEngineOptions(dir, SEGMENT_SIZE, SyncPolicy.MANUAL)).use { engine ->
                repeat(count) { engine.put(key(it), VALUE_100) }
                val starts = List(200) { (it * 7919L % (count - 100)).toInt() }
                // 워밍업
                starts.take(20).forEach { start -> engine.scan(key(start), key(start + 100)).use { it.forEach { } } }
                val scanNanos = measureNanoTime {
                    starts.forEach { start -> engine.scan(key(start), key(start + 100)).use { it.forEach { } } }
                }
                val getNanos = measureNanoTime {
                    starts.forEach { start -> repeat(100) { engine.get(key(start + it)) } }
                }
                println("| ${fmt(count)} | ${ms(scanNanos / 200)} | ${ms(getNanos / 200)} |")
            }
        }
    }
    println()
}

private fun heapPerKey() {
    println("## 5. 키 100만 개일 때 keydir 힙 사용량\n")
    println("키 14 B. 쓰기 전후 GC 후 사용 힙 차이. GC 시점에 따라 오차가 있다.\n")
    withTempDir { dir ->
        LogEngine.open(LogEngineOptions(dir, SEGMENT_SIZE, SyncPolicy.MANUAL)).use { engine ->
            val before = usedHeapAfterGc()
            repeat(1_000_000) { engine.put(key(it), VALUE_100) }
            val after = usedHeapAfterGc()
            val total = after - before
            println("| 키 수 | 힙 증가 | 키당 바이트 |")
            println("|---:|---:|---:|")
            println("| 1,000,000 | ${mib(total)} | ${total / 1_000_000} |")
        }
    }
    println()
}

private val VALUE_100 = ByteArray(100) { 'v'.code.toByte() }
private val VALUE_1K = ByteArray(1024) { 'v'.code.toByte() }

private fun value(round: Int) = ByteArray(100) { ('a' + round).code.toByte() }

