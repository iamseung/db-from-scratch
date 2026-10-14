package dev.mydb.engine.log

import dev.mydb.storage.SyncPolicy
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * 로드맵 M1 완료 조건: 프로세스를 kill -9 후 재시작해도 확정된 쓰기가 모두 남아 있다.
 *
 * 자식 JVM이 쓰기를 반복하며 ACK를 출력하고, 부모는 무작위 시점에 SIGKILL을 보낸 뒤 엔진을 다시 열어 검증한다.
 * - 마지막으로 받은 ACK까지의 쓰기는 반드시 남아 있다.
 * - ACK를 받기 전에 죽었어도 디스크에 닿은 쓰기는 남을 수 있다. 그래서 남은 키는 0..m (m ≥ ACK)의 빈틈없는 앞부분이어야 한다.
 *
 * 관찰 (macOS, APFS): 이 테스트로는 잘린 프레임이 생기지 않는다. 커널은 진행 중인 write() 시스템 콜을 끝낸 뒤
 * SIGKILL을 처리하므로 프레임은 통째로 쓰이거나 아예 안 쓰인다. 4 MiB 값으로 30회 실험해도 같았다.
 * 잘린 꼬리 복구 경로는 LogEngineTest가 바이트를 직접 잘라 검증한다.
 */
class CrashTest {
    @TempDir
    lateinit var root: Path

    private val seed = System.nanoTime()
    private val random = Random(seed)

    @Test
    fun `EVERY_WRITE 단일 put은 kill -9 후에도 ACK한 쓰기가 모두 남는다`() =
        repeatRounds(rounds = 50, policy = SyncPolicy.EVERY_WRITE, mode = "single")

    @Test
    fun `배치는 kill -9 후에 통째로 남거나 통째로 사라진다`() =
        repeatRounds(rounds = 20, policy = SyncPolicy.EVERY_WRITE, mode = "batch")

    /**
     * MANUAL은 fsync를 하지 않지만 write()로 OS 페이지 캐시에 넘어간 데이터는 프로세스가 죽어도 OS가 디스크에 쓴다.
     * 그래서 프로세스 크래시는 견디고, 전원 차단에서만 차이가 난다 (m0 설계 §6).
     */
    @Test
    fun `MANUAL도 프로세스 크래시는 견딘다`() =
        repeatRounds(rounds = 20, policy = SyncPolicy.MANUAL, mode = "single")

    private fun repeatRounds(rounds: Int, policy: SyncPolicy, mode: String) {
        repeat(rounds) { round ->
            val dataDir = root.resolve("$mode-$policy-$round")
            val context = "seed=$seed round=$round policy=$policy mode=$mode"
            // 세그먼트를 작게 잡아 크래시가 rollover 도중에도 걸리게 한다
            val lastAck = runAndKill(dataDir, policy, mode, segmentSize = 4096, killAfterAcks = random.nextInt(1, 400), context)
            verify(dataDir, mode, lastAck, context)
        }
    }

    /** 자식을 띄워 ACK를 [killAfterAcks]개 받은 뒤 SIGKILL을 보낸다. 마지막으로 받은 ACK 번호를 돌려준다. */
    private fun runAndKill(dataDir: Path, policy: SyncPolicy, mode: String, segmentSize: Int, killAfterAcks: Int, context: String): Long {
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", childClasspath(),
            "dev.mydb.engine.log.CrashWriterMainKt",
            dataDir.toString(), policy.name, segmentSize.toString(), mode,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        var lastAck = -1L
        try {
            val reader = process.inputStream.bufferedReader()
            while (lastAck + 1 < killAfterAcks) {
                val line = reader.readLine()
                    ?: fail("자식 프로세스가 ACK ${lastAck + 1} 전에 종료됐습니다 (exit=${process.waitFor()}) $context")
                lastAck = line.removePrefix("ACK ").toLong()
            }
            // ACK 직후뿐 아니라 쓰기 한가운데서도 죽도록 약간 기다린다
            Thread.sleep(random.nextLong(0, 3))
        } finally {
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "자식 프로세스가 종료되지 않았습니다 $context" }
        }
        return lastAck
    }

    private fun verify(dataDir: Path, mode: String, lastAck: Long, context: String) {
        LogEngine.open(LogEngineOptions(dataDir)).use { engine ->
            val entries = engine.scan(ByteArray(0), null).use { it.asSequence().toList() }
            val indexes = entries.map { CrashWorkload.indexOf(it.key) }

            val ackedKeyCount = when (mode) {
                "batch" -> (lastAck + 1) * CrashWorkload.BATCH_SIZE
                else -> lastAck + 1
            }
            assertTrue(indexes.size >= ackedKeyCount, "ACK한 키 $ackedKeyCount 개 중 ${indexes.size} 개만 남았습니다 $context")
            assertEquals((0L until indexes.size).toList(), indexes, "남은 키에 빈틈이 있습니다 $context")
            if (mode == "batch") {
                assertEquals(0, indexes.size % CrashWorkload.BATCH_SIZE, "배치 일부만 남았습니다: ${indexes.size} 개 $context")
            }
            entries.forEach { assertContentEquals(CrashWorkload.value(CrashWorkload.indexOf(it.key)), it.value, context) }
        }
    }

    private fun childClasspath(): String =
        System.getProperty("crash.child.classpath") ?: error("crash.child.classpath가 없습니다. Gradle test 태스크로 실행하세요")
}
