package dev.mydb.sql

import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** SQL 계층은 KvEngine에만 의존하므로 세 엔진 위에서 똑같이 동작해야 한다. */
abstract class SqlContractTest {
    @TempDir
    lateinit var dir: Path

    abstract fun openEngine(dir: Path): KvEngine

    /** Unit만 받는다. 테스트 메서드가 값을 돌려주면 JUnit이 조용히 건너뛴다 (M5에서 겪음). */
    private fun withDb(block: (Database) -> Unit) = openEngine(dir).use { block(Database(it)) }

    private fun i(v: Long) = Value.IntV(v)

    private fun t(v: String) = Value.TextV(v)

    private fun Database.run(vararg sql: String) = sql.forEach { execute(it) }

    private fun Database.plan(sql: String) = (execute("EXPLAIN $sql") as Result.Plan).text

    private fun Database.seedShop() = run(
        "CREATE TABLE users (id INT PRIMARY KEY, name TEXT NOT NULL, city TEXT, age INT)",
        "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, amount INT)",
        "INSERT INTO users VALUES (1, 'kim', 'Seoul', 31), (2, 'lee', 'Busan', 27), (3, 'park', NULL, 45), (4, 'choi', 'Seoul', NULL)",
        "INSERT INTO orders VALUES (10, 1, 500), (11, 1, 50), (12, 2, 300), (13, 3, 120), (14, 9, 999)",
    )

    @Test
    fun `완료 조건 - CREATE, INSERT, SELECT WHERE JOIN ORDER BY, CREATE INDEX, EXPLAIN`() = withDb { db ->
        db.seedShop()
        assertEquals(listOf(listOf(t("kim")), listOf(t("choi"))), db.query("SELECT name FROM users WHERE city = 'Seoul' ORDER BY id"))
        assertEquals(
            listOf(listOf(t("kim"), i(500)), listOf(t("lee"), i(300)), listOf(t("park"), i(120))),
            db.query("SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id WHERE o.amount > 100 ORDER BY o.amount DESC"),
        )
        db.execute("CREATE INDEX idx_city ON users (city)")
        assertTrue("IndexScan idx_city" in db.plan("SELECT * FROM users WHERE city = 'Seoul'"), db.plan("SELECT * FROM users WHERE city = 'Seoul'"))
        assertEquals(2, db.query("SELECT * FROM users WHERE city = 'Seoul'").size)
    }

    @Test
    fun `접근 경로 - pk 같음, pk 범위, 인덱스 같음, 인덱스 범위, 전체 스캔`() = withDb { db ->
        db.seedShop()
        db.execute("CREATE INDEX idx_age ON users (age)")
        assertTrue(db.plan("SELECT * FROM users WHERE id = 2").contains("PkLookup users (id = 2)"))
        assertTrue(db.plan("SELECT * FROM users WHERE id >= 2 AND id < 4").contains("PkRangeScan users (id >= 2, < 4)"))
        assertTrue(db.plan("SELECT * FROM users WHERE 30 < age").contains("IndexScan idx_age on users (age > 30)"))
        assertTrue(db.plan("SELECT * FROM users WHERE age = 27").contains("IndexScan idx_age on users (age = 27)"))
        assertTrue(db.plan("SELECT * FROM users WHERE name = 'kim'").contains("SeqScan users"))
        assertEquals(listOf(listOf(i(2)), listOf(i(3))), db.query("SELECT id FROM users WHERE id >= 2 AND id < 4"))
        assertEquals(listOf(listOf(i(1)), listOf(i(3))), db.query("SELECT id FROM users WHERE 30 < age ORDER BY id"))
    }

    @Test
    fun `NULL은 3값 논리를 따른다`() = withDb { db ->
        db.seedShop()
        assertEquals(0, db.query("SELECT * FROM users WHERE city = NULL").size)
        assertEquals(listOf(listOf(i(3))), db.query("SELECT id FROM users WHERE city IS NULL"))
        assertEquals(listOf(listOf(i(2))), db.query("SELECT id FROM users WHERE NOT (city = 'Seoul')"), "NULL 도시는 NOT(…)도 NULL이라 빠진다")
        assertEquals(listOf(listOf(i(3)), listOf(i(4))), db.query("SELECT id FROM users WHERE age > 40 OR city = 'Seoul' AND age IS NULL ORDER BY id"))
        assertEquals(listOf(listOf(Value.Null)), db.query("SELECT age + 1 FROM users WHERE id = 4"))
    }

