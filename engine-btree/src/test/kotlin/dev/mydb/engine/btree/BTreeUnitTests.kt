package dev.mydb.engine.btree

import dev.mydb.storage.IoCounters
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

private fun b(s: String) = s.toByteArray()

class PageTest {
    private fun leaf(size: Int = 512) = Page(1, ByteArray(size)).apply { init(Page.LEAF) }

    @Test
    fun `슬롯은 키 순서를 유지하고 이진 탐색으로 찾는다`() {
        val page = leaf()
        listOf("c", "a", "b").forEach { k ->
            val pos = page.search(b(k))
            assertTrue(pos < 0)
            assertTrue(page.insertLeaf(-(pos + 1), b(k), b("v-$k")))
        }

        assertEquals(listOf("a", "b", "c"), (0 until page.cellCount).map { String(page.keyAt(it)) })
        assertEquals(1, page.search(b("b")))
        assertEquals(-3, page.search(b("bb")))
        assertContentEquals(b("v-c"), page.valueAt(2))
    }

    @Test
    fun `삭제로 생긴 구멍은 compact로 회수해 다시 쓴다`() {
        val page = leaf()
        var n = 0
        while (page.insertLeaf(n, b("k%03d".format(n)), ByteArray(20))) n++
        val full = n
        repeat(full / 2) { page.removeAt(0) }

        // 남은 연속 공간은 부족하지만 구멍까지 합치면 충분하다 → compact 후 삽입 성공
        repeat(full / 2) { assertTrue(page.insertLeaf(page.cellCount, b("z%03d".format(it)), ByteArray(20)), "재삽입 $it") }
        assertEquals(full - full / 2 + full / 2, page.cellCount)
        assertFalse(page.insertLeaf(page.cellCount, b("zzz"), ByteArray(20)))
    }

    @Test
    fun `upperBound는 key보다 큰 첫 슬롯이다`() {
        val page = Page(2, ByteArray(512)).apply { init(Page.INTERNAL) }
        page.insertInternal(0, b("b"), 10)
        page.insertInternal(1, b("d"), 20)
        page.link = 30

        assertEquals(10, page.childFor(page.upperBound(b("a"))))
        assertEquals(20, page.childFor(page.upperBound(b("b")))) // b 이상은 다음 자식
        assertEquals(30, page.childFor(page.upperBound(b("d"))))
        assertEquals(30, page.childFor(page.upperBound(b("z"))))
    }

    @Test
    fun `체크섬은 한 바이트만 바뀌어도 불일치다`() {
        val page = leaf()
        page.insertLeaf(0, b("k"), b("v"))
        page.writeChecksum()
        assertTrue(page.checksumMatches())

        page.bytes[300] = (page.bytes[300] + 1).toByte()
        assertFalse(page.checksumMatches())
    }

    @Test
    fun `셀 크기 상한은 리프에 셀 4개가 들어가게 한다`() {
        val max = Page.maxCellPayload(512)
        val page = leaf()
        repeat(4) { assertTrue(page.insertLeaf(it, b("$it").copyOf(10), ByteArray(max - 10)), "셀 $it") }
    }
}

class BufferPoolTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `깨끗하고 pin되지 않은 페이지만 LRU 순으로 내보내고 더러운 페이지는 남긴다`() {
        Pager(dir.resolve("db"), 512, IoCounters()).use { pager ->
            pager.pageCount = 1
            val pool = BufferPool(16, pager)
            val frames = List(16) { pool.newPage(Page.LEAF) }
            frames.forEach { pool.unpin(it) }

            // 전부 더러우면 (no-steal) 내보낼 게 없다
            assertFalse(pool.hasHeadroom(1))
            assertFailsWith<IllegalStateException> { pool.newPage(Page.LEAF) }

            // 디스크에 쓰고 깨끗하게 만들면 내보낼 수 있다
            frames.forEach { pager.write(it.page) }
            pool.markAllClean()
            assertTrue(pool.hasHeadroom(16))
            val pinned = pool.fetch(frames[0].page.id)
            assertTrue(pool.hasHeadroom(15) && !pool.hasHeadroom(16), "pin된 프레임은 내보낼 수 없다")
            pool.unpin(pinned)
        }
    }
}

/**
 * 페이지 512 B로 split은 충분히 일어나게 하고, 버퍼 풀은 256장으로 키운다.
 * 16장이면 체크포인트(macOS에서 fsync 여러 번)가 수천 번 일어나 82초가 걸렸다.
 */
class BTreeTest {
    @TempDir
    lateinit var dir: Path

    private fun options(path: Path) = tinyOptions(path).copy(bufferPoolPages = 256, syncPolicy = dev.mydb.storage.SyncPolicy.MANUAL)

    private fun keyOf(n: Int) = "key-%08d".format(n).toByteArray()

    @Test
    fun `순차·역순·무작위 삽입 각 2만 건 후 불변식과 모든 값이 맞다`() {
        val orders = mapOf(
            "sequential" to (0 until 20_000).toList(),
            "reverse" to (0 until 20_000).reversed(),
            "random" to (0 until 20_000).shuffled(kotlin.random.Random(42)),
        )
        for ((name, order) in orders) {
            BTreeEngine.open(options(dir.resolve(name))).use { engine ->
                order.forEach { engine.put(keyOf(it), b("v$it")) }
                val report = engine.verify()

                assertEquals(20_000L, report.keys, name)
                assertTrue(report.height >= 3, "$name height ${report.height}")
                // 바이트 기준 절반 split이라 최소 채움률은 약 0.5. 무작위 삽입 평균은 이론값 ln 2 ≈ 0.69에 가깝다
                assertTrue(report.minLeafFill >= 0.4, "$name 최소 리프 채움률 ${report.minLeafFill}")
                (0 until 20_000).forEach { assertContentEquals(b("v$it"), engine.get(keyOf(it)), name) }
                println("$name: $report")
            }
        }
    }

    @Test
    fun `삭제 후에도 불변식이 유지되고 지운 키는 없다`() {
        BTreeEngine.open(options(dir)).use { engine ->
            repeat(10_000) { engine.put(keyOf(it), b("v$it")) }
            (0 until 10_000 step 3).forEach { engine.delete(keyOf(it)) }
            val report = engine.verify()

            assertEquals((0 until 10_000).count { it % 3 != 0 }.toLong(), report.keys)
            (0 until 10_000).forEach { assertEquals(if (it % 3 == 0) null else "v$it", engine.get(keyOf(it))?.let(::String)) }
            println("삭제 후: $report")
        }
    }

    @Test
    fun `셀 상한을 넘는 값은 거부한다`() {
        BTreeEngine.open(tinyOptions(dir)).use { engine ->
            assertFailsWith<IllegalArgumentException> { engine.put(b("k"), ByteArray(Page.maxCellPayload(512))) }
        }
    }
}
