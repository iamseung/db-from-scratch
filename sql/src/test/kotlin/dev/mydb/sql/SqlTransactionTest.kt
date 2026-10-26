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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** BEGIN … COMMIT / ROLLBACK. 세션마다 트랜잭션 하나, 스냅샷 격리. */
abstract class SqlTransactionTest {
    @TempDir
    lateinit var dir: Path

    abstract fun openEngine(dir: Path): KvEngine

    private fun withDb(block: (Database) -> Unit) = openEngine(dir).use { block(Database(it, transactional = true)) }

    private fun i(v: Long) = Value.IntV(v)

    private fun Session.query(sql: String) = (execute(sql) as Result.Rows).rows

    private fun Session.run(vararg sql: String) = sql.forEach { execute(it) }

    private fun Database.seedAccounts() {
        execute("CREATE TABLE accounts (id INT PRIMARY KEY, owner TEXT NOT NULL, balance INT NOT NULL)")
        execute("INSERT INTO accounts VALUES (1, 'kim', 100), (2, 'lee', 100)")
    }

    @Test
    fun `커밋 전 변경은 다른 세션에 보이지 않고 커밋 후에 보인다`() = withDb { db ->
        db.seedAccounts()
        val a = db.session()
        val b = db.session()
        a.run("BEGIN", "UPDATE accounts SET balance = balance - 30 WHERE id = 1", "INSERT INTO accounts VALUES (3, 'park', 0)")

        assertEquals(listOf(listOf(i(70))), a.query("SELECT balance FROM accounts WHERE id = 1"), "자기 변경은 보인다")
        assertEquals(listOf(listOf(i(100))), b.query("SELECT balance FROM accounts WHERE id = 1"))
        assertEquals(2, b.query("SELECT * FROM accounts").size)

        a.execute("COMMIT")
        assertEquals(listOf(listOf(i(70))), b.query("SELECT balance FROM accounts WHERE id = 1"))
        assertEquals(3, b.query("SELECT * FROM accounts").size)
    }

    @Test
    fun `ROLLBACK은 DML과 DDL을 모두 되돌린다`() = withDb { db ->
        db.seedAccounts()
        val s = db.session()
        s.run(
            "BEGIN",
            "DELETE FROM accounts WHERE id = 2",
            "CREATE TABLE audit (id INT PRIMARY KEY, note TEXT)",
            "CREATE INDEX idx_owner ON accounts (owner)",
            "INSERT INTO audit VALUES (1, 'x')",
        )
        assertEquals(1, s.query("SELECT * FROM audit").size)
        s.execute("ROLLBACK")

        assertEquals(2, db.query("SELECT * FROM accounts").size)
        assertFailsWith<SqlException> { db.execute("SELECT * FROM audit") }
        assertTrue("SeqScan" in (db.execute("EXPLAIN SELECT * FROM accounts WHERE owner = 'kim'") as Result.Plan).text, "인덱스 생성도 되돌려진다")
    }

    @Test
    fun `트랜잭션은 시작 시점의 스냅샷을 끝까지 본다`() = withDb { db ->
        db.seedAccounts()
        val reader = db.session()
        reader.execute("BEGIN")
        val before = reader.query("SELECT id, balance FROM accounts ORDER BY id")
        db.execute("UPDATE accounts SET balance = 0")
        db.execute("INSERT INTO accounts VALUES (9, 'new', 1)")

        assertEquals(before, reader.query("SELECT id, balance FROM accounts ORDER BY id"))
        reader.execute("COMMIT")
        assertEquals(3, reader.query("SELECT * FROM accounts").size, "트랜잭션이 끝나면 최신을 본다")
    }

    @Test
    fun `같은 행을 바꾼 두 트랜잭션 중 나중 COMMIT은 실패하고 그 변경은 버려진다`() = withDb { db ->
        db.seedAccounts()
        val a = db.session()
        val b = db.session()
        a.run("BEGIN", "UPDATE accounts SET balance = balance + 10 WHERE id = 1")
        b.run("BEGIN", "UPDATE accounts SET balance = balance + 20 WHERE id = 1", "UPDATE accounts SET balance = 999 WHERE id = 2")
        a.execute("COMMIT")

        val e = assertFailsWith<SqlException> { b.execute("COMMIT") }
        assertTrue("직렬화 실패" in e.message!!, e.message)
        assertFalse(b.inTransaction)
        assertEquals(listOf(listOf(i(110)), listOf(i(100))), db.query("SELECT balance FROM accounts ORDER BY id"), "lost update 없음, b의 다른 변경도 버려짐")
    }

