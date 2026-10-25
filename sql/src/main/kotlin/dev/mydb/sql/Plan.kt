package dev.mydb.sql

import dev.mydb.kv.KvEngine

typealias Row = List<Value>

/** FROM·JOIN에 나온 테이블들. 행은 테이블 순서대로 열을 이어 붙인 목록이다. */
class Scope(val tables: List<Pair<String, TableDef>>) {
    private val offsets = tables.runningFold(0) { acc, (_, def) -> acc + def.columns.size }

    val width: Int
        get() = offsets.last()

    fun offsetOf(tableIndex: Int) = offsets[tableIndex]

    /** 열 이름을 행 안의 위치로. 테이블을 안 썼는데 두 테이블에 같은 이름이 있으면 모호하다. */
    fun resolve(column: Expr.Column): Int {
        val candidates = tables.indices.mapNotNull { t ->
            val (alias, def) = tables[t]
            if (column.table != null && column.table != alias) return@mapNotNull null
            def.columnIndex(column.name)?.let { offsets[t] + it }
        }
        return when (candidates.size) {
            1 -> candidates[0]
            0 -> throw SqlException("없는 열입니다: $column")
            else -> throw SqlException("모호한 열입니다: $column (테이블 이름을 붙이세요)")
        }
    }

    fun tableIndexOf(column: Expr.Column): Int {
        val position = resolve(column)
        return offsets.indexOfLast { it <= position }.coerceAtMost(tables.size - 1)
    }

    fun columnNames(): List<String> =
        tables.flatMap { (alias, def) -> def.columns.map { if (tables.size > 1) "$alias.${it.name}" else it.name } }
}

object Eval {
    fun eval(expr: Expr, scope: Scope, row: Row): Value = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Column -> row[scope.resolve(expr)]
        is Expr.Neg -> when (val v = eval(expr.operand, scope, row)) {
            Value.Null -> Value.Null
            is Value.IntV -> Value.IntV(-v.v)
            else -> throw SqlException("정수가 아닌 값에 -를 쓸 수 없습니다: $v")
        }
        is Expr.Not -> when (val v = eval(expr.operand, scope, row)) {
            Value.Null -> Value.Null
            is Value.BoolV -> Value.BoolV(!v.v)
            else -> throw SqlException("NOT의 피연산자는 BOOL이어야 합니다: $v")
        }
        is Expr.IsNull -> Value.BoolV((eval(expr.operand, scope, row) == Value.Null) != expr.negated)
        is Expr.Binary -> binary(expr, scope, row)
    }

    /** WHERE·ON은 TRUE인 행만 남긴다. NULL(알 수 없음)은 FALSE처럼 걸러진다. */
    fun isTrue(expr: Expr, scope: Scope, row: Row): Boolean = eval(expr, scope, row) == Value.BoolV(true)

    private fun binary(expr: Expr.Binary, scope: Scope, row: Row): Value {
        val op = expr.op
        if (op == Expr.Op.AND || op == Expr.Op.OR) {
            // 3값 논리: AND는 FALSE가 하나라도 있으면 FALSE, OR는 TRUE가 하나라도 있으면 TRUE, 나머지 NULL이 끼면 NULL
            val left = logical(eval(expr.left, scope, row))
            val right = logical(eval(expr.right, scope, row))
            val dominant = op == Expr.Op.OR
            return when {
                left == dominant || right == dominant -> Value.BoolV(dominant)
                left == null || right == null -> Value.Null
                else -> Value.BoolV(!dominant)
            }
        }
        val left = eval(expr.left, scope, row)
        val right = eval(expr.right, scope, row)
        if (op.isComparison) {
            val cmp = compareValues(left, right) ?: return Value.Null
            return Value.BoolV(
                when (op) {
                    Expr.Op.EQ -> cmp == 0
                    Expr.Op.NE -> cmp != 0
                    Expr.Op.LT -> cmp < 0
                    Expr.Op.LE -> cmp <= 0
                    Expr.Op.GT -> cmp > 0
                    else -> cmp >= 0
                },
            )
        }
        if (left == Value.Null || right == Value.Null) return Value.Null
        if (left !is Value.IntV || right !is Value.IntV) throw SqlException("산술은 INT끼리만 가능합니다: $left ${op.symbol} $right")
        return Value.IntV(
            when (op) {
                Expr.Op.ADD -> Math.addExact(left.v, right.v)
                Expr.Op.SUB -> Math.subtractExact(left.v, right.v)
                else -> Math.multiplyExact(left.v, right.v)
            },
        )
    }

    private fun logical(v: Value): Boolean? = when (v) {
        Value.Null -> null
        is Value.BoolV -> v.v
        else -> throw SqlException("AND/OR의 피연산자는 BOOL이어야 합니다: $v")
    }

    /** 열을 참조하지 않는 식(상수)인지. 접근 경로 선택에 쓴다. */
    fun isConstant(expr: Expr): Boolean = when (expr) {
        is Expr.Literal -> true
        is Expr.Column -> false
        is Expr.Neg -> isConstant(expr.operand)
        is Expr.Not -> isConstant(expr.operand)
        is Expr.IsNull -> isConstant(expr.operand)
        is Expr.Binary -> isConstant(expr.left) && isConstant(expr.right)
    }

    fun columnsOf(expr: Expr): List<Expr.Column> = when (expr) {
        is Expr.Literal -> emptyList()
        is Expr.Column -> listOf(expr)
        is Expr.Neg -> columnsOf(expr.operand)
        is Expr.Not -> columnsOf(expr.operand)
        is Expr.IsNull -> columnsOf(expr.operand)
        is Expr.Binary -> columnsOf(expr.left) + columnsOf(expr.right)
    }

    fun conjuncts(expr: Expr?): List<Expr> = when {
        expr == null -> emptyList()
        expr is Expr.Binary && expr.op == Expr.Op.AND -> conjuncts(expr.left) + conjuncts(expr.right)
        else -> listOf(expr)
    }
}

