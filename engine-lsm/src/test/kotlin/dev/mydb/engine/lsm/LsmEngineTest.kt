package dev.mydb.engine.lsm

import dev.mydb.engine.lsm.manifest.Manifest
import dev.mydb.storage.FrameCodec
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LsmEngineTest {
    @TempDir
    lateinit var dir: Path

    private fun b(s: String) = s.toByteArray()

    @Test
    fun `memtable이 차면 flush하고 compaction이 테이블 수를 억제한다`() {
        LsmEngine.open(tinyOptions(dir)).use { engine ->
            repeat(500) { engine.put(b("key-%04d".format(it)), b("value-$it")) }

            assertTrue(engine.tableCount() in 1..12, "테이블 ${engine.tableCount()}개")
            repeat(500) { assertEquals("value-$it", String(engine.get(b("key-%04d".format(it)))!!)) }
        }
        // 재시작은 남은 WAL을 flush하므로 테이블이 늘 수 있다. 같은 시점에 MANIFEST와 디렉터리가 일치하는지 본다
        LsmEngine.open(tinyOptions(dir)).use { engine -> assertEquals(engine.tableCount(), engineTableFiles().size) }
    }

    @Test
    fun `compaction이 반복돼도 지운 키가 되살아나지 않는다`() {
        LsmEngine.open(tinyOptions(dir)).use { engine ->
            engine.put(b("victim"), b("old"))
            repeat(100) { engine.put(b("pad-a-%03d".format(it)), b("x")) }
            engine.delete(b("victim"))
            repeat(300) { engine.put(b("pad-b-%03d".format(it)), b("y")) }

            assertNull(engine.get(b("victim")))
        }
        LsmEngine.open(tinyOptions(dir)).use { engine -> assertNull(engine.get(b("victim"))) }
    }

    /**
     * 최신이 아닌 구간을 합칠 때, 결과가 더 새 테이블의 값을 가리면 안 된다.
     * 결과를 "새 파일 = 최신"으로 취급하면 T1의 옛 값이 T3의 새 값을 가린다 (설계 §4.3).
     * 재오픈마다 남은 WAL이 테이블 하나로 flush되는 성질로 크기가 다른 테이블 세 개를 만든다.
     */
    @Test
    fun `오래된 구간만 합쳐도 더 새 테이블의 값이 이긴다`() {
        val noCompaction = tinyOptions(dir).copy(memtableSizeBytes = 1L shl 20, compactionMinThreshold = 100, compactionMaxThreshold = 100)
        LsmEngine.open(noCompaction).use { it.put(b("k"), b("v1")) }
        LsmEngine.open(noCompaction).use { it.put(b("other"), b("x")) }
        LsmEngine.open(noCompaction).use { engine ->
            engine.put(b("k"), b("v3"))
            repeat(200) { engine.put(b("pad-%03d".format(it)), ByteArray(200)) }
        }

        LsmEngine.open(noCompaction.copy(compactionMinThreshold = 2, compactionMaxThreshold = 2)).use { engine ->
            assertEquals(3, engine.tableCount())
            val result = engine.compact()!!

            assertEquals(2, result.inputFileIds.size, "작은 두 테이블만 합쳐야 합니다")
            assertEquals("v3", String(engine.get(b("k"))!!))
            assertEquals("v3", engine.scan(b("k"), b("l")).use { String(it.next().value) })
        }
        LsmEngine.open(noCompaction).use { engine -> assertEquals("v3", String(engine.get(b("k"))!!)) }
    }

    @Test
    fun `compactAll은 테이블 하나만 남기고 tombstone을 모두 버린다`() {
        LsmEngine.open(tinyOptions(dir).copy(compactionMinThreshold = 100, compactionMaxThreshold = 100)).use { engine ->
            repeat(100) { engine.put(b("k-%03d".format(it)), b("v")) }
            repeat(100) { engine.delete(b("k-%03d".format(it))) }

            val result = engine.compactAll()!!

            assertTrue(result.droppedTombstones)
            assertNull(result.outputFileId, "모두 지워졌으므로 결과 테이블이 없어야 합니다")
            assertEquals(0, engine.tableCount())
            assertEquals(0, engine.scan(ByteArray(0), null).use { it.asSequence().count() })
        }
        assertEquals(emptyList(), engineTableFiles())
    }

    @Test
    fun `scan 반복자가 열려 있는 동안 compaction 입력 파일은 지워지지 않는다`() {
        LsmEngine.open(tinyOptions(dir).copy(compactionMinThreshold = 100, compactionMaxThreshold = 100)).use { engine ->
            repeat(200) { engine.put(b("k-%04d".format(it)), b("v$it")) }
            val iterator = engine.scan(ByteArray(0), null)
            val first = iterator.next()

            engine.compactAll()
            val rest = iterator.asSequence().toList()
            val beforeClose = engineTableFiles().size
            iterator.close()

            assertEquals("k-0000", String(first.key))
            assertEquals(199, rest.size)
            assertTrue(beforeClose > 1, "반복자가 붙잡은 입력 파일이 남아 있어야 합니다")
            assertEquals(1, engineTableFiles().size, "close 후 입력 파일이 지워져야 합니다")
        }
    }

    @Test
    fun `각 단계에서 죽어도 재시작 후 모델과 같고 고아 파일이 없다`() {
        for (step in LsmStep.entries) {
            val stepDir = dir.resolve(step.name)
            val model = sortedMapOf<String, String>()
            LsmEngine.open(tinyOptions(stepDir)).use { engine ->
                repeat(60) {
                    engine.put(b("k-%03d".format(it)), b("v$it"))
                    model["k-%03d".format(it)] = "v$it"
                }
                engine.delete(b("k-010"))
                model.remove("k-010")
            }

            val crashing = LsmEngine.open(tinyOptions(stepDir).copy(faultInjector = LsmFaultInjector { if (it == step) throw SimulatedCrash() }))
            assertFailsWith<SimulatedCrash>("$step 지점에 도달해야 합니다") {
                repeat(400) {
                    // 예외는 WAL 기록과 memtable 반영 뒤 flush·compaction에서 나므로, 던진 put도 확정된 쓰기다
                    model["k-%03d".format(it % 60)] = "w$it"
                    crashing.put(b("k-%03d".format(it % 60)), b("w$it"))
                }
                crashing.compactAll()
            }
            crashing.close()

            repeat(2) { attempt ->
                LsmEngine.open(tinyOptions(stepDir)).use { engine ->
                    val actual = engine.scan(ByteArray(0), null).use { iterator ->
                        iterator.asSequence().associate { String(it.key) to String(it.value) }
                    }
                    assertEquals(model, actual.toSortedMap(), "$step 재시작 ${attempt + 1}회차")
                    assertEquals(engine.tableCount(), engineTableFiles(stepDir).size, "$step: MANIFEST가 모르는 .sst가 남았습니다")
                }
            }
            val files = stepDir.toFile().list()!!.toList()
            assertTrue(files.none { it.endsWith(".tmp") }, "$step: tmp 파일이 남았습니다 $files")
        }
    }

    /**
     * sync 전에 일부만 쓰인 편집 = commit되지 않은 변경. 잘라내고 그 이전 상태로 열려야 한다.
     * (이미 sync된 편집을 자르는 것은 크래시가 아니라 디스크 손상이라 여기서 다루지 않는다)
     */
    @Test
    fun `MANIFEST 끝에 쓰다 만 편집이 있으면 버리고 데이터는 그대로다`() {
        LsmEngine.open(tinyOptions(dir)).use { engine ->
            repeat(50) { engine.put(b("k-%03d".format(it)), b("v$it")) }
        }
        val manifest = dir.resolve(Manifest.FILE_NAME)
        val goodSize = manifest.fileSize()
        val torn = FrameCodec.encode(ByteArray(40) { 1 }).array().copyOf(FrameCodec.HEADER_SIZE + 5)
        FileChannel.open(manifest, WRITE).use { it.write(ByteBuffer.wrap(torn), goodSize) }

        LsmEngine.open(tinyOptions(dir)).use { engine ->
            repeat(50) { assertEquals("v$it", String(engine.get(b("k-%03d".format(it)))!!)) }
        }
        assertTrue(manifest.fileSize() >= goodSize, "잘린 꼬리를 정리한 뒤 새 편집이 붙는다")
    }

    private class SimulatedCrash : RuntimeException()

    private fun engineTableFiles(path: Path = dir) = path.toFile().listFiles()!!.filter { it.toPath().name.endsWith(".sst") }
}
