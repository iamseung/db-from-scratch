package dev.mydb.kv

import java.nio.file.Path
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * 무작위 연산을 엔진과 TreeMap 모델에 똑같이 적용하고 매번 결과를 비교한다.
 * put/delete/batch/scan에 재오픈과 엔진별 유지 작업(merge, compaction)을 섞어 단위 테스트가 놓치는 조합을 찾는다.
 * 실패하면 메시지의 seed로 그대로 재현할 수 있다.
 */
@Tag("slow")
abstract class KvEngineRandomTest {
    @TempDir
    lateinit var dir: Path

    /** 의미를 검증하는 테스트라 fsync를 생략하는 설정을 권장한다. 재오픈은 OS 캐시만으로 충분하다. */
    abstract fun openEngine(dir: Path): KvEngine

    /** 엔진별 유지 작업. M1은 merge, M2는 compaction. */
    open fun maintenance(engine: KvEngine) = Unit

    @Test
    fun `무작위 연산에서 엔진과 모델이 항상 같다`() {
        repeat(5) { runSeed(System.nanoTime()) }
    }

    private fun runSeed(seed: Long) {
        val random = Random(seed)
        val dataDir = dir.resolve("seed-$seed")
        val model = TreeMap<ByteArray, ByteArray>(ByteKeys.COMPARATOR)
        var engine = openEngine(dataDir)
        try {
            repeat(3_000) { step ->
                val context = "seed=$seed step=$step"
                when (random.nextInt(100)) {
                    in 0..44 -> {
                        val key = randomKey(random)
                        val value = randomValue(random)
                        engine.put(key, value)
                        model[key] = value
                    }
                    in 45..59 -> {
                        val key = randomKey(random)
                        engine.delete(key)
                        model.remove(key)
                    }
                    in 60..69 -> {
                        val ops = List(random.nextInt(1, 6)) {
                            if (random.nextBoolean()) WriteOp.Put(randomKey(random), randomValue(random)) else WriteOp.Delete(randomKey(random))
                        }
                        engine.writeBatch(ops)
                        ops.forEach { op ->
                            when (op) {
                                is WriteOp.Put -> model[op.key] = op.value
                                is WriteOp.Delete -> model.remove(op.key)
                            }
                        }
                    }
                    in 70..84 -> {
                        val key = randomKey(random)
                        assertEquals(model[key]?.toList(), engine.get(key)?.toList(), context)
                    }
                    in 85..92 -> {
                        val a = randomKey(random)
                        val b = randomKey(random)
                        val (from, to) = if (ByteKeys.compare(a, b) <= 0) a to b else b to a
                        val expected = model.subMap(from, to).map { (k, v) -> String(k) to String(v) }
                        val actual = engine.scan(from, to).use { it.asSequence().map { e -> String(e.key) to String(e.value) }.toList() }
                        assertEquals(expected, actual, context)
                    }
                    in 93..96 -> {
                        engine.close()
                        engine = openEngine(dataDir)
                    }
                    else -> maintenance(engine)
                }
            }
            val all = engine.scan(ByteArray(0), null).use { it.asSequence().map { e -> String(e.key) to String(e.value) }.toList() }
            assertEquals(model.map { (k, v) -> String(k) to String(v) }, all, "seed=$seed 최종 상태")
        } finally {
            engine.close()
        }
    }

    /** 키 공간을 작게 잡아 덮어쓰기와 삭제가 자주 같은 키에 걸리게 한다. */
    private fun randomKey(random: Random) = "k%03d".format(random.nextInt(200)).toByteArray()

    private fun randomValue(random: Random) = "v".repeat(random.nextInt(0, 40)).plus(random.nextInt()).toByteArray()
}
