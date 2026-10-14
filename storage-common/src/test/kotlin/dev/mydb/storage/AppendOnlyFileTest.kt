package dev.mydb.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.io.TempDir

class AppendOnlyFileTest {
    @TempDir
    lateinit var dir: Path

    private val counters = IoCounters()
    private val path get() = dir.resolve("000001.log")

    @Test
    fun `append가 돌려준 offset으로 읽으면 같은 payload가 나온다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            val first = file.append("a".toByteArray())
            val second = file.append("bb".toByteArray())

            assertEquals(0L, first)
            assertEquals(FrameCodec.frameSize(1).toLong(), second)
            assertContentEquals("bb".toByteArray(), file.okPayload(second))
            assertContentEquals("a".toByteArray(), file.okPayload(first))
        }
    }

    @Test
    fun `size는 쓴 프레임 크기의 합이고 실제 파일 크기와 같다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            file.append(ByteArray(10))
            file.append(ByteArray(0))

            assertEquals(FrameCodec.frameSize(10) + FrameCodec.frameSize(0).toLong(), file.size)
            assertEquals(file.size, path.fileSize())
        }
    }

    @Test
    fun `다시 열면 기존 프레임이 남아 있고 그 뒤에 이어서 쓴다`() {
        AppendOnlyFile.openForAppend(path, counters).use { it.append("before".toByteArray()); it.sync() }

        AppendOnlyFile.openForAppend(path, counters).use { file ->
            file.append("after".toByteArray())

            assertEquals(listOf("before", "after"), file.payloads())
        }
    }

    @Test
    fun `손상된 꼬리를 잘라내면 이어서 정상적으로 쓸 수 있다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            file.append("committed-1".toByteArray())
            file.append("committed-2".toByteArray())
            file.sync()
        }
        val goodSize = path.fileSize()
        // 다음 프레임을 쓰다가 프로세스가 죽은 상황: 프레임 앞부분만 디스크에 남았다
        val torn = FrameCodec.encode("in-flight".toByteArray()).toByteArray().copyOf(FrameCodec.HEADER_SIZE + 3)
        FileChannel.open(path, APPEND).use { it.write(ByteBuffer.wrap(torn)) }

        AppendOnlyFile.openForAppend(path, counters).use { file ->
            val (offset, result) = file.frames().last()
            assertEquals(FrameReadResult.Corrupted(goodSize, CorruptionReason.TRUNCATED_PAYLOAD), result)

            file.truncate(offset)
            file.append("committed-3".toByteArray())

            assertEquals(listOf("committed-1", "committed-2", "committed-3"), file.payloads())
            assertEquals(torn.size.toLong(), counters.truncatedBytes.get())
        }
    }

    @Test
    fun `truncate는 현재 크기 범위 안에서만 허용한다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            file.append(ByteArray(4))

            assertFailsWith<IllegalArgumentException> { file.truncate(file.size + 1) }
            assertFailsWith<IllegalArgumentException> { file.truncate(-1) }
        }
    }

    @Test
    fun `읽기 전용 파일은 읽기만 된다`() {
        AppendOnlyFile.openForAppend(path, counters).use { it.append("x".toByteArray()) }

        AppendOnlyFile.openReadOnly(path, counters).use { file ->
            assertEquals(listOf("x"), file.payloads())
            assertFailsWith<IllegalStateException> { file.append("y".toByteArray()) }
            assertFailsWith<IllegalStateException> { file.truncate(0) }
            assertFailsWith<IllegalStateException> { file.sync() }
        }
    }

    @Test
    fun `카운터는 디스크에 쓰고 읽은 바이트와 sync 횟수를 센다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            val offset = file.append(ByteArray(100))
            file.sync()
            file.readFrame(offset)

            val frameSize = FrameCodec.frameSize(100).toLong()
            assertEquals(frameSize, counters.bytesWritten.get())
            assertEquals(frameSize, counters.bytesRead.get())
            assertEquals(1L, counters.syncCount.get())
        }
    }

    @Test
    fun `빈 파일은 바로 EndOfFile이다`() {
        AppendOnlyFile.openForAppend(path, counters).use { file ->
            assertEquals(0L, file.size)
            assertEquals(FrameReadResult.EndOfFile, file.readFrame(0))
        }
    }

    private fun AppendOnlyFile.okPayload(offset: Long): ByteArray {
        val result = readFrame(offset)
        assertIs<FrameReadResult.Ok>(result)
        return result.payload
    }

    private fun AppendOnlyFile.payloads(): List<String> =
        frames().map { (_, result) -> String((result as FrameReadResult.Ok).payload) }.toList()
}