    @Test
    fun `타입·제약 위반은 오류이고 문장 전체가 무효다`() = withDb { db ->
        db.seedShop()
        assertFailsWith<SqlException> { db.execute("SELECT * FROM users WHERE name = 1") }
        assertFailsWith<SqlException> { db.execute("INSERT INTO users VALUES (5, 7, 'x', 1)") }
        assertFailsWith<SqlException> { db.execute("INSERT INTO users (id, city) VALUES (6, 'x')") }
        assertFailsWith<SqlException> { db.execute("INSERT INTO users VALUES (7, 'a', NULL, 1), (8, 'b', NULL, 2), (1, 'dup', NULL, 3)") }
        assertEquals(4, db.query("SELECT * FROM users").size, "중복 하나 때문에 7, 8도 들어가지 않아야 한다")
        assertFailsWith<SqlException> { db.execute("UPDATE users SET id = 100 WHERE id = 1") }
        assertFailsWith<SqlException> { db.execute("SELECT id FROM users u JOIN orders o ON u.id = o.user_id") }
        assertFailsWith<SqlException> { db.execute("SELECT nope FROM users") }
        assertFailsWith<SqlException> { db.execute("CREATE TABLE x (a INT, b INT)") }
    }

    @Test
    fun `JOIN은 인덱스가 있으면 IndexLookupJoin, 없으면 NestedLoopJoin이고 결과는 같다`() = withDb { db ->
        db.seedShop()
        val sql = "SELECT o.id, u.name FROM orders o JOIN users u ON o.user_id = u.id WHERE u.city = 'Seoul' ORDER BY o.id"
        val expected = listOf(listOf(i(10), t("kim")), listOf(i(11), t("kim")))
        assertEquals(expected, db.query(sql))
        assertTrue("IndexLookupJoin o.user_id → u.id" in db.plan(sql), db.plan(sql))

        // 안쪽(orders.user_id)에 인덱스가 없고 바깥(users.id)이 기본 키면 둘을 바꿔 users를 찾는다
        val swapped = "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.amount"
        assertTrue("IndexLookupJoin o.user_id → u.id" in db.plan(swapped), db.plan(swapped))
        val noIndex = "SELECT u.name, o.amount FROM users u JOIN orders o ON u.age = o.amount"
        assertTrue("NestedLoopJoin" in db.plan(noIndex), db.plan(noIndex))
        assertEquals(4, db.query(swapped).size)

        db.execute("CREATE INDEX idx_user ON orders (user_id)")
        assertEquals(db.query(swapped), db.query(swapped.replace("ORDER BY o.amount", "ORDER BY o.amount")))
        assertTrue("IndexLookupJoin u.id → o.user_id" in db.plan(swapped), db.plan(swapped))
    }

    @Test
    fun `Halloween 문제 - 인덱스 열을 올리는 UPDATE는 각 행을 한 번만 바꾼다`() = withDb { db ->
        db.run("CREATE TABLE t (id INT PRIMARY KEY, score INT)", "CREATE INDEX idx_score ON t (score)")
        db.execute("INSERT INTO t VALUES " + (1..50).joinToString(", ") { "($it, $it)" })
        assertTrue("IndexScan idx_score" in db.plan("SELECT * FROM t WHERE score >= 1"))

        assertEquals(Result.Affected(50), db.execute("UPDATE t SET score = score + 100 WHERE score >= 1"))

        assertEquals((1..50).map { listOf(i(it.toLong()), i(it + 100L)) }, db.query("SELECT id, score FROM t ORDER BY id"))
        assertEquals(50, db.query("SELECT * FROM t WHERE score > 100").size)
        assertEquals(0, db.query("SELECT * FROM t WHERE score <= 100").size, "옛 인덱스 항목이 남으면 여기서 보인다")
    }

    @Test
    fun `DELETE는 행과 인덱스 항목을 함께 지운다`() = withDb { db ->
        db.seedShop()
        db.execute("CREATE INDEX idx_city ON users (city)")
        assertEquals(Result.Affected(2), db.execute("DELETE FROM users WHERE city = 'Seoul'"))
        assertEquals(0, db.query("SELECT * FROM users WHERE city = 'Seoul'").size)
        assertEquals(listOf(listOf(i(2)), listOf(i(3))), db.query("SELECT id FROM users ORDER BY id"))
    }

    @Test
    fun `ORDER BY는 NULL을 가장 작게 두고 LIMIT을 적용한다`() = withDb { db ->
        db.seedShop()
        assertEquals(listOf(listOf(i(4)), listOf(i(2)), listOf(i(1))), db.query("SELECT id FROM users ORDER BY age LIMIT 3"))
        assertEquals(listOf(listOf(i(3)), listOf(i(1))), db.query("SELECT id FROM users ORDER BY age DESC LIMIT 2"))
    }

    @Test
    fun `재오픈 후 스키마·데이터·인덱스가 남는다`() {
        withDb { db ->
            db.seedShop()
            db.execute("CREATE INDEX idx_city ON users (city)")
        }
        withDb { db ->
            assertTrue("IndexScan idx_city" in db.plan("SELECT * FROM users WHERE city = 'Busan'"))
            assertEquals(listOf(listOf(t("lee"))), db.query("SELECT name FROM users WHERE city = 'Busan'"))
            assertFailsWith<SqlException> { db.execute("CREATE TABLE users (id INT PRIMARY KEY)") }
        }
    }

