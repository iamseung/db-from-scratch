package dev.mydb.engine.log

import dev.mydb.kv.WriteOp
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.FrameCodec
import dev.mydb.storage.LogRecord
import dev.mydb.storage.LogRecordCodec
import dev.mydb.storage.RecoveryMode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
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

class LogEngineTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `빈 디렉터리를 열면 첫 세그먼트를 만든다`() {
        open().close()

        assertEquals(listOf("000001.log"), logNames())
    }

    @Test
    fun `세그먼트가 가득 차면 다음 id로 넘어가고 재시작 후에도 모두 읽힌다`() {
        val frameSize = FrameCodec.frameSize(LogRecordCodec.encode(put("key-0", "value-0")).size).toLong()
        open(segmentSize = frameSize * 3).use { engine ->
            repeat(10) { engine.put(b("key-$it"), b("value-$it")) }
        }

        assertEquals(listOf("000001.log", "000002.log", "000003.log", "000004.log"), logNames())
        open().use { engine ->
            repeat(10) { assertContentEquals(b("value-$it"), engine.get(b("key-$it"))) }
        }
    }

    @Test
    fun `세그먼트보다 큰 레코드는 빈 세그먼트에 혼자 들어간다`() {
        open(segmentSize = 64).use { engine ->
            engine.put(b("small"), b("v"))
            engine.put(b("big"), ByteArray(1000))
            engine.put(b("after"), b("v"))

            assertEquals(1000, engine.get(b("big"))!!.size)
        }

        assertEquals(3, logNames().size)
    }

    @Test
    fun `마지막 세그먼트의 잘린 꼬리는 잘라내고 이어서 쓴다`() {
        open().use { engine -> engine.put(b("committed"), b("v")) }
        val goodSize = lastLog().fileSize()
        appendTorn(lastLog(), put("in-flight", "x"))

        open().use { engine ->
            assertContentEquals(b("v"), engine.get(b("committed")))
            assertNull(engine.get(b("in-flight")))
            assertEquals(goodSize, lastLog().fileSize())

            engine.put(b("after-recovery"), b("v2"))
        }
        open().use { engine -> assertContentEquals(b("v2"), engine.get(b("after-recovery"))) }
    }

    @Test
    fun `잘린 배치는 op 하나도 반영되지 않는다`() {
        open().use { engine -> engine.put(b("a"), b("old")) }
        appendTorn(lastLog(), LogRecord.Batch(listOf(put("a", "new"), put("b", "new"))))

        open().use { engine ->
            assertContentEquals(b("old"), engine.get(b("a")))
            assertNull(engine.get(b("b")))
        }
    }

    @Test
    fun `STRICT 모드는 잘린 꼬리를 자르지 않고 실패한다`() {
        open().use { engine -> engine.put(b("k"), b("v")) }
        appendTorn(lastLog(), put("in-flight", "x"))
        val tornSize = lastLog().fileSize()

        assertFailsWith<CorruptionException> { open(recoveryMode = RecoveryMode.STRICT) }
        assertEquals(tornSize, lastLog().fileSize())
    }

    @Test
    fun `불변 세그먼트가 손상되면 TOLERATE_TAIL이어도 실패한다`() {
        open(segmentSize = 1).use { engine ->
            engine.put(b("a"), b("1"))
            engine.put(b("b"), b("2"))
        }
        val first = SegmentFiles.logPath(dir, 1)
        FileChannel.open(first, WRITE).use { it.write(ByteBuffer.wrap(byteArrayOf(0x7F)), first.fileSize() - 1) }

        assertFailsWith<CorruptionException> { open() }
    }

    @Test
    fun `손상으로 열기에 실패해도 잠금은 풀린다`() {
        open().use { engine -> engine.put(b("k"), b("v")) }
        appendTorn(lastLog(), put("in-flight", "x"))
        assertFailsWith<CorruptionException> { open(recoveryMode = RecoveryMode.STRICT) }

        open().close()
    }

    @Test
    fun `같은 디렉터리를 두 엔진이 동시에 열 수 없다`() {
        open().use {
            assertFailsWith<IllegalStateException> { open() }
        }
        open().close()
    }

    @Test
    fun `닫은 엔진은 사용할 수 없고 close는 여러 번 불러도 된다`() {
        val engine = open()
        engine.close()
        engine.close()

        assertFailsWith<IllegalStateException> { engine.get(b("k")) }
        assertFailsWith<IllegalStateException> { engine.put(b("k"), b("v")) }
    }

    @Test
    fun `scan 반복자는 만든 시점의 키 목록을 따른다`() {
        open().use { engine ->
            engine.put(b("a"), b("1"))
            engine.put(b("b"), b("2"))
            val iterator = engine.scan(ByteArray(0), null)
            engine.put(b("c"), b("3"))

            assertEquals(listOf("a", "b"), iterator.asSequence().map { String(it.key) }.toList())
        }
    }

    @Test
    fun `디스크 사용량은 세그먼트 파일 크기의 합이다`() {
        open(segmentSize = 1).use { engine ->
            engine.put(b("a"), b("1"))
            engine.writeBatch(listOf(WriteOp.Put(b("b"), b("2")), WriteOp.Delete(b("a"))))

            val onDisk = Files.list(dir).use { files -> files.filter { it.name.endsWith(".log") }.toList().sumOf { it.fileSize() } }
            assertEquals(onDisk, engine.stats().diskBytesUsed)
            assertTrue(engine.stats().writeAmplification > 1.0)
        }
    }

    private fun open(segmentSize: Long = 4L * 1024 * 1024, recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL) =
        LogEngine.open(LogEngineOptions(dir, segmentSizeBytes = segmentSize, recoveryMode = recoveryMode))

    /** 프레임을 쓰다가 프로세스가 죽은 상황: 헤더와 payload 일부만 남긴다. */
    private fun appendTorn(path: Path, record: LogRecord) {
        val frame = FrameCodec.encode(LogRecordCodec.encode(record))
        val torn = ByteArray(FrameCodec.HEADER_SIZE + 2).also { frame.get(it) }
        FileChannel.open(path, APPEND).use { it.write(ByteBuffer.wrap(torn)) }
    }

    private fun logNames(): List<String> = SegmentFiles.listLogs(dir).map { (_, path) -> path.name }

    private fun lastLog(): Path = SegmentFiles.listLogs(dir).last().second

    private fun put(key: String, value: String) = LogRecord.Put(b(key), b(value))

    private fun b(s: String) = s.toByteArray()
}
