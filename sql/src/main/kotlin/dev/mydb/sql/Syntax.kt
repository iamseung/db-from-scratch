package dev.mydb.sql

// ---------- AST ----------

sealed interface Expr {
    data class Literal(val value: Value) : Expr {
        override fun toString() = value.toString()
    }

    data class Column(val table: String?, val name: String) : Expr {
        override fun toString() = if (table == null) name else "$table.$name"
    }

    data class Binary(val op: Op, val left: Expr, val right: Expr) : Expr {
        override fun toString() = "$left ${op.symbol} $right"
    }

    data class Not(val operand: Expr) : Expr {
        override fun toString() = "NOT $operand"
    }

    data class Neg(val operand: Expr) : Expr {
        override fun toString() = "-$operand"
    }

    data class IsNull(val operand: Expr, val negated: Boolean) : Expr {
        override fun toString() = "$operand IS ${if (negated) "NOT " else ""}NULL"
    }

    enum class Op(val symbol: String) {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="), AND("AND"), OR("OR"), ADD("+"), SUB("-"), MUL("*");

        val isComparison get() = this in setOf(EQ, NE, LT, LE, GT, GE)

        /** a op b ⇔ b flipped() a */
        fun flipped(): Op = when (this) {
            LT -> GT
            LE -> GE
            GT -> LT
            GE -> LE
            else -> this
        }
    }
}

data class ColumnDef(val name: String, val type: SqlType, val notNull: Boolean, val primaryKey: Boolean)

data class TableRef(val name: String, val alias: String) {
    override fun toString() = if (alias == name) name else "$name $alias"
}

data class SelectItem(val expr: Expr, val label: String)

sealed interface Statement {
    data class CreateTable(val name: String, val columns: List<ColumnDef>) : Statement

    data class CreateIndex(val name: String, val table: String, val column: String) : Statement

    data class Insert(val table: String, val columns: List<String>?, val rows: List<List<Expr>>) : Statement

    data class Select(
        /** null이면 SELECT * */
        val items: List<SelectItem>?,
        val from: TableRef,
        val join: TableRef?,
        val on: Expr?,
        val where: Expr?,
        val orderBy: Expr?,
        val descending: Boolean,
        val limit: Int?,
    ) : Statement

    data class Update(val table: String, val assignments: List<Pair<String, Expr>>, val where: Expr?) : Statement

    data class Delete(val table: String, val where: Expr?) : Statement

    data class Explain(val select: Select) : Statement

    /** NULL 허용 열만 추가한다 (기존 행은 NULL). */
    data class AlterAddColumn(val table: String, val column: ColumnDef) : Statement
}

// ---------- 렉서 ----------

enum class TokenType { IDENT, KEYWORD, INT, STRING, SYMBOL, EOF }

data class Token(val type: TokenType, val text: String, val position: Int)

object Lexer {
    val KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "TRUE", "FALSE", "INSERT", "INTO", "VALUES",
        "CREATE", "TABLE", "INDEX", "ON", "PRIMARY", "KEY", "INT", "INTEGER", "BIGINT", "TEXT", "BOOL", "BOOLEAN",
        "UPDATE", "SET", "DELETE", "JOIN", "INNER", "ORDER", "BY", "ASC", "DESC", "LIMIT", "EXPLAIN", "AS",
        "ALTER", "ADD", "COLUMN",
    )

    fun tokenize(sql: String): List<Token> {
        val tokens = ArrayList<Token>()
        var pos = 0
        while (pos < sql.length) {
            val c = sql[pos]
            when {
                c.isWhitespace() -> pos++
                c == '-' && sql.startsWith("--", pos) -> while (pos < sql.length && sql[pos] != '\n') pos++
                c.isLetter() || c == '_' -> {
                    val start = pos
                    while (pos < sql.length && (sql[pos].isLetterOrDigit() || sql[pos] == '_')) pos++
                    val word = sql.substring(start, pos)
                    val upper = word.uppercase()
                    tokens += if (upper in KEYWORDS) Token(TokenType.KEYWORD, upper, start) else Token(TokenType.IDENT, word.lowercase(), start)
                }
                c.isDigit() -> {
                    val start = pos
                    while (pos < sql.length && sql[pos].isDigit()) pos++
                    tokens += Token(TokenType.INT, sql.substring(start, pos), start)
                }
                c == '\'' -> {
                    val start = pos++
                    val text = StringBuilder()
                    while (true) {
                        if (pos >= sql.length) throw SqlException("문자열이 닫히지 않았습니다 (위치 $start)")
                        if (sql[pos] == '\'') {
                            if (sql.startsWith("''", pos)) { text.append('\''); pos += 2; continue }
                            pos++
                            break
                        }
                        text.append(sql[pos++])
                    }
                    tokens += Token(TokenType.STRING, text.toString(), start)
                }
                else -> {
                    val two = sql.substring(pos, minOf(pos + 2, sql.length))
                    val symbol = when {
                        two in setOf("<=", ">=", "!=", "<>") -> two
                        c in "=<>(),*;.+-" -> c.toString()
                        else -> throw SqlException("알 수 없는 문자 '$c' (위치 $pos)")
                    }
                    tokens += Token(TokenType.SYMBOL, symbol, pos)
                    pos += symbol.length
                }
            }
        }
        tokens += Token(TokenType.EOF, "", sql.length)
        return tokens
    }
}