    /**
     * 차분 무작위 테스트: 같은 변경을 인덱스 있는 테이블(a1, b1)과 없는 테이블(a2, b2), 그리고 Kotlin 모델에 적용하고
     * 무작위 질의 결과가 셋 다 같은지 본다.
     */
    @Test
    fun `무작위 변경과 질의에서 인덱스 유무와 모델의 결과가 같다`() = withDb { db ->
        val random = Random(11)
        for (suffix in listOf("1", "2")) {
            db.run("CREATE TABLE a$suffix (id INT PRIMARY KEY, x INT, s TEXT)", "CREATE TABLE b$suffix (id INT PRIMARY KEY, a_id INT, y INT)")
        }
        db.run("CREATE INDEX ix1 ON a1 (x)", "CREATE INDEX is1 ON a1 (s)", "CREATE INDEX ia1 ON b1 (a_id)")
        data class A(val id: Int, var x: Int?, var s: String?)
        data class B(val id: Int, var aId: Int?, var y: Int)
        val modelA = sortedMapOf<Int, A>()
        val modelB = sortedMapOf<Int, B>()
        fun lit(v: Any?) = when (v) { null -> "NULL"; is String -> "'$v'"; else -> v.toString() }
        fun both(sql: String) = listOf("1", "2").forEach { db.execute(sql.replace("{a}", "a$it").replace("{b}", "b$it")) }

        repeat(600) {
            when (random.nextInt(6)) {
                0, 1 -> {
                    val id = random.nextInt(80)
                    if (id !in modelA) {
                        val row = A(id, if (random.nextInt(5) == 0) null else random.nextInt(20), if (random.nextInt(5) == 0) null else "s${random.nextInt(6)}")
                        both("INSERT INTO {a} VALUES (${row.id}, ${lit(row.x)}, ${lit(row.s)})")
                        modelA[id] = row
                    }
                }
                2 -> {
                    val id = random.nextInt(120)
                    if (id !in modelB) {
                        val row = B(id, if (random.nextInt(6) == 0) null else random.nextInt(80), random.nextInt(100))
                        both("INSERT INTO {b} VALUES (${row.id}, ${lit(row.aId)}, ${row.y})")
                        modelB[id] = row
                    }
                }
                3 -> {
                    val bound = random.nextInt(20)
                    val delta = random.nextInt(-3, 4)
                    both("UPDATE {a} SET x = x + $delta WHERE x >= $bound")
                    modelA.values.filter { it.x != null && it.x!! >= bound }.forEach { it.x = it.x!! + delta }
                }
                4 -> {
                    val s = "s${random.nextInt(6)}"
                    both("DELETE FROM {a} WHERE s = '$s'")
                    modelA.values.removeIf { it.s == s }
                }
                else -> {
                    val y = random.nextInt(100)
                    both("UPDATE {b} SET a_id = NULL WHERE y < $y AND a_id > 70")
                    modelB.values.filter { it.y < y && it.aId != null && it.aId!! > 70 }.forEach { it.aId = null }
                }
            }
        }

        repeat(150) {
            val lo = random.nextInt(-5, 25)
            val hi = lo + random.nextInt(0, 10)
            val s = "s${random.nextInt(6)}"
            val cases = listOf(
                "SELECT id FROM {a} WHERE x >= $lo AND x < $hi ORDER BY id" to
                    modelA.values.filter { it.x != null && it.x!! >= lo && it.x!! < hi }.map { listOf(i(it.id.toLong())) },
                "SELECT id FROM {a} WHERE s = '$s' OR x = $lo ORDER BY id" to
                    modelA.values.filter { it.s == s || it.x == lo }.map { listOf(i(it.id.toLong())) },
                "SELECT {a}.id, {b}.id FROM {a} JOIN {b} ON {a}.id = {b}.a_id WHERE {a}.x > $lo ORDER BY {b}.id" to
                    modelB.values.mapNotNull { bRow -> bRow.aId?.let { modelA[it] }?.takeIf { it.x != null && it.x!! > lo }?.let { listOf(i(it.id.toLong()), i(bRow.id.toLong())) } },
                "SELECT id FROM {a} WHERE x IS NULL AND s IS NOT NULL ORDER BY id LIMIT 3" to
                    modelA.values.filter { it.x == null && it.s != null }.take(3).map { listOf(i(it.id.toLong())) },
            )
            for ((sql, expected) in cases) {
                val indexed = db.query(sql.replace("{a}", "a1").replace("{b}", "b1"))
                val plain = db.query(sql.replace("{a}", "a2").replace("{b}", "b2"))
                assertEquals(expected, indexed, "인덱스 있음: $sql")
                assertEquals(expected, plain, "인덱스 없음: $sql")
            }
        }
    }
}

class LogSqlTest : SqlContractTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}

class LsmSqlTest : SqlContractTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class BTreeSqlTest : SqlContractTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 32, syncPolicy = SyncPolicy.MANUAL))
}
