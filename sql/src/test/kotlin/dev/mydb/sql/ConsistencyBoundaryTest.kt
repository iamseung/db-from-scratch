package dev.mydb.sql

import dev.mydb.document.DocumentStore
import dev.mydb.document.Json
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

/**
 * M8 D5: 일관성 경계. 관계형은 원자성의 단위가 문장이고, 주문과 항목은 두 문장이다 (M7 트랜잭션을 SQL에 연결하지 않았다).
 * 문서형은 주문과 항목이 한 문서라 함께 들어가거나 함께 안 들어간다.
 */
class ConsistencyBoundaryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `관계형은 두 번째 문장이 실패하면 주문만 남고 문서형은 아무것도 남지 않는다`() {
        LsmEngine.open(LsmOptions(dir.resolve("rel"), syncPolicy = SyncPolicy.MANUAL)).use { engine ->
            val db = Database(engine)
            db.execute("CREATE TABLE orders (id INT PRIMARY KEY, total INT)")
            db.execute("CREATE TABLE items (id INT PRIMARY KEY, order_id INT NOT NULL, qty INT)")
            db.execute("INSERT INTO items VALUES (2, 99, 1)") // 이미 있는 항목 id

            db.execute("INSERT INTO orders VALUES (1, 300)")
            assertFailsWith<SqlException> { db.execute("INSERT INTO items VALUES (1, 1, 2), (2, 1, 1)") }

            assertEquals(1, db.query("SELECT * FROM orders WHERE id = 1").size, "주문은 남았다")
            assertEquals(0, db.query("SELECT * FROM items WHERE order_id = 1").size, "항목은 없다 → 항목 없는 주문")
        }

        LsmEngine.open(LsmOptions(dir.resolve("doc"), syncPolicy = SyncPolicy.MANUAL)).use { engine ->
            val orders = DocumentStore(engine).createCollection("orders")
            orders.insert("1", Json.parseObject("""{"total":300,"items":[{"id":1,"qty":2}]}"""))

            // 같은 id로 다시 넣으면 문서 전체가 거부된다. 주문과 항목이 따로 남는 상태가 없다
            assertFailsWith<IllegalStateException> { orders.insert("1", Json.parseObject("""{"total":999,"items":[]}""")) }
            assertEquals(Json.parseObject("""{"total":300,"items":[{"id":1,"qty":2}]}"""), orders.get("1"))
            assertNull(orders.get("2"))
        }
    }
}