// ---------- 파서 (재귀 하강) ----------

class Parser private constructor(private val tokens: List<Token>) {
    private var index = 0

    companion object {
        fun parse(sql: String): Statement {
            val parser = Parser(Lexer.tokenize(sql))
            val statement = parser.statement()
            parser.accept(TokenType.SYMBOL, ";")
            parser.expect(TokenType.EOF)
            return statement
        }

        fun parseExpr(text: String): Expr {
            val parser = Parser(Lexer.tokenize(text))
            val expr = parser.expr()
            parser.expect(TokenType.EOF)
            return expr
        }
    }

    private fun statement(): Statement = when {
        acceptKeyword("CREATE") -> if (acceptKeyword("TABLE")) createTable() else { expectKeyword("INDEX"); createIndex() }
        acceptKeyword("INSERT") -> insert()
        acceptKeyword("SELECT") -> select()
        acceptKeyword("UPDATE") -> update()
        acceptKeyword("DELETE") -> delete()
        acceptKeyword("EXPLAIN") -> { expectKeyword("SELECT"); Statement.Explain(select()) }
        acceptKeyword("ALTER") -> alterTable()
        else -> fail("문장이 필요합니다 (SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, EXPLAIN)")
    }

    private fun createTable(): Statement.CreateTable {
        val name = ident()
        expectSymbol("(")
        val columns = ArrayList<ColumnDef>()
        do {
            val column = ident()
            val type = type()
            var notNull = false
            var primaryKey = false
            while (true) {
                when {
                    acceptKeyword("NOT") -> { expectKeyword("NULL"); notNull = true }
                    acceptKeyword("PRIMARY") -> { expectKeyword("KEY"); primaryKey = true }
                    else -> break
                }
            }
            columns += ColumnDef(column, type, notNull || primaryKey, primaryKey)
        } while (accept(TokenType.SYMBOL, ","))
        expectSymbol(")")
        return Statement.CreateTable(name, columns)
    }

    private fun type(): SqlType {
        if (peek().type != TokenType.KEYWORD) fail("타입이 필요합니다 (INT, TEXT, BOOL)")
        return when (expect(TokenType.KEYWORD).text) {
            "INT", "INTEGER", "BIGINT" -> SqlType.INT
            "TEXT" -> SqlType.TEXT
            "BOOL", "BOOLEAN" -> SqlType.BOOL
            else -> fail("타입이 필요합니다 (INT, TEXT, BOOL)", back = 1)
        }
    }

    private fun alterTable(): Statement.AlterAddColumn {
        expectKeyword("TABLE")
        val table = ident()
        expectKeyword("ADD")
        acceptKeyword("COLUMN")
        val column = ident()
        return Statement.AlterAddColumn(table, ColumnDef(column, type(), notNull = false, primaryKey = false))
    }

    private fun createIndex(): Statement.CreateIndex {
        val name = ident()
        expectKeyword("ON")
        val table = ident()
        expectSymbol("(")
        val column = ident()
        expectSymbol(")")
        return Statement.CreateIndex(name, table, column)
    }

    private fun insert(): Statement.Insert {
        expectKeyword("INTO")
        val table = ident()
        val columns = if (accept(TokenType.SYMBOL, "(")) {
            commaList { ident() }.also { expectSymbol(")") }
        } else {
            null
        }
        expectKeyword("VALUES")
        val rows = commaList {
            expectSymbol("(")
            commaList { expr() }.also { expectSymbol(")") }
        }
        return Statement.Insert(table, columns, rows)
    }