data class KeyBound(val value: Value, val inclusive: Boolean)

/** Volcano 반복자 트리. 각 연산자는 아래에서 행을 하나씩 당겨 온다. */
sealed class PlanNode {
    abstract fun rows(): Sequence<Row>

    abstract val label: String

    open val children: List<PlanNode> = emptyList()

    fun explain(depth: Int = 0): String =
        ("  ".repeat(depth) + label + "\n") + children.joinToString("") { it.explain(depth + 1) }
}

/** 키 범위 [from, to)를 scan한다. 하한이 없으면 NULL(0x01)을 건너뛴 첫 값부터 (비교는 NULL과 참이 될 수 없다). */
internal fun keyRange(prefix: ByteArray, lower: KeyBound?, upper: KeyBound?): Pair<ByteArray, ByteArray> {
    val from = lower?.let { b -> (prefix + KeyCodec.encode(b.value)).let { if (b.inclusive) it else prefixEnd(it) } } ?: (prefix + KeyCodec.FIRST_VALUE)
    val to = upper?.let { b -> (prefix + KeyCodec.encode(b.value)).let { if (b.inclusive) prefixEnd(it) else it } } ?: prefixEnd(prefix)
    return from to to
}

private fun bounds(lower: KeyBound?, upper: KeyBound?) = listOfNotNull(
    lower?.let { (if (it.inclusive) ">= " else "> ") + it.value },
    upper?.let { (if (it.inclusive) "<= " else "< ") + it.value },
).joinToString(", ")

class SeqScan(private val engine: KvEngine, private val table: TableDef, private val alias: String) : PlanNode() {
    override val label = "SeqScan ${table.name}${if (alias != table.name) " $alias" else ""}"

    override fun rows(): Sequence<Row> = sequence {
        val entries = engine.scan(table.rowPrefix, prefixEnd(table.rowPrefix)).use { it.asSequence().toList() }
        entries.forEach { yield(RowCodec.decode(table.types, it.value)) }
    }
}

class PkLookup(private val engine: KvEngine, private val table: TableDef, alias: String, private val key: Value) : PlanNode() {
    override val label = "PkLookup ${table.name}${if (alias != table.name) " $alias" else ""} (${table.columns[table.pk].name} = $key)"

    override fun rows(): Sequence<Row> = sequence {
        engine.get(table.rowKey(key))?.let { yield(RowCodec.decode(table.types, it)) }
    }
}

class PkRangeScan(
    private val engine: KvEngine,
    private val table: TableDef,
    alias: String,
    private val lower: KeyBound?,
    private val upper: KeyBound?,
) : PlanNode() {
    override val label = "PkRangeScan ${table.name}${if (alias != table.name) " $alias" else ""} (${table.columns[table.pk].name} ${bounds(lower, upper)})"

    override fun rows(): Sequence<Row> = sequence {
        val (from, to) = keyRange(table.rowPrefix, lower, upper)
        if (java.util.Arrays.compareUnsigned(from, to) >= 0) return@sequence
        engine.scan(from, to).use { it.asSequence().toList() }.forEach { yield(RowCodec.decode(table.types, it.value)) }
    }
}

