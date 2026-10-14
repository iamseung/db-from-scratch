package dev.mydb.kv.crash

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * 프로세스를 kill -9 후 재시작해도 확정된 쓰기가 모두 남는지 검증한다. 엔진 모듈은 [factory]만 정하면 된다.
 *
 * 자식 JVM이 쓰기를 반복하며 ACK를 출력하고, 부모는 무작위 시점에 SIGKILL을 보낸 뒤 엔진을 다시 열어 검증한다.
 * - 마지막으로 받은 ACK까지의 쓰기는 반드시 남아 있다.
 * - ACK를 받기 전에 죽었어도 디스크에 닿은 쓰기는 남을 수 있다. 그래서 남은 키는 0..m (m ≥ ACK)의 빈틈없는 앞부분이어야 한다.
 *
 * 관찰 (macOS, APFS, M1에서): 이 테스트로는 잘린 프레임이 생기지 않는다. 커널은 진행 중인 write() 시스템 콜을 끝낸 뒤
 * SIGKILL을 처리하므로 프레임은 통째로 쓰이거나 아예 안 쓰인다. 4 MiB 값으로 30회 실험해도 같았다.
 * 잘린 꼬리 복구 경로는 엔진별 테스트가 바이트를 직접 잘라 검증한다.
 */
@Tag("slow")
abstract class KvEngineCrashTest {
    @TempDir
    lateinit var root: Path

    abstract val factory: KClass<out CrashEngineFactory>

    /** 재시작 후 엔진별로 추가 확인할 파일 상태 (예: 남은 임시 파일이 없는지). */
    open fun verifyFiles(dataDir: Path, context: String) = Unit

    private val seed = System.nanoTime()
    private val random = Random(seed)

    @Test
    fun `durable 단일 put은 kill -9 후에도 ACK한 쓰기가 모두 남는다`() =
        repeatRounds(rounds = 50, durable = true, mode = CrashMode.SINGLE)

    @Test
    fun `배치는 kill -9 후에 통째로 남거나 통째로 사라진다`() =
        repeatRounds(rounds = 20, durable = true, mode = CrashMode.BATCH)

    /**
     * 유지 작업은 1ms 남짓이라 무작위 시점의 SIGKILL로는 거의 걸리지 않는다(M1 merge에서 60회 중 0회 관찰).
     * 그래서 자식이 유지 작업 직전에 출력하는 신호를 받는 즉시 죽여 그 구간을 노린다.
     */
    @Test
    fun `유지 작업 도중 kill -9 되어도 확정된 쓰기가 모두 남는다`() =
        repeatRounds(rounds = 30, durable = true, mode = CrashMode.MAINTENANCE)

    /**
     * fsync를 하지 않아도 write()로 OS 페이지 캐시에 넘어간 데이터는 프로세스가 죽어도 OS가 디스크에 쓴다.
     * 그래서 프로세스 크래시는 견디고, 전원 차단에서만 차이가 난다.
     */
    @Test
    fun `durable이 아니어도 프로세스 크래시는 견딘다`() =
        repeatRounds(rounds = 20, durable = false, mode = CrashMode.SINGLE)

    private fun repeatRounds(rounds: Int, durable: Boolean, mode: CrashMode) {
        repeat(rounds) { round ->
            val dataDir = root.resolve("$mode-$durable-$round")
            val context = "seed=$seed round=$round durable=$durable mode=$mode"
            // 유지 작업 모드는 n번째 유지 작업이 시작될 때 죽인다
            val killAfterAcks = when (mode) {
                CrashMode.MAINTENANCE -> random.nextInt(1, 10) * CrashWorkload.MAINTENANCE_EVERY.toInt()
                else -> random.nextInt(1, 400)
            }
            val lastAck = runAndKill(dataDir, durable, mode, killAfterAcks, context)
            verify(dataDir, mode, lastAck, context)
            verifyFiles(dataDir, context)
        }
    }

    /** 자식을 띄워 ACK를 [killAfterAcks]개 받은 뒤(유지 작업 모드는 그 뒤의 신호에서) SIGKILL을 보낸다. 마지막 ACK 번호를 돌려준다. */
    private fun runAndKill(dataDir: Path, durable: Boolean, mode: CrashMode, killAfterAcks: Int, context: String): Long {
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", childClasspath(),
            "dev.mydb.kv.crash.CrashWriterMainKt",
            factory.java.name, dataDir.toString(), durable.toString(), mode.name,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        var lastAck = -1L
        try {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine()
                    ?: fail("자식 프로세스가 ACK ${lastAck + 1} 전에 종료됐습니다 (exit=${process.waitFor()}) $context")
                if (line == CrashWorkload.MAINTENANCE_SIGNAL) {
                    if (lastAck + 1 >= killAfterAcks) break
                    continue
                }
                lastAck = line.removePrefix("ACK ").toLong()
                if (mode != CrashMode.MAINTENANCE && lastAck + 1 >= killAfterAcks) break
            }
            // 신호 직후뿐 아니라 작업 한가운데서도 죽도록 약간 기다린다
            Thread.sleep(random.nextLong(0, 3))
        } finally {
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "자식 프로세스가 종료되지 않았습니다 $context" }
        }
        return lastAck
    }

    private fun verify(dataDir: Path, mode: CrashMode, lastAck: Long, context: String) {
        val engineFactory = factory.java.getDeclaredConstructor().newInstance()
        engineFactory.open(dataDir, durable = true).use { engine ->
            val entries = engine.scan(ByteArray(0), null).use { it.asSequence().toList() }
            val indexes = entries.map { CrashWorkload.indexOf(it.key) }

            val ackedKeyCount = when (mode) {
                CrashMode.BATCH -> (lastAck + 1) * CrashWorkload.BATCH_SIZE
                else -> lastAck + 1
            }
            assertTrue(indexes.size >= ackedKeyCount, "ACK한 키 $ackedKeyCount 개 중 ${indexes.size} 개만 남았습니다 $context")
            assertEquals((0L until indexes.size).toList(), indexes, "남은 키에 빈틈이 있습니다 $context")
            if (mode == CrashMode.BATCH) {
                assertEquals(0, indexes.size % CrashWorkload.BATCH_SIZE, "배치 일부만 남았습니다: ${indexes.size} 개 $context")
            }
            entries.forEach { assertContentEquals(CrashWorkload.value(CrashWorkload.indexOf(it.key)), it.value, context) }
        }
    }

    private fun childClasspath(): String =
        System.getProperty("crash.child.classpath") ?: error("crash.child.classpath가 없습니다. Gradle test 태스크로 실행하세요")
}
