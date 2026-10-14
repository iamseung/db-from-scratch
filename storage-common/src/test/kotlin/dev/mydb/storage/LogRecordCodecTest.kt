package dev.mydb.storage

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogRecordCodecTest {
    private val put = LogRecord.Put("k1".toByteArray(), "v1".toByteArray())
    private val delete = LogRecord.Delete("k2".toByteArray())

    @Test
    fun `PUT 바이트 레이아웃은 설계 문서와 같다`() {
        val expected = byteArrayOf(0x01) + int(2) + int(2) + "k1".toByteArray() + "v1".toByteArray()

        assertContentEquals(expected, LogRecordCodec.encode(put))
    }

    @Test
    fun `DELETE 바이트 레이아웃은 설계 문서와 같다`() {
        val expected = byteArrayOf(0x02) + int(2) + "k2".toByteArray()

        assertContentEquals(expected, LogRecordCodec.encode(delete))
    }

    @Test
    fun `BATCH는 count 뒤에 type 바이트를 포함한 op들이 이어진다`() {
        val batch = LogRecord.Batch(listOf(put, delete))
        val expected = byteArrayOf(0x03) + int(2) + LogRecordCodec.encode(put) + LogRecordCodec.encode(delete)

        assertContentEquals(expected, LogRecordCodec.encode(batch))
    }

    @Test
    fun `모든 타입을 왕복 변환한다`() {
        val records = listOf(
            put,
            delete,
            LogRecord.Put(ByteArray(0), ByteArray(0)),
            LogRecord.Batch(listOf(put, delete, LogRecord.Put("k1".toByteArray(), "v2".toByteArray()))),
            LogRecord.MergeFooter(0),
            LogRecord.MergeFooter(Long.MAX_VALUE),
        )

        records.forEach { assertEquals(it, LogRecordCodec.decode(LogRecordCodec.encode(it))) }
    }

    @Test
    fun `encodedSize는 실제 인코딩 길이와 같다`() {
        listOf(put, delete, LogRecord.Batch(listOf(put, delete)), LogRecord.MergeFooter(7)).forEach {
            assertEquals(LogRecordCodec.encode(it).size.toLong(), LogRecordCodec.encodedSize(it))
        }
    }

    @Test
    fun `최대 크기 키와 값을 왕복 변환한다`() {
        val record = LogRecord.Put(ByteArray(LogRecordCodec.MAX_KEY_SIZE) { 1 }, ByteArray(LogRecordCodec.MAX_VALUE_SIZE) { 2 })

        assertEquals(record, LogRecordCodec.decode(LogRecordCodec.encode(record)))
    }

    @Test
    fun `크기 상한을 넘는 레코드는 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { LogRecord.Put(ByteArray(LogRecordCodec.MAX_KEY_SIZE + 1), ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { LogRecord.Put(ByteArray(0), ByteArray(LogRecordCodec.MAX_VALUE_SIZE + 1)) }
        assertFailsWith<IllegalArgumentException> { LogRecord.Delete(ByteArray(LogRecordCodec.MAX_KEY_SIZE + 1)) }
        assertFailsWith<IllegalArgumentException> { LogRecord.Batch(emptyList()) }
        assertFailsWith<IllegalArgumentException> { LogRecord.MergeFooter(-1) }
    }

    @Test
    fun `프레임 최대 크기를 넘는 배치는 인코딩을 거부한다`() {
        val big = LogRecord.Put(ByteArray(0), ByteArray(LogRecordCodec.MAX_VALUE_SIZE))

        assertFailsWith<IllegalArgumentException> { LogRecordCodec.encode(LogRecord.Batch(listOf(big, big))) }
    }

    @Test
    fun `알 수 없는 타입과 빈 payload는 손상이다`() {
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(byteArrayOf(0x55)) }
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `어느 지점에서 잘린 레코드도 손상으로 판정한다`() {
        val records = listOf(put, delete, LogRecord.Batch(listOf(put, delete)), LogRecord.MergeFooter(3))

        for (record in records) {
            val bytes = LogRecordCodec.encode(record)
            for (len in 0 until bytes.size) {
                assertFailsWith<CorruptionException>("$record 를 $len 바이트로 자른 경우") {
                    LogRecordCodec.decode(bytes.copyOf(len))
                }
            }
        }
    }

    @Test
    fun `레코드 뒤에 남는 바이트가 있으면 손상이다`() {
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(LogRecordCodec.encode(put) + 0) }
    }

    @Test
    fun `길이 필드가 상한을 넘거나 음수면 손상이다`() {
        val tooLongKey = byteArrayOf(0x02) + int(LogRecordCodec.MAX_KEY_SIZE + 1)
        val negativeKey = byteArrayOf(0x02) + int(-1)

        assertFailsWith<CorruptionException> { LogRecordCodec.decode(tooLongKey) }
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(negativeKey) }
    }

    @Test
    fun `배치 안의 배치나 footer는 손상이다`() {
        val nested = byteArrayOf(0x03) + int(1) + LogRecordCodec.encode(LogRecord.Batch(listOf(put)))
        val footerInBatch = byteArrayOf(0x03) + int(1) + LogRecordCodec.encode(LogRecord.MergeFooter(1))

        assertFailsWith<CorruptionException> { LogRecordCodec.decode(nested) }
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(footerInBatch) }
    }

    @Test
    fun `배치 count가 0이거나 남은 바이트로 불가능한 값이면 손상이다`() {
        val zero = byteArrayOf(0x03) + int(0)
        val huge = byteArrayOf(0x03) + int(Int.MAX_VALUE) + LogRecordCodec.encode(delete)

        assertFailsWith<CorruptionException> { LogRecordCodec.decode(zero) }
        assertFailsWith<CorruptionException> { LogRecordCodec.decode(huge) }
    }

    @Test
    fun `프레임과 합쳐 왕복 변환한다`() {
        val batch = LogRecord.Batch(listOf(put, delete))
        val frame = FrameCodec.encode(LogRecordCodec.encode(batch)).toByteArray()

        val read = FrameCodec.read(ByteArrayFrameSource(frame), 0) as FrameReadResult.Ok

        assertEquals(batch, LogRecordCodec.decode(read.payload))
    }

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
