package dev.mydb.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyntaxTest {
    @Test
    fun `AND가 OR보다, 비교가 AND보다, 곱셈이 덧셈보다 먼저 묶인다`() {
        assertEquals("a = 1 OR b = 2 AND c = 3", Parser.parseExpr("a = 1 OR b = 2 AND c = 3").toString())
        val e = Parser.parseExpr("a = 1 OR b = 2 AND c = 3") as Expr.Binary
        assertEquals(Expr.Op.OR, e.op)
        assertEquals(Expr.Op.AND, (e.right as Expr.Binary).op)

        val sum = Parser.parseExpr("1 + 2 * 3") as Expr.Binary
        assertEquals(Expr.Op.ADD, sum.op)
        assertEquals(Expr.Op.MUL, (sum.right as Expr.Binary).op)
    }

    @Test
    fun `NOT, IS NULL, 괄호, 단항 마이너스, 문자열 이스케이프`() {
        assertEquals(Expr.Not(Expr.IsNull(Expr.Column(null, "x"), negated = true)), Parser.parseExpr("NOT x IS NOT NULL"))
        assertEquals(Expr.Literal(Value.TextV("it's")), Parser.parseExpr("'it''s'"))
        assertEquals(Expr.Neg(Expr.Literal(Value.IntV(5))), Parser.parseExpr("-5"))
        assertEquals(Expr.Op.MUL, (Parser.parseExpr("(1 + 2) * 3") as Expr.Binary).op)
    }

    @Test
    fun `SELECT 전체 문법을 읽는다`() {
        val s = Parser.parse("select u.name, o.amount as amt from users u join orders o on u.id = o.user_id where o.amount > 10 order by o.amount desc limit 5;")
            as Statement.Select
        assertEquals(listOf("u.name", "amt"), s.items!!.map { it.label })
        assertEquals(TableRef("users", "u"), s.from)
        assertEquals(TableRef("orders", "o"), s.join)
        assertTrue(s.descending)
        assertEquals(5, s.limit)
    }

    @Test
    fun `DDL과 DML을 읽는다`() {
        val create = Parser.parse("CREATE TABLE t (id INT PRIMARY KEY, name TEXT NOT NULL, ok BOOL)") as Statement.CreateTable
        assertEquals(listOf(ColumnDef("id", SqlType.INT, true, true), ColumnDef("name", SqlType.TEXT, true, false), ColumnDef("ok", SqlType.BOOL, false, false)), create.columns)
        assertEquals(Statement.CreateIndex("idx", "t", "name"), Parser.parse("CREATE INDEX idx ON t (name)"))
        assertEquals(2, (Parser.parse("INSERT INTO t (id, name) VALUES (1, 'a'), (2, 'b')") as Statement.Insert).rows.size)
        assertEquals(1, (Parser.parse("UPDATE t SET name = 'z' WHERE id = 1") as Statement.Update).assignments.size)
        assertTrue(Parser.parse("EXPLAIN SELECT * FROM t") is Statement.Explain)
    }

    @Test
    fun `구문 오류는 위치와 기대한 것을 알려 준다`() {
        val e = assertFailsWith<SqlException> { Parser.parse("SELECT * FORM t") }
        assertTrue("위치 9" in e.message!! && "FROM" in e.message!!, e.message)
        assertFailsWith<SqlException> { Parser.parse("SELECT * FROM t WHERE") }
        assertFailsWith<SqlException> { Parser.parse("SELECT 'abc FROM t") }
        assertFailsWith<SqlException> { Parser.parse("CREATE TABLE t (id FLOAT)") }
    }
}

class CodecTest {
    @Test
    fun `행 인코딩은 NULL을 포함해 왕복한다`() {
        val types = listOf(SqlType.INT, SqlType.TEXT, SqlType.BOOL, SqlType.INT)
        val row = listOf(Value.IntV(-5), Value.TextV("가나"), Value.Null, Value.IntV(Long.MAX_VALUE))
        assertEquals(row, RowCodec.decode(types, RowCodec.encode(types, row)))
    }

    @Test
    fun `키 인코딩은 값 순서를 보존하고 NULL이 가장 앞이다`() {
        val ints = listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE).map { Value.IntV(it) }
        val texts = listOf("", "a", "a\u0000", "ab", "b").map { Value.TextV(it) }
        for (values in listOf(listOf<Value>(Value.Null) + ints, listOf<Value>(Value.Null) + texts)) {
            val encoded = values.map { KeyCodec.encode(it) }
            assertEquals(encoded, encoded.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) })
        }
    }
}