class IndexScan(
    private val engine: KvEngine,
    private val table: TableDef,
    alias: String,
    private val index: IndexDef,
    private val lower: KeyBound?,
    private val upper: KeyBound?,
) : PlanNode() {
    override val label = "IndexScan ${index.name} on ${table.name}${if (alias != table.name) " $alias" else ""} " +
        "(${table.columns[index.column].name} ${if (lower == upper && lower != null) "= ${lower.value}" else bounds(lower, upper)})"

    override fun rows(): Sequence<Row> = sequence {
        val (from, to) = keyRange(table.indexPrefix(index), lower, upper)
        if (java.util.Arrays.compareUnsigned(from, to) >= 0) return@sequence
        val keys = engine.scan(from, to).use { iterator -> iterator.asSequence().map { it.key }.toList() }
        for (key in keys) engine.get(table.rowKeyFromIndexKey(index, key))?.let { yield(RowCodec.decode(table.types, it)) }
    }
}

class FilterNode(private val child: PlanNode, private val predicate: Expr, private val scope: Scope) : PlanNode() {
    override val label = "Filter $predicate"
    override val children = listOf(child)

    override fun rows() = child.rows().filter { Eval.isTrue(predicate, scope, it) }
}

/** 안쪽을 한 번 읽어 메모리에 두고, 바깥 행마다 전부 훑는다. */
class NestedLoopJoin(
    private val outer: PlanNode,
    private val inner: PlanNode,
    private val on: Expr,
    private val scope: Scope,
    private val outerIsFrom: Boolean,
) : PlanNode() {
    override val label = "NestedLoopJoin $on"
    override val children = listOf(outer, inner)

    override fun rows(): Sequence<Row> = sequence {
        val innerRows = inner.rows().toList()
        for (o in outer.rows()) {
            for (i in innerRows) {
                val row = if (outerIsFrom) o + i else i + o
                if (Eval.isTrue(on, scope, row)) yield(row)
            }
        }
    }
}

/** 바깥 행마다 안쪽을 기본 키나 인덱스로 찾는다. */
class IndexLookupJoin(
    private val outer: PlanNode,
    private val innerLookup: (Value) -> Sequence<Row>,
    private val outerColumn: Int,
    private val innerFilter: Expr?,
    private val innerScope: Scope,
    private val outerIsFrom: Boolean,
    private val description: String,
) : PlanNode() {
    override val label = "IndexLookupJoin $description" + (innerFilter?.let { " [안쪽 Filter $it]" } ?: "")
    override val children = listOf(outer)

    override fun rows(): Sequence<Row> = sequence {
        for (o in outer.rows()) {
            val key = o[outerColumn]
            if (key == Value.Null) continue
            for (i in innerLookup(key)) {
                if (innerFilter != null && !Eval.isTrue(innerFilter, innerScope, i)) continue
                yield(if (outerIsFrom) o + i else i + o)
            }
        }
    }
}

class SortNode(private val child: PlanNode, private val key: Expr, private val descending: Boolean, private val scope: Scope) : PlanNode() {
    override val label = "Sort $key${if (descending) " DESC" else ""}"
    override val children = listOf(child)

    /** NULL은 가장 작은 값으로 취급한다 (오름차순에서 앞, 내림차순에서 뒤). */
    override fun rows(): Sequence<Row> {
        val comparator = Comparator<Pair<Value, Row>> { a, b ->
            when {
                a.first == Value.Null && b.first == Value.Null -> 0
                a.first == Value.Null -> -1
                b.first == Value.Null -> 1
                else -> compareValues(a.first, b.first)!!
            }
        }.let { if (descending) it.reversed() else it }
        return child.rows().map { Eval.eval(key, scope, it) to it }.toList().sortedWith(comparator).asSequence().map { it.second }
    }
}

class LimitNode(private val child: PlanNode, private val limit: Int) : PlanNode() {
    override val label = "Limit $limit"
    override val children = listOf(child)

    override fun rows() = child.rows().take(limit)
}

class ProjectNode(private val child: PlanNode, val items: List<SelectItem>?, private val scope: Scope) : PlanNode() {
    override val label = "Project ${items?.joinToString(", ") { it.label } ?: "*"}"
    override val children = listOf(child)

    val columns: List<String>
        get() = items?.map { it.label } ?: scope.columnNames()

    override fun rows() = if (items == null) child.rows() else child.rows().map { row -> items.map { Eval.eval(it.expr, scope, row) } }
}
