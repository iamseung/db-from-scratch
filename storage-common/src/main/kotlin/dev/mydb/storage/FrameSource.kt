package dev.mydb.storage

import java.nio.ByteBuffer

/**
 * 프레임을 읽어 올 수 있는 바이트 저장소.
 * 메모리 배열과 파일이 같은 디코딩 코드를 쓰게 하려는 추상화다.
 */
interface FrameSource {
    val size: Long

    /** [position]부터 [dst]의 남은 공간을 정확히 채운다. 호출자는 범위가 [size] 안임을 보장한다. */
    fun readFully(position: Long, dst: ByteBuffer)
}

class ByteArrayFrameSource(private val bytes: ByteArray) : FrameSource {
    override val size: Long
        get() = bytes.size.toLong()

    override fun readFully(position: Long, dst: ByteBuffer) {
        dst.put(bytes, position.toInt(), dst.remaining())
    }
}
