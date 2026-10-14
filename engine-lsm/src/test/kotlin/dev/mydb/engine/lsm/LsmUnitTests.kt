package dev.mydb.engine.lsm

import dev.mydb.engine.lsm.compaction.SizeTieredPicker
import dev.mydb.engine.lsm.sstable.BloomFilter
import dev.mydb.engine.lsm.sstable.SSTableReader
import dev.mydb.engine.lsm.sstable.SSTableWriter
import dev.mydb.engine.lsm.sstable.TableMeta
import dev.mydb.storage.CorruptionException
import dev.mydb.storage.IoCounters
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

private fun b(s: String) = s.toByteArray()

private fun put(k: String, v: String) = Entry(b(k), MemValue.Put(b(v)))

private fun tomb(k: String) = Entry(b(k), MemValue.Tombstone)

private fun key(n: Int) = "key-%08d".format(n).toByteArray()

class MemTableTest {
    @Test
    fun `정렬 순서로 돌려주고 덮어쓰기와 tombstone을 반영한다`() {
        val table = MemTable()
        table.apply(dev.mydb.storage.LogRecord.Put(b("b"), b("1")))
        table.apply(dev.mydb.storage.LogRecord.Put(b("a"), b("1")))
        table.apply(dev.mydb.storage.LogRecord.Put(b("b"), b("2")))
        table.apply(dev.mydb.storage.LogRecord.Delete(b("c")))

        assertEquals(listOf(put("a", "1"), put("b", "2"), tomb("c")), table.entries().asSequence().toList())
        assertEquals(listOf(put("b", "2")), table.rangeSnapshot(b("b"), b("c")))
    }

    @Test
    fun `크기는 새 키에만 오버헤드를 더하고 덮어쓰기는 값 크기 차이만 반영한다`() {
        val table = MemTable()
        table.apply(dev.mydb.storage.LogRecord.Put(b("k"), ByteArray(10)))
        val afterFirst = table.approximateBytes
        table.apply(dev.mydb.storage.LogRecord.Put(b("k"), ByteArray(4)))

        assertEquals(afterFirst - 6, table.approximateBytes)
    }
}

class MergingIteratorTest {
    @Test
    fun `같은 키는 앞쪽 소스가 이기고 tombstone은 숨긴다`() {
        val newer = listOf(put("a", "new"), tomb("b"), put("d", "d"))
        val older = listOf(put("a", "old"), put("b", "old"), put("c", "c"))

        val merged = MergingIterator(listOf(newer.iterator(), older.iterator()), dropTombstones = true).asSequence().toList()

        assertEquals(listOf(put("a", "new"), put("c", "c"), put("d", "d")), merged)
    }

    @Test
    fun `tombstone을 남기는 모드는 이긴 tombstone을 내보낸다`() {
        val merged = MergingIterator(listOf(listOf(tomb("b")).iterator(), listOf(put("b", "old")).iterator()), dropTombstones = false)

        assertEquals(listOf(tomb("b")), merged.asSequence().toList())
    }

    @Test
    fun `모든 소스에 같은 키가 있어도 하나만 내보낸다`() {
        val sources = List(5) { i -> listOf(put("k", "v$i"), put("z", "z$i")).iterator() }

        assertEquals(listOf(put("k", "v0"), put("z", "z0")), MergingIterator(sources, true).asSequence().toList())
    }

    @Test
    fun `빈 소스와 소스 없음도 처리한다`() {
        assertEquals(emptyList(), MergingIterator(emptyList(), true).asSequence().toList())
        val sources = listOf(emptyList<Entry>().iterator(), listOf(put("a", "1")).iterator())
        assertEquals(listOf(put("a", "1")), MergingIterator(sources, true).asSequence().toList())
    }
}

class BloomFilterTest {
    @Test
    fun `넣은 키는 항상 있음이고 넣지 않은 키의 위양성률은 2퍼센트 미만이다`() {
        val n = 100_000
        val hashes = LongArray(n) { BloomFilter.hash64(key(it)) }
        val filter = BloomFilter.decode(BloomFilter.build(hashes, n, 10).encode())

        repeat(n) { assertTrue(filter.mightContain(key(it))) }
        val rate = (n until 2 * n).count { filter.mightContain(key(it)) }.toDouble() / n
        println("bloom 위양성률 (10 bits/key, 순차 키): $rate")
        assertTrue(rate < 0.02, "위양성률 $rate")
    }

    @Test
    fun `bitsPerKey가 0이면 필터 없이 항상 있음이다`() {
        val filter = BloomFilter.decode(BloomFilter.build(LongArray(0), 0, 0).encode())

        assertTrue(filter.mightContain(b("anything")))
    }
}

class SSTableTest {
    @TempDir
    lateinit var dir: Path

    private val counters = IoCounters()

    @Test
    fun `블록 수천 개짜리 테이블의 모든 키를 찾고 없는 키는 null이다`() {
        val path = write(5_000, blockSize = 128)
        SSTableReader.open(path, counters).use { reader ->
            assertTrue(reader.blockCount > 1_000, "블록 ${reader.blockCount}개")
            for (i in 0 until 10_000 step 2) assertEquals(MemValue.Put(b("v$i")), reader.get(key(i)))
            for (i in 1 until 10_000 step 2) assertNull(reader.get(key(i)))
            assertNull(reader.get(b("zzz")))
            assertNull(reader.get(b("")))
        }
    }

