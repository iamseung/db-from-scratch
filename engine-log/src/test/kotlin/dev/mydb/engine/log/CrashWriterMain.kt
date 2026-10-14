package dev.mydb.engine.log

import dev.mydb.kv.WriteOp
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

/**
 * 크래시 테스트의 자식 JVM 진입점.
 * 쓰기를 끝없이 반복하고, 쓰기가 반환될 때마다 "ACK i"를 출력한다. 부모가 SIGKILL로 죽일 때까지 멈추지 않는다.
 *
 * args: dataDir, syncPolicy, segmentSizeBytes, mode(single|batch|merge)
 * merge 모드는 단일 put을 하면서 주기적으로 merge를 돌리고, 직전에 "MERGING"을 출력해 부모가 merge 구간을 노릴 수 있게 한다.
 */
fun main(args: Array<String>) {
    val (dataDir, policy, segmentSize, mode) = args
    val options = LogEngineOptions(Path.of(dataDir), segmentSizeBytes = segmentSize.toLong(), syncPolicy = SyncPolicy.valueOf(policy))
    val engine = LogEngine.open(options)
    var i = 0L
    while (true) {
        when (mode) {
            "single" -> engine.put(CrashWorkload.key(i), CrashWorkload.value(i))
            "merge" -> {
                engine.put(CrashWorkload.key(i), CrashWorkload.value(i))
                if (i % CrashWorkload.MERGE_EVERY == CrashWorkload.MERGE_EVERY - 1) {
                    println("ACK $i")
                    println("MERGING")
                    System.out.flush()
                    engine.merge()
                    i++
                    continue
                }
            }
            "batch" -> engine.writeBatch(
                CrashWorkload.batchKeys(i).map { k -> WriteOp.Put(CrashWorkload.key(k), CrashWorkload.value(k)) },
            )
            else -> error("알 수 없는 mode: $mode")
        }
        println("ACK $i")
        System.out.flush()
        i++
    }
}

/** 부모와 자식이 같은 키·값 규칙을 공유한다. 키는 0 채움이라 바이트 순서 = 번호 순서다. */
object CrashWorkload {
    const val BATCH_SIZE = 3
    const val MERGE_EVERY = 20L

    fun key(n: Long): ByteArray = "key-%010d".format(n).toByteArray()

    fun value(n: Long): ByteArray = "value-$n-".padEnd(100, '#').toByteArray()

    fun batchKeys(batch: Long): List<Long> = (0 until BATCH_SIZE).map { batch * BATCH_SIZE + it }

    fun indexOf(key: ByteArray): Long = String(key).removePrefix("key-").toLong()
}
