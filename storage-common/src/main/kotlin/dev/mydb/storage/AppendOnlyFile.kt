package dev.mydb.storage

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * 프레임을 끝에 추가하고 위치 지정으로 읽는 파일.
 *
 * - 쓰기 위치는 OS의 APPEND 모드 대신 [size]로 직접 관리한다. 그래야 append가 프레임 시작 offset을 정확히 돌려줄 수 있다.
 * - 읽기는 pread(`channel.read(dst, position)`)라 채널의 공유 position을 건드리지 않는다.
 * - append 도중 예외가 나면 [size]는 그대로이고, 일부 쓰인 바이트는 다음 append가 덮어쓴다.
 *   그 사이 프로세스가 죽으면 재오픈 시 꼬리가 손상 프레임으로 보이므로 호출자가 [truncate]로 정리한다.
 * - 쓰기는 단일 스레드에서만 호출한다. 읽기는 여러 스레드에서 호출해도 된다.
 */
class AppendOnlyFile private constructor(
    val path: Path,
    private val channel: FileChannel,
    private val counters: IoCounters,
    private val writable: Boolean,
) : FrameSource, AutoCloseable {

    /** 다음 append 위치 = 논리적 파일 크기. */
    @Volatile
    override var size: Long = channel.size()
        private set

    /** 프레임을 쓰고 그 프레임의 시작 offset을 돌려준다. 디스크 확정은 [sync]가 따로 한다. */
    fun append(payload: ByteArray): Long {
        checkWritable()
        val frame = FrameCodec.encode(payload)
        val offset = size
        var position = offset
        while (frame.hasRemaining()) {
            position += channel.write(frame, position)
        }
        size = position
        counters.bytesWritten.addAndGet(position - offset)
        return offset
    }

    fun readFrame(offset: Long): FrameReadResult = FrameCodec.read(this, offset)

    fun frames(from: Long = 0): Sequence<Pair<Long, FrameReadResult>> = FrameCodec.readAll(this, from)

    /** 복구용. [newSize] 뒤의 바이트를 버린다. */
    fun truncate(newSize: Long) {
        checkWritable()
        require(newSize in 0..size) { "truncate 위치가 범위를 벗어났습니다: $newSize (size=$size)" }
        channel.truncate(newSize)
        counters.truncatedBytes.addAndGet(size - newSize)
        size = newSize
    }

    /**
     * 지금까지 쓴 내용을 디스크에 확정한다 (fsync).
     * macOS의 fsync는 디스크 자체 캐시까지 비우지 않으므로 전원 차단까지 보장하지는 않는다.
     */
    fun sync() {
        checkWritable()
        channel.force(true)
        counters.syncCount.incrementAndGet()
    }

    override fun readFully(position: Long, dst: ByteBuffer) {
        var current = position
        while (dst.hasRemaining()) {
            val read = channel.read(dst, current)
            if (read < 0) throw EOFException("$path 의 $current 위치에서 파일이 끝났습니다 (기대 size=$size)")
            current += read
        }
        counters.bytesRead.addAndGet(current - position)
    }

    override fun close() {
        channel.close()
    }

    private fun checkWritable() {
        check(writable) { "읽기 전용으로 연 파일입니다: $path" }
    }

    companion object {
        /** 없으면 만든다. 기존 내용은 그대로 두며, 손상된 꼬리 정리는 호출자의 복구 정책을 따른다. */
        fun openForAppend(path: Path, counters: IoCounters): AppendOnlyFile =
            AppendOnlyFile(path, FileChannel.open(path, CREATE, READ, WRITE), counters, writable = true)

        fun openReadOnly(path: Path, counters: IoCounters): AppendOnlyFile =
            AppendOnlyFile(path, FileChannel.open(path, READ), counters, writable = false)
    }
}
