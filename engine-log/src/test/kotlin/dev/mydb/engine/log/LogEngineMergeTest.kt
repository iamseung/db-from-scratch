package dev.mydb.engine.log

import dev.mydb.kv.WriteOp
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LogEngineMergeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `merge는 덮어쓴 값과 삭제된 키의 공간을 회수한다`() {
        open().use { engine ->
            repeat(50) { round -> repeat(5) { engine.put(b("key-$it"), b("value-$round")) } }
            engine.delete(b("key-4"))
            engine.put(b("in-active"), b("x"))

            val result = engine.merge()

            // 최신 값 중 일부는 활성 세그먼트에 있어 merge 대상이 아니다
            assertTrue(result.liveEntries in 1..4, "살아 있는 엔트리 ${result.liveEntries}")
            assertTrue(result.reclaimedBytes > result.bytesAfter * 10, "회수 $result")
            repeat(4) { assertContentEquals(b("value-49"), engine.get(b("key-$it"))) }
            assertNull(engine.get(b("key-4")))
        }
        assertEquals(2, logNames().size, "병합 세그먼트 1개 + 활성 세그먼트 1개")
    }

    @Test
    fun `merge로 tombstone을 버려도 재시작 후 지운 키가 되살아나지 않는다`() {
        open().use { engine ->
            engine.put(b("deleted"), b("old"))
            repeat(20) { engine.put(b("filler-$it"), b("v")) }
            engine.delete(b("deleted"))
            repeat(20) { engine.put(b("filler2-$it"), b("v")) }
            engine.merge()
        }

        open().use { engine ->
            assertNull(engine.get(b("deleted")))
            assertContentEquals(b("v"), engine.get(b("filler-0")))
        }
    }

    @Test
    fun `불변 세그먼트가 없으면 아무 일도 하지 않는다`() {
        open(segmentSize = 1L shl 20).use { engine ->
            engine.put(b("k"), b("v"))

            assertEquals(MergeResult(emptyList(), 0, 0, 0), engine.merge())
            assertContentEquals(b("v"), engine.get(b("k")))
        }
    }

    @Test
    fun `배치로 쓴 값도 merge 후 그대로 읽힌다`() {
        open().use { engine ->
            engine.writeBatch(listOf(WriteOp.Put(b("a"), b("1")), WriteOp.Put(b("b"), b("2")), WriteOp.Put(b("a"), b("3"))))
            repeat(20) { engine.put(b("filler-$it"), b("v")) }

            engine.merge()

            assertContentEquals(b("3"), engine.get(b("a")))
            assertContentEquals(b("2"), engine.get(b("b")))
        }
    }

    @Test
    fun `merge 뒤에도 이어서 쓰고 다시 merge할 수 있다`() {
        open().use { engine ->
            repeat(30) { engine.put(b("first-$it"), b("v1")) }
            engine.merge()
            repeat(30) { engine.put(b("second-$it"), b("v2")) }
            engine.put(b("first-0"), b("updated"))
            engine.merge()
        }

        open().use { engine ->
            assertContentEquals(b("updated"), engine.get(b("first-0")))
            assertContentEquals(b("v1"), engine.get(b("first-29")))
            assertContentEquals(b("v2"), engine.get(b("second-29")))
        }
        assertTrue(mergeNames().isEmpty())
    }

    @Test
    fun `footer 전에 죽으면 merge는 없던 일이 된다`() =
        crashAt(MergeStep.ENTRY_WRITTEN, expectMerged = false)

    @Test
    fun `footer를 쓰고 sync 전에 죽어도 프로세스 크래시면 footer가 남아 merge가 확정된다`() =
        crashAt(MergeStep.FOOTER_WRITTEN, expectMerged = true)

    @Test
    fun `commit 직후 죽으면 재시작이 설치를 마저 한다`() =
        crashAt(MergeStep.COMMITTED, expectMerged = true)

    @Test
    fun `옛 세그먼트를 일부만 지우고 죽어도 재시작이 나머지를 지운다`() =
        crashAt(MergeStep.OLD_LOG_DELETED, expectMerged = true)

    @Test
    fun `rename 직후 죽어도 정상이다`() =
        crashAt(MergeStep.RENAMED, expectMerged = true)

    @Test
    fun `footer가 깨진 merge 파일은 버리고 원래 세그먼트를 쓴다`() {
        val expected = writeWorkload()
        val segmentsBefore = logNames()
        assertFailsWith<SimulatedCrash> { openCrashingAt(MergeStep.COMMITTED).use { it.merge() } }
        val mergeFile = SegmentFiles.listMerges(dir).single().second
        FileChannel.open(mergeFile, WRITE).use { it.truncate(mergeFile.fileSize() - 1) }

        open().use { engine -> assertState(engine, expected) }

        assertEquals(segmentsBefore, logNames())
        assertTrue(mergeNames().isEmpty())
    }

    /**
     * [step]에서 크래시를 흉내 낸 뒤 재시작한다. 어느 지점이든 데이터는 같아야 하고,
     * 파일 구성만 commit point 전후로 달라진다.
     */
    private fun crashAt(step: MergeStep, expectMerged: Boolean) {
        val expected = writeWorkload()
        val segmentsBefore = logNames()
        val activeBefore = segmentsBefore.last()

        assertFailsWith<SimulatedCrash> { openCrashingAt(step).use { it.merge() } }

        open().use { engine ->
            assertState(engine, expected)
            // 재시작 후에도 다음 merge가 정상 동작해야 한다
            engine.put(b("after-crash"), b("v"))
            engine.merge()
            assertContentEquals(b("v"), engine.get(b("after-crash")))
        }
        open().use { engine -> assertState(engine, expected + ("after-crash" to "v")) }
        assertTrue(mergeNames().isEmpty(), "merge 파일이 남았습니다: ${mergeNames()}")

        // 위의 두 번째 merge가 파일 구성을 바꿨으므로, 크래시 직후 상태는 별도로 다시 만들어 확인한다
        dir.toFile().listFiles()!!.forEach { it.deleteRecursively() }
        writeWorkload()
        assertFailsWith<SimulatedCrash> { openCrashingAt(step).use { it.merge() } }
        open().close()
        val after = logNames()
        if (expectMerged) {
            assertEquals(listOf(segmentsBefore[segmentsBefore.size - 2], activeBefore), after, "병합 세그먼트 + 활성 세그먼트")
        } else {
            assertEquals(segmentsBefore, after, "원래 세그먼트 그대로")
        }
    }

    /** 덮어쓰기·삭제·배치가 섞인 상태를 만들고 기대 상태를 돌려준다. */
    private fun writeWorkload(): Map<String, String> {
        val model = sortedMapOf<String, String>()
        open().use { engine ->
            repeat(3) { round ->
                repeat(10) {
                    engine.put(b("key-$it"), b("value-$round-$it"))
                    model["key-$it"] = "value-$round-$it"
                }
            }
            engine.delete(b("key-3"))
            model.remove("key-3")
            engine.writeBatch(listOf(WriteOp.Put(b("batched"), b("b")), WriteOp.Delete(b("key-4"))))
            model["batched"] = "b"
            model.remove("key-4")
            engine.put(b("active-only"), b("a"))
            model["active-only"] = "a"
        }
        return model
    }

    private fun assertState(engine: LogEngine, expected: Map<String, String>) {
        val actual = engine.scan(ByteArray(0), null).use { iterator ->
            iterator.asSequence().associate { String(it.key) to String(it.value) }
        }
        assertEquals(expected.toSortedMap(), actual.toSortedMap())
    }

    private class SimulatedCrash : RuntimeException()

    /** 지정한 단계에 처음 도달할 때 예외를 던진다. */
    private fun openCrashingAt(step: MergeStep) = LogEngine.open(
        LogEngineOptions(dir, segmentSizeBytes = SEGMENT_SIZE, faultInjector = { if (it == step) throw SimulatedCrash() }),
    )

    private fun open(segmentSize: Long = SEGMENT_SIZE) = LogEngine.open(LogEngineOptions(dir, segmentSizeBytes = segmentSize))

    private fun logNames(): List<String> = SegmentFiles.listLogs(dir).map { (_, path) -> path.name }

    private fun mergeNames(): List<String> = SegmentFiles.listMerges(dir).map { (_, path) -> path.name }

    private fun b(s: String) = s.toByteArray()

    companion object {
        /** 프레임 몇 개마다 세그먼트가 넘어가도록 작게 잡는다. */
        private const val SEGMENT_SIZE = 256L
    }
}