    private fun select(): Statement.Select {
        val items = if (accept(TokenType.SYMBOL, "*")) {
            null
        } else {
            commaList {
                val start = index
                val e = expr()
                val label = if (acceptKeyword("AS")) ident() else tokens.subList(start, index).joinToString("") { t -> if (t.type == TokenType.STRING) "'${t.text}'" else t.text }
                SelectItem(e, label)
            }
        }
        expectKeyword("FROM")
        val from = tableRef()
        var join: TableRef? = null
        var on: Expr? = null
        if (acceptKeyword("INNER") || peekKeyword("JOIN")) {
            expectKeyword("JOIN")
            join = tableRef()
            expectKeyword("ON")
            on = expr()
        }
        val where = if (acceptKeyword("WHERE")) expr() else null
        var orderBy: Expr? = null
        var descending = false
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY")
            orderBy = expr()
            descending = when {
                acceptKeyword("DESC") -> true
                else -> { acceptKeyword("ASC"); false }
            }
        }
        val limit = if (acceptKeyword("LIMIT")) expect(TokenType.INT).text.toInt() else null
        return Statement.Select(items, from, join, on, where, orderBy, descending, limit)
    }

    private fun tableRef(): TableRef {
        val name = ident()
        acceptKeyword("AS")
        val alias = if (peek().type == TokenType.IDENT) ident() else name
        return TableRef(name, alias)
    }

    private fun update(): Statement.Update {
        val table = ident()
        expectKeyword("SET")
        val assignments = commaList {
            val column = ident()
            expectSymbol("=")
            column to expr()
        }
        val where = if (acceptKeyword("WHERE")) expr() else null
        return Statement.Update(table, assignments, where)
    }

    private fun delete(): Statement.Delete {
        expectKeyword("FROM")
        val table = ident()
        val where = if (acceptKeyword("WHERE")) expr() else null
        return Statement.Delete(table, where)
    }

    // 우선순위가 낮은 것부터: OR < AND < NOT < 비교 < +,- < * < 단항 -
    private fun expr(): Expr = or()

    private fun or(): Expr {
        var left = and()
        while (acceptKeyword("OR")) left = Expr.Binary(Expr.Op.OR, left, and())
        return left
    }

    private fun and(): Expr {
        var left = not()
        while (acceptKeyword("AND")) left = Expr.Binary(Expr.Op.AND, left, not())
        return left
    }

    private fun not(): Expr = if (acceptKeyword("NOT")) Expr.Not(not()) else comparison()

    private fun comparison(): Expr {
        val left = sum()
        if (acceptKeyword("IS")) {
            val negated = acceptKeyword("NOT")
            expectKeyword("NULL")
            return Expr.IsNull(left, negated)
        }
        val op = when (peek().takeIf { it.type == TokenType.SYMBOL }?.text) {
            "=" -> Expr.Op.EQ
            "!=", "<>" -> Expr.Op.NE
            "<" -> Expr.Op.LT
            "<=" -> Expr.Op.LE
            ">" -> Expr.Op.GT
            ">=" -> Expr.Op.GE
            else -> return left
        }
        index++
        return Expr.Binary(op, left, sum())
    }

    private fun sum(): Expr {
        var left = term()
        while (true) {
            left = when {
                accept(TokenType.SYMBOL, "+") -> Expr.Binary(Expr.Op.ADD, left, term())
                accept(TokenType.SYMBOL, "-") -> Expr.Binary(Expr.Op.SUB, left, term())
                else -> return left
            }
        }
    }

    private fun term(): Expr {
        var left = unary()
        while (accept(TokenType.SYMBOL, "*")) left = Expr.Binary(Expr.Op.MUL, left, unary())
        return left
    }

    private fun unary(): Expr = if (accept(TokenType.SYMBOL, "-")) Expr.Neg(unary()) else primary()

    private fun primary(): Expr {
        val token = peek()
        return when {
            token.type == TokenType.INT -> { index++; Expr.Literal(Value.IntV(token.text.toLongOrNull() ?: fail("정수가 너무 큽니다: ${token.text}"))) }
            token.type == TokenType.STRING -> { index++; Expr.Literal(Value.TextV(token.text)) }
            acceptKeyword("TRUE") -> Expr.Literal(Value.BoolV(true))
            acceptKeyword("FALSE") -> Expr.Literal(Value.BoolV(false))
            acceptKeyword("NULL") -> Expr.Literal(Value.Null)
            accept(TokenType.SYMBOL, "(") -> expr().also { expectSymbol(")") }
            token.type == TokenType.IDENT -> {
                val first = ident()
                if (accept(TokenType.SYMBOL, ".")) Expr.Column(first, ident()) else Expr.Column(null, first)
            }
            else -> fail("식이 필요합니다")
        }
    }

    private fun <T> commaList(item: () -> T): List<T> {
        val items = arrayListOf(item())
        while (accept(TokenType.SYMBOL, ",")) items += item()
        return items
    }

    private fun ident(): String = expect(TokenType.IDENT).text

    private fun peek(): Token = tokens[index]

    private fun peekKeyword(word: String) = peek().type == TokenType.KEYWORD && peek().text == word

    private fun accept(type: TokenType, text: String? = null): Boolean {
        val token = peek()
        if (token.type != type || (text != null && token.text != text)) return false
        index++
        return true
    }

    private fun acceptKeyword(word: String) = accept(TokenType.KEYWORD, word)

    private fun expect(type: TokenType): Token {
        val token = peek()
        if (token.type != type) fail("${type.name.lowercase()}이(가) 필요합니다")
        index++
        return token
    }

    private fun expectKeyword(word: String) {
        if (!acceptKeyword(word)) fail("$word 이(가) 필요합니다")
    }

    private fun expectSymbol(symbol: String) {
        if (!accept(TokenType.SYMBOL, symbol)) fail("'$symbol' 이(가) 필요합니다")
    }

    private fun fail(message: String, back: Int = 0): Nothing {
        val token = tokens[index - back]
        val found = if (token.type == TokenType.EOF) "문장 끝" else "'${token.text}'"
        throw SqlException("구문 오류 (위치 ${token.position}): $message, 실제: $found")
    }
}