    @Test
    fun `범위 반복은 블록 경계와 상관없이 정확하다`() {
        val path = write(1_000, blockSize = 64)
        SSTableReader.open(path, counters).use { reader ->
            val keys = reader.iterator(key(101), key(301)).asSequence().map { String(it.key) }.toList()
            assertEquals((102 until 301 step 2).map { String(key(it)) }, keys)
            assertEquals(1_000, reader.iterator(ByteArray(0), null).asSequence().count())
            assertEquals(0, reader.iterator(b("zzz"), null).asSequence().count())
        }
    }

    @Test
    fun `tombstone과 블록보다 큰 엔트리도 저장하고 읽는다`() {
        val path = dir.resolve("t.sst")
        val writer = SSTableWriter(path, 1, 1, counters, 64, 10)
        writer.add(put("a", "1"))
        writer.add(Entry(b("big"), MemValue.Put(ByteArray(1000) { 7 })))
        writer.add(tomb("c"))
        writer.finish()

        SSTableReader.open(path, counters).use { reader ->
            assertEquals(MemValue.Tombstone, reader.get(b("c")))
            assertEquals(1000, (reader.get(b("big")) as MemValue.Put).value.size)
            assertEquals(1L, reader.footer.age)
        }
    }

    @Test
    fun `footer가 깨지면 열 때, 블록이 깨지면 읽을 때 CorruptionException이다`() {
        val path = write(100, blockSize = 64)
        flip(path, path.fileSize() - 3)
        assertFailsWith<CorruptionException> { SSTableReader.open(path, counters) }

        val path2 = write(100, blockSize = 64, name = "u.sst")
        flip(path2, 20)
        SSTableReader.open(path2, counters).use { reader -> assertFailsWith<CorruptionException> { reader.get(key(0)) } }
    }

    @Test
    fun `엔트리가 없으면 파일을 만들지 않는다`() {
        val path = dir.resolve("empty.sst")

        assertNull(SSTableWriter(path, 1, 1, counters, 4096, 10).finish())
        assertFalse(path.exists())
        assertTrue(dir.toFile().listFiles()!!.isEmpty())
    }

    @Test
    fun `키가 오름차순이 아니면 거부한다`() {
        val writer = SSTableWriter(dir.resolve("x.sst"), 1, 1, counters, 4096, 10)
        writer.add(put("b", "1"))

        assertFailsWith<IllegalArgumentException> { writer.add(put("a", "1")) }
        assertFailsWith<IllegalArgumentException> { writer.add(put("b", "2")) }
        writer.abort()
    }

    private fun write(count: Int, blockSize: Int, name: String = "t.sst"): Path {
        val path = dir.resolve(name)
        val writer = SSTableWriter(path, 1, 7, counters, blockSize, 10)
        for (i in 0 until count * 2 step 2) writer.add(Entry(key(i), MemValue.Put(b("v$i"))))
        writer.finish()
        return path
    }

    private fun flip(path: Path, position: Long) {
        FileChannel.open(path, WRITE).use { it.write(ByteBuffer.wrap(byteArrayOf(0x5A)), position) }
    }
}

class SizeTieredPickerTest {
    private fun t(id: Long, age: Long, size: Long) = TableMeta(id, age, 1, size, b("a"), b("z"))

    @Test
    fun `크기가 비슷한 나이 연속 구간을 고른다`() {
        val tables = listOf(t(1, 1, 1000), t(2, 2, 100), t(3, 3, 110), t(4, 4, 90), t(5, 5, 105))

        val pick = SizeTieredPicker(4, 32).pick(tables)!!

        assertEquals(listOf(2L, 3L, 4L, 5L), pick.tables.map { it.fileId })
        assertFalse(pick.includesOldest)
    }

    @Test
    fun `임계값 미만이면 고르지 않는다`() {
        assertNull(SizeTieredPicker(4, 32).pick(listOf(t(1, 1, 100), t(2, 2, 100), t(3, 3, 100))))
    }

    @Test
    fun `가장 오래된 테이블을 포함하면 tombstone을 버릴 수 있다고 표시한다`() {
        assertTrue(SizeTieredPicker(2, 32).pick(listOf(t(1, 1, 100), t(2, 2, 100)))!!.includesOldest)
    }

    @Test
    fun `크기가 비슷해도 사이에 다른 크기가 끼면 비연속이라 합치지 않는다`() {
        assertNull(SizeTieredPicker(2, 32).pick(listOf(t(1, 1, 100), t(2, 2, 10_000), t(3, 3, 100))))
    }

    @Test
    fun `maxThreshold를 넘겨 고르지 않는다`() {
        val tables = (1L..10L).map { t(it, it, 100) }

        assertEquals(3, SizeTieredPicker(2, 3).pick(tables)!!.tables.size)
    }
}
