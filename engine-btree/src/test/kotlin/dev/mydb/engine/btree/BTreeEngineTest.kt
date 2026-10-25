package dev.mydb.engine.btree

import dev.mydb.kv.WriteOp
import dev.mydb.storage.CorruptionException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BTreeEngineTest {
    @TempDir
    lateinit var dir: Path

    private fun b(s: String) = s.toByteArray()

    private class SimulatedCrash : RuntimeException()

    /** 덮어쓰기·삭제·배치가 섞인 상태를 만든다. 체크포인트는 버퍼 풀이 차면서 자연히 여러 번 일어난다. */
    private fun writeWorkload(engine: BTreeEngine, model: MutableMap<String, String>, rounds: Int) {
        repeat(rounds) { i ->
            val k = "k-%04d".format(i % 700)
            model[k] = "v$i"
            if (i % 50 == 49) {
                // 예외(크래시 흉내)는 WAL 기록 뒤에 나므로, 던진 연산도 확정된 쓰기다 → 모델을 먼저 갱신
                model.remove("k-%04d".format((i + 1) % 700))
                engine.writeBatch(listOf(WriteOp.Put(b(k), b("v$i")), WriteOp.Delete(b("k-%04d".format((i + 1) % 700)))))
            } else {
                engine.put(b(k), b("v$i"))
            }
        }
    }

    private fun snapshot(engine: BTreeEngine) =
        engine.scan(ByteArray(0), null).use { it.asSequence().associate { e -> String(e.key) to String(e.value) } }.toSortedMap()

    @Test
    fun `체크포인트 각 단계에서 죽어도 재시작 후 모델과 같고 불변식이 유지된다`() {
        val cases = listOf(BTreeStep.DW_SYNCED to 1, BTreeStep.PAGE_WRITTEN to 1, BTreeStep.PAGE_WRITTEN to 5, BTreeStep.CHECKPOINT_DONE to 1)
        for ((step, nth) in cases) {
            val caseDir = dir.resolve("$step-$nth")
            val model = sortedMapOf<String, String>()
            BTreeEngine.open(tinyOptions(caseDir)).use { writeWorkload(it, model, 1_000) }

            var reached = 0
            var armed = false // open의 복구 체크포인트에서는 발동하지 않게 한다
            val crashing = BTreeEngine.open(tinyOptions(caseDir).copy(faultInjector = { if (armed && it == step && ++reached == nth) throw SimulatedCrash() }))
            armed = true
            assertFailsWith<SimulatedCrash>("$step #$nth 에 도달해야 합니다") { writeWorkload(crashing, model, 3_000) }
            crashing.close()

            repeat(2) { attempt ->
                BTreeEngine.open(tinyOptions(caseDir)).use { engine ->
                    assertEquals(model, snapshot(engine), "$step #$nth 재시작 ${attempt + 1}회차")
                    engine.verify()
                }
            }
            assertFalse(caseDir.resolve(DoubleWrite.FILE_NAME).exists(), "$step: doublewrite가 남았습니다")
        }
    }

    @Test
    fun `제자리 쓰기 중 찢어진 페이지는 doublewrite로 복구된다`() {
        val model = sortedMapOf<String, String>()
        BTreeEngine.open(tinyOptions(dir)).use { writeWorkload(it, model, 500) }
        var reached = 0
        var armed = false
        val crashing = BTreeEngine.open(tinyOptions(dir).copy(faultInjector = { if (armed && it == BTreeStep.PAGE_WRITTEN && ++reached == 1) throw SimulatedCrash() }))
        armed = true
        assertFailsWith<SimulatedCrash> { writeWorkload(crashing, model, 2_000) }
        crashing.close()

        // doublewrite에 들어 있는 페이지 하나를 DB 파일에서 반쯤 덮어써 찢어진 쓰기를 흉내 낸다
        val pages = DoubleWrite.readComplete(dir.resolve(DoubleWrite.FILE_NAME), 512, dev.mydb.storage.IoCounters())!!
        val victim = pages.first { it.first != 0 }.first
        FileChannel.open(dir.resolve("data.db"), WRITE).use { it.write(ByteBuffer.wrap(ByteArray(200) { 0x7F }), victim * 512L + 100) }

        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            assertEquals(model, snapshot(engine))
            engine.verify()
        }
    }

    @Test
    fun `doublewrite에 없는 페이지가 깨지면 읽을 때 CorruptionException이다`() {
        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            repeat(200) { engine.put(b("k-%04d".format(it)), b("v$it")) }
            engine.checkpoint()
        }
        FileChannel.open(dir.resolve("data.db"), WRITE).use { it.write(ByteBuffer.wrap(ByteArray(8) { 0x7F }), 1 * 512L + 300) }

        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            assertFailsWith<CorruptionException> { repeat(200) { engine.get(b("k-%04d".format(it))) } }
        }
    }

    @Test
    fun `버퍼 풀보다 큰 쓰기도 체크포인트를 거듭하며 끝까지 적용된다`() {
        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            engine.writeBatch(List(400) { WriteOp.Put(b("batch-%04d".format(it)), b("v$it")) })
            repeat(400) { assertEquals("v$it", engine.get(b("batch-%04d".format(it)))?.let(::String)) }
        }
        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            repeat(400) { assertEquals("v$it", engine.get(b("batch-%04d".format(it)))?.let(::String)) }
            engine.verify()
        }
    }

    @Test
    fun `다른 페이지 크기로 열면 거부한다`() {
        BTreeEngine.open(tinyOptions(dir)).close()

        assertFailsWith<IllegalArgumentException> { BTreeEngine.open(tinyOptions(dir).copy(pageSize = 1024)) }
        assertTrue(dir.resolve("data.db").exists())
    }
}
