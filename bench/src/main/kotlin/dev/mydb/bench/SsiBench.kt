package dev.mydb.bench

import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.storage.SyncPolicy
import dev.mydb.txn.Isolation
import dev.mydb.txn.SerializationFailureException
import dev.mydb.txn.Txn
import dev.mydb.txn.TxnStore
import dev.mydb.txn.WriteConflictException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * SSI의 성능 비용 (docs/detailed/m7-transactions.md §11). 실행: ./gradlew :bench:run --args=ssi
 * 모두 M2 LSM(MANUAL) 위. SNAPSHOT과 SERIALIZABLE을 같은 조건으로 비교한다.
 */
fun runSsiBench(only: String? = null) {
    println("# SSI 성능 비용: SNAPSHOT vs SERIALIZABLE (M2 LSM, MANUAL)\n")
    if (only == null || only == "1") fixedOverhead()
    if (only == null || only == "2") retainedRecords()
    if (only == null || only == "3") contention()
    if (only == null || only == "4") falsePositives()
}

private val ISOLATIONS = listOf(Isolation.SNAPSHOT, Isolation.SERIALIZABLE)

private fun k(n: Int) = "k%06d".format(n).toByteArray()

private val VAL = ByteArray(100) { 'v'.code.toByte() }

private fun <T> withStore(isolation: Isolation, preload: Int, block: (TxnStore) -> T): T = withTempDir { dir ->
    LsmEngine.open(LsmOptions(dir, syncPolicy = SyncPolicy.MANUAL)).use { engine ->
        val store = TxnStore(engine, isolation)
        (0 until preload).chunked(1_000).forEach { chunk -> store.run { t -> chunk.forEach { t.put(k(it), VAL) } } }
        block(store)
    }
}

/** 1. 경합 없는 단일 스레드: 읽기 집합 기록과 커밋 검사의 고정 비용. */
private fun fixedOverhead() {
    println("## 1. 경합 없을 때의 고정 비용 (단일 스레드, 키 1만 개 적재, 트랜잭션 2만 개)\n")
    val shapes = listOf<Pair<String, (Txn, Random) -> Unit>>(
        "읽기 전용 (get 10)" to { t, r -> repeat(10) { t.get(k(r.nextInt(10_000))) } },
        "읽기 + 쓰기 (get 10, put 1)" to { t, r -> repeat(10) { t.get(k(r.nextInt(10_000))) }; t.put(k(r.nextInt(10_000)), VAL) },
        "쓰기 전용 (put 10)" to { t, r -> repeat(10) { t.put(k(r.nextInt(10_000)), VAL) } },
        "범위 + 쓰기 (scan 100, put 1)" to { t, r -> val s = r.nextInt(9_900); t.scan(k(s), k(s + 100)); t.put(k(r.nextInt(10_000)), VAL) },
    )
    println("JIT 예열 순서 효과를 없애려고 3회 반복하며 매 회 두 격리의 실행 순서를 번갈아 바꾸고, 회차별 값과 중앙값을 적는다.\n")
    println("| 트랜잭션 모양 | SNAPSHOT txn/s (회차) | SERIALIZABLE txn/s (회차) | 중앙값 SSI / SI |")
    println("|---|---|---|---:|")
    for ((name, body) in shapes) {
        val runs = mutableMapOf(Isolation.SNAPSHOT to ArrayList<Double>(), Isolation.SERIALIZABLE to ArrayList())
        repeat(3) { round ->
            val order = if (round % 2 == 0) ISOLATIONS else ISOLATIONS.reversed()
            for (isolation in order) {
                runs.getValue(isolation) += withStore(isolation, 10_000) { store ->
                    val random = Random(round)
                    repeat(5_000) { store.run { t -> body(t, random) } } // 예열
                    20_000 / (measureNanoTime { repeat(20_000) { store.run { t -> body(t, random) } } } / 1e9)
                }
            }
        }
        val si = runs.getValue(Isolation.SNAPSHOT)
        val ssi = runs.getValue(Isolation.SERIALIZABLE)
        println("| $name | ${si.joinToString(" / ") { fmt(it.toLong()) }} | ${ssi.joinToString(" / ") { fmt(it.toLong()) }} | ${"%.2f".format(ssi.sorted()[1] / si.sorted()[1])} |")
    }
    println()
}

/**
 * 2. 오래 열린 SERIALIZABLE 트랜잭션이 있으면 그동안 커밋된 기록을 버릴 수 없다 (§11.2 커밋 기록).
 * 커밋 검사는 보관 중인 기록 수에 비례하므로, 쓰기 트랜잭션의 커밋이 점점 느려지는지 본다.
 */
