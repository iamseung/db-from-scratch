package dev.mydb.kv

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 모든 KvEngine 구현이 지켜야 하는 의미.
 * 엔진 모듈은 이 클래스를 상속해 [openEngine]만 구현한다.
 */
abstract class KvEngineContractTest {
    @TempDir
    lateinit var dir: Path

    /** 같은 [dir]로 다시 부르면 이전에 닫은 엔진의 데이터를 이어서 열어야 한다. */
    abstract fun openEngine(dir: Path): KvEngine

    @Test
    fun `없는 키는 null이다`() = withEngine { engine ->
        assertNull(engine.get(b("missing")))
    }

    @Test
    fun `put한 값을 get으로 읽는다`() = withEngine { engine ->
        engine.put(b("k"), b("v"))

        assertContentEquals(b("v"), engine.get(b("k")))
    }

    @Test
    fun `같은 키에 다시 put하면 덮어쓴다`() = withEngine { engine ->
        engine.put(b("k"), b("v1"))
        engine.put(b("k"), b("v2"))

        assertContentEquals(b("v2"), engine.get(b("k")))
    }

    @Test
    fun `delete하면 null이 되고 없는 키 delete는 아무 일도 없다`() = withEngine { engine ->
        engine.put(b("k"), b("v"))
        engine.delete(b("k"))
        engine.delete(b("never-existed"))

        assertNull(engine.get(b("k")))
    }

    @Test
    fun `빈 값은 없는 키와 구별된다`() = withEngine { engine ->
        engine.put(b("empty"), ByteArray(0))

        assertContentEquals(ByteArray(0), engine.get(b("empty")))
    }

    @Test
    fun `빈 키도 쓸 수 있다`() = withEngine { engine ->
        engine.put(ByteArray(0), b("v"))

        assertContentEquals(b("v"), engine.get(ByteArray(0)))
    }

    @Test
    fun `put 이후 호출자가 배열을 바꿔도 저장된 키와 값은 그대로다`() = withEngine { engine ->
        val key = b("k")
        val value = b("v")
        engine.put(key, value)
        key[0] = 'x'.code.toByte()
        value[0] = 'x'.code.toByte()

        assertContentEquals(b("v"), engine.get(b("k")))
        assertNull(engine.get(b("x")))
    }

    @Test
    fun `scan은 from 이상 to 미만을 부호 없는 바이트 순서로 돌려준다`() = withEngine { engine ->
        listOf("a", "b", "ba", "c", "d").forEach { engine.put(b(it), b("v-$it")) }
        engine.put(byteArrayOf(0x01), b("low"))
        engine.put(byteArrayOf(0xFF.toByte()), b("high"))

        assertEquals(listOf("b", "ba", "c"), engine.scanKeys(b("b"), b("d")))
        assertEquals(listOf(listOf<Byte>(0x01)), engine.scanRawKeys(ByteArray(0), b("a")))
        assertEquals(listOf(b("d").toList(), listOf(0xFF.toByte())), engine.scanRawKeys(b("d"), null))
        assertEquals(b("v-ba").toList(), engine.scan(b("ba"), b("bb")).use { it.next().value.toList() })
    }

    @Test
    fun `scan은 삭제된 키를 건너뛴다`() = withEngine { engine ->
        listOf("a", "b", "c").forEach { engine.put(b(it), b(it)) }
        engine.delete(b("b"))

        assertEquals(listOf("a", "c"), engine.scanKeys(ByteArray(0), null))
    }

    @Test
    fun `writeBatch는 순서대로 적용되고 같은 키는 마지막 op가 이긴다`() = withEngine { engine ->
        engine.put(b("gone"), b("x"))

        engine.writeBatch(
            listOf(
                WriteOp.Put(b("k"), b("first")),
                WriteOp.Put(b("other"), b("o")),
                WriteOp.Put(b("k"), b("last")),
                WriteOp.Delete(b("gone")),
                WriteOp.Put(b("put-then-delete"), b("x")),
                WriteOp.Delete(b("put-then-delete")),
            ),
        )

        assertContentEquals(b("last"), engine.get(b("k")))
        assertContentEquals(b("o"), engine.get(b("other")))
        assertNull(engine.get(b("gone")))
        assertNull(engine.get(b("put-then-delete")))
    }

    @Test
    fun `빈 writeBatch는 아무 일도 하지 않는다`() = withEngine { engine ->
        engine.writeBatch(emptyList())

        assertEquals(emptyList(), engine.scanKeys(ByteArray(0), null))
    }

    @Test
    fun `닫았다 다시 열어도 마지막 상태가 그대로다`() {
        openEngine(dir).use { engine ->
            engine.put(b("kept"), b("v1"))
            engine.put(b("overwritten"), b("old"))
            engine.put(b("overwritten"), b("new"))
            engine.put(b("deleted"), b("x"))
            engine.delete(b("deleted"))
            engine.writeBatch(listOf(WriteOp.Put(b("batched"), b("b")), WriteOp.Delete(b("kept"))))
            engine.flush()
        }

        openEngine(dir).use { engine ->
            assertEquals(listOf("batched", "overwritten"), engine.scanKeys(ByteArray(0), null))
            assertContentEquals(b("new"), engine.get(b("overwritten")))
            assertContentEquals(b("b"), engine.get(b("batched")))
        }
    }

    @Test
    fun `사용자가 쓴 바이트를 센다`() = withEngine { engine ->
        engine.put(b("key"), b("value"))
        engine.writeBatch(listOf(WriteOp.Put(b("k2"), b("v2")), WriteOp.Delete(b("key"))))

        val stats = engine.stats()
        assertEquals((3 + 5 + 2 + 2 + 3).toLong(), stats.userBytesWritten)
        assertTrue(stats.diskBytesWritten >= stats.userBytesWritten)
    }

    private fun withEngine(block: (KvEngine) -> Unit) = openEngine(dir).use(block)

    private fun KvEngine.scanKeys(from: ByteArray, to: ByteArray?): List<String> =
        scan(from, to).use { iterator -> iterator.asSequence().map { String(it.key) }.toList() }

    private fun KvEngine.scanRawKeys(from: ByteArray, to: ByteArray?): List<List<Byte>> =
        scan(from, to).use { iterator -> iterator.asSequence().map { it.key.toList() }.toList() }

    private fun b(s: String) = s.toByteArray()
}