    /** M8 D5에서 본 "항목 없는 주문"을 트랜잭션으로 막는다. */
    @Test
    fun `여러 문장을 원자적으로 묶어 중간 실패를 되돌린다`() = withDb { db ->
        db.execute("CREATE TABLE orders (id INT PRIMARY KEY, total INT)")
        db.execute("CREATE TABLE items (id INT PRIMARY KEY, order_id INT NOT NULL, qty INT)")
        db.execute("INSERT INTO items VALUES (2, 99, 1)")
        val s = db.session()
        s.run("BEGIN", "INSERT INTO orders VALUES (1, 300)")
        assertFailsWith<SqlException> { s.execute("INSERT INTO items VALUES (1, 1, 2), (2, 1, 1)") }
        assertTrue(s.inTransaction, "실패한 문장만 무효이고 트랜잭션은 열려 있다")
        assertEquals(0, s.query("SELECT * FROM items WHERE order_id = 1").size, "실패한 문장의 일부(항목 1)도 남지 않는다")
        s.execute("ROLLBACK")

        assertEquals(0, db.query("SELECT * FROM orders").size, "주문도 남지 않는다")
    }

    @Test
    fun `트랜잭션 제어 오류`() = withDb { db ->
        val s = db.session()
        assertFailsWith<SqlException> { s.execute("COMMIT") }
        assertFailsWith<SqlException> { s.execute("ROLLBACK") }
        s.execute("BEGIN TRANSACTION")
        assertFailsWith<SqlException> { s.execute("BEGIN") }
        s.execute("COMMIT")
        dir.resolve("raw").let { rawDir ->
            LsmEngine.open(LsmOptions(rawDir)).use { engine -> assertFailsWith<SqlException> { Database(engine).execute("BEGIN") } }
        }
    }

    /** SQL로 하는 동시 이체: 세션 8개가 BEGIN … COMMIT으로 이체하고, 충돌이면 처음부터 다시 한다. */
    @Test
    fun `동시 이체 - 세션 8개의 SQL 트랜잭션에서 잔액 합계가 보존된다`() = withDb { db ->
        db.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT NOT NULL)")
        db.execute("INSERT INTO accounts VALUES " + (0 until 10).joinToString(", ") { "($it, 1000)" })
        val conflicts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { worker ->
            pool.submit {
                val session = db.session()
                val random = Random(worker)
                repeat(60) {
                    val from = random.nextInt(10)
                    val to = (from + 1 + random.nextInt(9)) % 10
                    val amount = random.nextInt(1, 100)
                    while (true) {
                        session.execute("BEGIN")
                        val balance = (session.query("SELECT balance FROM accounts WHERE id = $from")[0][0] as Value.IntV).v
                        if (balance >= amount) {
                            session.execute("UPDATE accounts SET balance = balance - $amount WHERE id = $from")
                            session.execute("UPDATE accounts SET balance = balance + $amount WHERE id = $to")
                        }
                        try {
                            session.execute("COMMIT")
                            break
                        } catch (e: SqlException) {
                            conflicts.incrementAndGet()
                        }
                    }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))

        val balances = db.query("SELECT balance FROM accounts").map { (it[0] as Value.IntV).v }
        assertEquals(10_000L, balances.sum())
        assertTrue(balances.all { it >= 0 })
        println("SQL 동시 이체 충돌 후 재시도: ${conflicts.get()}회")
        assertTrue(conflicts.get() > 0, "충돌이 한 번도 없었다면 이 테스트는 동시성을 검증하지 못한 것이다")
    }
}

class LsmSqlTransactionTest : SqlTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir, memtableSizeBytes = 4096, syncPolicy = SyncPolicy.MANUAL))
}

class BTreeSqlTransactionTest : SqlTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir, pageSize = 1024, bufferPoolPages = 64, syncPolicy = SyncPolicy.MANUAL))
}

class LogSqlTransactionTest : SqlTransactionTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, syncPolicy = SyncPolicy.MANUAL))
}