private fun retainedRecords() {
    println("## 2. 오래 열린 트랜잭션과 커밋 기록 누적 (쓰기 트랜잭션 4,000개, 각각 get 1 + put 1, 서로 겹치지 않는 키)\n")
    println("오래 열린 트랜잭션은 키 100개를 읽은 채 측정 내내 열려 있다 (읽은 키는 쓰기 트랜잭션과 겹치지 않아 중단은 없다).\n")
    println("| 격리 | 오래 열린 트랜잭션 | 앞 1,000건 커밋 평균 | 뒤 1,000건 커밋 평균 | 측정 끝 보관 기록 |")
    println("|---|---:|---:|---:|---:|")
    for (isolation in ISOLATIONS) {
        for (idle in listOf(0, 1, 10)) {
            withStore(isolation, 20_000) { store ->
                val readers = List(idle) { i -> store.begin().also { t -> repeat(100) { t.get(k(10_000 + i * 100 + it)) } } }
                val latencies = LongArray(4_000)
                repeat(4_000) { i ->
                    latencies[i] = measureNanoTime { store.run { t -> t.get(k(i)); t.put(k(i), VAL) } }
                }
                val retained = store.retainedSerializableRecords()
                readers.forEach { it.close() }
                val first = latencies.take(1_000).average() / 1e3
                val last = latencies.takeLast(1_000).average() / 1e3
                println("| $isolation | $idle | ${"%.1f".format(first)} µs | ${"%.1f".format(last)} µs | ${fmt(retained)} |")
            }
        }
    }
    println()
}

private class ContentionResult(val committedPerSec: Double, val attemptsPerCommit: Double, val writeConflicts: Long, val serializationFailures: Long)

/** 스레드 8개, 각자 [perThread]번 커밋할 때까지 재시도한다. 실패를 종류별로 센다. */
private fun runContended(store: TxnStore, perThread: Int, body: (Txn, Random) -> Unit): ContentionResult {
    val attempts = AtomicLong()
    val conflicts = AtomicLong()
    val serial = AtomicLong()
    val pool = Executors.newFixedThreadPool(8)
    val nanos = measureNanoTime {
        repeat(8) { worker ->
            pool.submit {
                val random = Random(worker)
                repeat(perThread) {
                    while (true) {
                        attempts.incrementAndGet()
                        val t = store.begin()
                        try {
                            body(t, random)
                            t.commit()
                            break
                        } catch (e: SerializationFailureException) {
                            serial.incrementAndGet()
                        } catch (e: WriteConflictException) {
                            conflicts.incrementAndGet()
                        } finally {
                            t.close()
                        }
                    }
                }
            }
        }
        pool.shutdown()
        check(pool.awaitTermination(10, TimeUnit.MINUTES))
    }
    val commits = 8.0 * perThread
    return ContentionResult(commits / (nanos / 1e9), attempts.get() / commits, conflicts.get(), serial.get())
}

private fun num(t: Txn, key: String) = String(t.get(key.toByteArray())!!).toInt()

private fun setNum(t: Txn, key: String, v: Int) = t.put(key.toByteArray(), "$v".toByteArray())

/** 3회 반복하며 격리 실행 순서를 번갈아 바꾼다 (JIT 예열 순서 효과 제거). 각 격리의 회차별 결과를 돌려준다. */
private fun <T> alternating(rounds: Int = 3, run: (Isolation) -> T): Map<Isolation, List<T>> {
    val results = mutableMapOf<Isolation, MutableList<T>>(Isolation.SNAPSHOT to ArrayList(), Isolation.SERIALIZABLE to ArrayList())
    repeat(rounds) { round -> (if (round % 2 == 0) ISOLATIONS else ISOLATIONS.reversed()).forEach { results.getValue(it) += run(it) } }
    return results
}

private fun median(values: List<Double>) = values.sorted()[values.size / 2]

private fun medianLong(values: List<Long>) = values.sorted()[values.size / 2]

/** 3. 경합: 이체(같은 키 경쟁 → 두 격리 모두 쓰기 충돌)와 write skew가 생기기 쉬운 제약 워크로드. 3회 중앙값. */
private fun contention() {
    println("## 3. 경합 (스레드 8, 스레드당 커밋 300건, 3회 반복·순서 교대, 중앙값)\n")
    println("| 워크로드 | 격리 | 커밋/s (회차) | 커밋당 시도 | 쓰기 충돌 | 직렬화 실패 | 제약 (3회) |")
    println("|---|---|---|---:|---:|---:|---|")
    val transfer = alternating { isolation ->
        withStore(isolation, 0) { store ->
            store.run { t -> repeat(20) { setNum(t, "acct/$it", 1_000) } }
            val r = runContended(store, 300) { t, random ->
                val from = random.nextInt(20)
                val to = (from + 1 + random.nextInt(19)) % 20
                val amount = random.nextInt(1, 50)
                val balance = num(t, "acct/$from")
                if (balance >= amount) {
                    setNum(t, "acct/$from", balance - amount)
                    setNum(t, "acct/$to", num(t, "acct/$to") + amount)
                }
            }
            r to (store.begin().use { t -> (0 until 20).sumOf { num(t, "acct/$it") } } == 20_000)
        }
    }
    for ((isolation, runs) in transfer) {
        val rs = runs.map { it.first }
        println(
            "| 이체 (계좌 20개) | $isolation | ${rs.joinToString(" / ") { fmt(it.committedPerSec.toLong()) }} | ${"%.2f".format(median(rs.map { it.attemptsPerCommit }))} | " +
                "${fmt(medianLong(rs.map { it.writeConflicts }))} | ${fmt(medianLong(rs.map { it.serializationFailures }))} | 합계 ${if (runs.all { it.second }) "보존" else "깨짐"} |",
        )
    }
    val pairs = alternating { isolation ->
        withStore(isolation, 0) { store ->
            // 쌍 10개, 각 쌍 (x, y)에 제약 x + y ≥ 0. x나 y 중 하나에서 출금 — 서로 다른 키를 써서 SI에서는 write skew가 난다
            store.run { t -> repeat(10) { setNum(t, "pair/$it/x", 50); setNum(t, "pair/$it/y", 50) } }
            val r = runContended(store, 300) { t, random ->
                val p = random.nextInt(10)
                val side = if (random.nextBoolean()) "x" else "y"
                val amount = random.nextInt(1, 20)
                if (num(t, "pair/$p/x") + num(t, "pair/$p/y") >= amount) setNum(t, "pair/$p/$side", num(t, "pair/$p/$side") - amount)
            }
            r to store.begin().use { t -> (0 until 10).count { num(t, "pair/$it/x") + num(t, "pair/$it/y") < 0 } }
        }
    }
    for ((isolation, runs) in pairs) {
        val rs = runs.map { it.first }
        val violations = runs.map { it.second }
        println(
            "| 쌍 제약 출금 (쌍 10개) | $isolation | ${rs.joinToString(" / ") { fmt(it.committedPerSec.toLong()) }} | ${"%.2f".format(median(rs.map { it.attemptsPerCommit }))} | " +
                "${fmt(medianLong(rs.map { it.writeConflicts }))} | ${fmt(medianLong(rs.map { it.serializationFailures }))} | " +
                "${if (violations.all { it == 0 }) "유지" else "**위반 쌍 ${violations.joinToString(" / ")}개**"} |",
        )
    }
    println()
}

/**
 * 4. 거짓 양성: 트랜잭션 i는 키 c_i를 읽고 c_{i+1}에 쓴다. rw 간선은 항상 인덱스가 큰 쪽 → 작은 쪽,
 * wr 간선은 작은 쪽 → 큰 쪽인데 둘을 섞은 순환은 커밋 순서 모순이 생겨 만들 수 없다 (§ 측정 문서).
 * 따라서 이 워크로드의 모든 직렬화 실패는 **불필요한 중단**이다. 같은 키 쓰기 충돌은 두 격리 모두에서 정상이다.
 */
private fun falsePositives() {
    println("## 4. 거짓 양성 — 순환이 생길 수 없는 사슬 워크로드 (스레드 8, 스레드당 커밋 300건, 3회 반복·순서 교대, 중앙값)\n")
    println("| 사슬 길이 | 격리 | 커밋/s (회차) | 커밋당 시도 | 쓰기 충돌 (정상) | 직렬화 실패 (전부 거짓 양성) |")
    println("|---:|---|---|---:|---:|---:|")
    for (chain in listOf(8, 64)) {
        val results = alternating { isolation ->
            withStore(isolation, 0) { store ->
                store.run { t -> repeat(chain + 1) { setNum(t, "c/%03d".format(it), 0) } }
                runContended(store, 300) { t, random ->
                    val i = random.nextInt(chain)
                    val v = num(t, "c/%03d".format(i))
                    setNum(t, "c/%03d".format(i + 1), v + 1)
                }
            }
        }
        for ((isolation, rs) in results) {
            println(
                "| $chain | $isolation | ${rs.joinToString(" / ") { fmt(it.committedPerSec.toLong()) }} | ${"%.2f".format(median(rs.map { it.attemptsPerCommit }))} | " +
                    "${fmt(medianLong(rs.map { it.writeConflicts }))} | ${fmt(medianLong(rs.map { it.serializationFailures }))} |",
            )
        }
    }
    println()
}
