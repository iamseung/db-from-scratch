package dev.mydb.sql

import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp

sealed interface Result {
    data class Rows(val columns: List<String>, val rows: List<List<Value>>) : Result

    data class Affected(val count: Int) : Result

    data class Plan(val text: String) : Result

    data object Ok : Result
}

/**
 * 문장 하나를 주어진 KV 뷰 위에서 실행한다. 문장이 만드는 모든 KV 변경은 writeBatch 한 번이라 문장 단위로 원자적이다.
 * 뷰는 저장 엔진 그 자체(비트랜잭션 모드)이거나 트랜잭션(Txn)을 KvEngine처럼 보이게 한 것이다 — 실행 코드는 둘을 구분하지 않는다.
 * 상태가 없으므로 문장마다 새로 만든다. 세션·트랜잭션 관리는 [Database]·[Session].
 */
internal class Executor(private val engine: KvEngine) {
    private val catalog = Catalog(engine)

    fun execute(statement: Statement): Result = when (statement) {
        is Statement.CreateTable -> createTable(statement)
        is Statement.CreateIndex -> createIndex(statement)
        is Statement.Insert -> insert(statement)
        is Statement.Select -> select(statement)
        is Statement.Update -> update(statement)
        is Statement.Delete -> delete(statement)
        is Statement.Explain -> Result.Plan(planSelect(statement.select).first.explain().trimEnd())
        is Statement.AlterAddColumn -> alterAddColumn(statement)
        Statement.Begin, Statement.Commit, Statement.Rollback -> error("트랜잭션 제어 문장은 Session이 처리한다")
    }

    private fun createTable(s: Statement.CreateTable): Result {
        if (catalog.table(s.name) != null) throw SqlException("이미 있는 테이블입니다: ${s.name}")
        val pks = s.columns.count { it.primaryKey }
        if (pks != 1) throw SqlException("기본 키 열이 정확히 하나여야 합니다 (현재 $pks)")
        if (s.columns.first { it.primaryKey }.type == SqlType.BOOL) throw SqlException("BOOL은 기본 키가 될 수 없습니다")
        s.columns.groupBy { it.name }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { throw SqlException("열 이름이 중복됩니다: $it") }
        val id = catalog.nextId()
        engine.writeBatch(catalog.saveOps(TableDef(id, s.name, s.columns, emptyList()), id + 1))
        return Result.Ok
    }

    /** 인덱스 정의와 기존 행의 인덱스 항목을 한 배치로 쓴다. */
    private fun createIndex(s: Statement.CreateIndex): Result {
        val table = catalog.require(s.table)
        val column = table.columnIndex(s.column) ?: throw SqlException("없는 열입니다: ${s.table}.${s.column}")
        if (table.indexes.any { it.name == s.name }) throw SqlException("이미 있는 인덱스입니다: ${s.name}")
        if (column == table.pk || table.indexOn(column) != null) throw SqlException("이미 인덱스가 있는 열입니다: ${s.column}")
        val id = catalog.nextId()
        val index = IndexDef(id, s.name, column)
        val updated = table.withIndex(index)
        val ops = catalog.saveOps(updated, id + 1).toMutableList()
        SeqScan(engine, table, table.name).rows().forEach { ops += WriteOp.Put(updated.indexKey(index, it), ByteArray(0)) }
        engine.writeBatch(ops)
        return Result.Ok
    }

    /**
     * 행 인코딩은 "스키마 열 순서대로 값만"이라 열이 늘면 기존 행을 새 스키마로 읽을 수 없다.
     * 그래서 모든 행을 새 형식으로 다시 쓴다 (카탈로그 변경과 한 배치, 원자적). 비용은 행 수에 비례한다 (M8 D3).
     * 돌려주는 값은 다시 쓴 행 수.
     */
    private fun alterAddColumn(s: Statement.AlterAddColumn): Result {
        val table = catalog.require(s.table)
        if (table.columnIndex(s.column.name) != null) throw SqlException("이미 있는 열입니다: ${s.table}.${s.column.name}")
        val updated = TableDef(table.id, table.name, table.columns + s.column, table.indexes)
        val ops = catalog.saveOps(updated, catalog.nextId()).toMutableList()
        var rewritten = 0
        SeqScan(engine, table, table.name).rows().forEach { row ->
            ops += WriteOp.Put(updated.rowKey(row[table.pk]), RowCodec.encode(updated.types, row + Value.Null))
            rewritten++
        }
        engine.writeBatch(ops)
        return Result.Affected(rewritten)
    }

    private fun insert(s: Statement.Insert): Result {
        val table = catalog.require(s.table)
        val targets = s.columns?.map { table.columnIndex(it) ?: throw SqlException("없는 열입니다: ${s.table}.$it") } ?: table.columns.indices.toList()
        if (targets.toSet().size != targets.size) throw SqlException("INSERT 열이 중복됩니다")
        val empty = Scope(emptyList())
        val ops = ArrayList<WriteOp>()
        val seen = HashSet<List<Byte>>()
        for (exprs in s.rows) {
            if (exprs.size != targets.size) throw SqlException("값 ${exprs.size}개, 열 ${targets.size}개로 수가 다릅니다")
            val row = MutableList<Value>(table.columns.size) { Value.Null }
            exprs.forEachIndexed { i, e -> row[targets[i]] = Eval.eval(e, empty, emptyList()) }
            validate(table, row)
            val key = table.rowKey(row[table.pk])
            if (!seen.add(key.toList()) || engine.get(key) != null) throw SqlException("기본 키가 중복됩니다: ${row[table.pk]}")
            ops += WriteOp.Put(key, RowCodec.encode(table.types, row))
            table.indexes.forEach { ops += WriteOp.Put(table.indexKey(it, row), ByteArray(0)) }
        }
        engine.writeBatch(ops)
        return Result.Affected(s.rows.size)
    }

    /**
     * Halloween 문제를 피하려고 갱신 대상을 **먼저 모두 모은 뒤** 변경을 계산한다 (설계 §6.3).
     * 기본 키 변경은 허용하지 않는다.
     */
    private fun update(s: Statement.Update): Result {
        val table = catalog.require(s.table)
        val scope = Scope(listOf(table.name to table))
        val assignments = s.assignments.map { (name, expr) -> (table.columnIndex(name) ?: throw SqlException("없는 열입니다: ${s.table}.$name")) to expr }
        val targets = matchingRows(table, s.where).toList()
        val ops = ArrayList<WriteOp>()
        for (old in targets) {
            val new = old.toMutableList()
            assignments.forEach { (column, expr) -> new[column] = Eval.eval(expr, scope, old) }
            if (new[table.pk] != old[table.pk]) throw SqlException("기본 키는 바꿀 수 없습니다: ${table.columns[table.pk].name}")
            validate(table, new)
            ops += WriteOp.Put(table.rowKey(new[table.pk]), RowCodec.encode(table.types, new))
            for (index in table.indexes) {
                if (old[index.column] == new[index.column]) continue
                ops += WriteOp.Delete(table.indexKey(index, old))
                ops += WriteOp.Put(table.indexKey(index, new), ByteArray(0))
            }
        }
        engine.writeBatch(ops)
        return Result.Affected(targets.size)
    }

    private fun delete(s: Statement.Delete): Result {
        val table = catalog.require(s.table)
        val targets = matchingRows(table, s.where).toList()
        val ops = ArrayList<WriteOp>()
        for (row in targets) {
            ops += WriteOp.Delete(table.rowKey(row[table.pk]))
            table.indexes.forEach { ops += WriteOp.Delete(table.indexKey(it, row)) }
        }
        engine.writeBatch(ops)
        return Result.Affected(targets.size)
    }

    private fun matchingRows(table: TableDef, where: Expr?): Sequence<Row> {
        val scope = Scope(listOf(table.name to table))
        val access = accessPath(table, table.name, Eval.conjuncts(where), scope)
        return if (where == null) access.rows() else FilterNode(access, where, scope).rows()
    }

    private fun validate(table: TableDef, row: List<Value>) {
        table.columns.forEachIndexed { i, column ->
            val v = row[i]
            if (v == Value.Null) {
                if (column.notNull) throw SqlException("NOT NULL 열에 NULL을 넣을 수 없습니다: ${column.name}")
            } else if (v.typeOrNull() != column.type) {
                throw SqlException("타입이 다릅니다: ${column.name}은(는) ${column.type}인데 값은 $v")
            }
        }
    }

    private fun select(s: Statement.Select): Result {
        val (plan, project) = planSelect(s)
        return Result.Rows(project.columns, plan.rows().toList())
    }

    /** 플랜 트리와 최상단 Project를 돌려준다 (EXPLAIN과 실행이 같은 플랜을 쓴다). */
    private fun planSelect(s: Statement.Select): Pair<PlanNode, ProjectNode> {
        val from = catalog.require(s.from.name)
        val join = s.join?.let { catalog.require(it.name) }
        val tables = listOfNotNull(s.from.alias to from, join?.let { s.join.alias to it })
        if (tables.size == 2 && tables[0].first == tables[1].first) throw SqlException("두 테이블의 별칭이 같습니다: ${tables[0].first}")
        val scope = Scope(tables)
        val conjuncts = Eval.conjuncts(s.where)
        // 모든 열 참조가 해석되는지 먼저 확인한다 (빈 테이블에서도 오류를 내기 위해)
        (conjuncts + listOfNotNull(s.on, s.orderBy) + (s.items?.map { it.expr } ?: emptyList())).forEach { e -> Eval.columnsOf(e).forEach { scope.resolve(it) } }

        var node: PlanNode = if (join == null) {
            accessPath(from, s.from.alias, conjuncts, scope)
        } else {
            planJoin(s, from, join, conjuncts, scope)
        }
        // 접근 경로나 조인이 이미 쓴 조건도 다시 검사한다. 인덱스 범위 계산이 틀려도 결과가 틀리지 않게 하는 안전망이다
        s.where?.let { node = FilterNode(node, it, scope) }
        s.orderBy?.let { node = SortNode(node, it, s.descending, scope) }
        s.limit?.let { node = LimitNode(node, it) }
        val project = ProjectNode(node, s.items, scope)
        return project to project
    }

    /** 설계 §5.1: pk = 상수 → pk 범위 → 인덱스 = 상수 → 인덱스 범위 → 전체 스캔. */
    private fun accessPath(table: TableDef, alias: String, conjuncts: List<Expr>, scope: Scope): PlanNode {
        val tableIndex = scope.tables.indexOfFirst { it.first == alias }.coerceAtLeast(0)
        val predicates = conjuncts.mapNotNull { simplePredicate(it, scope, tableIndex) }
        fun eq(column: Int) = predicates.firstOrNull { it.column == column && it.op == Expr.Op.EQ }
        fun range(column: Int): Pair<KeyBound?, KeyBound?>? {
            val lower = predicates.firstOrNull { it.column == column && (it.op == Expr.Op.GT || it.op == Expr.Op.GE) }
                ?.let { KeyBound(it.value, it.op == Expr.Op.GE) }
            val upper = predicates.firstOrNull { it.column == column && (it.op == Expr.Op.LT || it.op == Expr.Op.LE) }
                ?.let { KeyBound(it.value, it.op == Expr.Op.LE) }
            return if (lower == null && upper == null) null else lower to upper
        }
        eq(table.pk)?.let { return PkLookup(engine, table, alias, it.value) }
        range(table.pk)?.let { (lo, hi) -> return PkRangeScan(engine, table, alias, lo, hi) }
        for (index in table.indexes) eq(index.column)?.let { return IndexScan(engine, table, alias, index, KeyBound(it.value, true), KeyBound(it.value, true)) }
        for (index in table.indexes) range(index.column)?.let { (lo, hi) -> return IndexScan(engine, table, alias, index, lo, hi) }
        return SeqScan(engine, table, alias)
    }

    private data class SimplePredicate(val column: Int, val op: Expr.Op, val value: Value)

    /** "열 op 상수" (또는 "상수 op 열")이고 열이 이 테이블 것이면. NULL 상수는 어떤 행과도 참이 아니라 제외. */
    private fun simplePredicate(expr: Expr, scope: Scope, tableIndex: Int): SimplePredicate? {
        if (expr !is Expr.Binary || !expr.op.isComparison || expr.op == Expr.Op.NE) return null
        val (column, constant, op) = when {
            expr.left is Expr.Column && Eval.isConstant(expr.right) -> Triple(expr.left, expr.right, expr.op)
            expr.right is Expr.Column && Eval.isConstant(expr.left) -> Triple(expr.right, expr.left, expr.op.flipped())
            else -> return null
        }
        if (scope.tableIndexOf(column) != tableIndex) return null
        val value = Eval.eval(constant, scope, emptyList())
        if (value == Value.Null) return null
        val position = scope.resolve(column) - scope.offsetOf(tableIndex)
        val def = scope.tables[tableIndex].second
        if (value.typeOrNull() != def.columns[position].type) {
            throw SqlException("타입이 다른 값을 비교할 수 없습니다: $column (${def.columns[position].type}) vs $value")
        }
        return SimplePredicate(position, op, value)
    }

    /**
     * 설계 §5.3. 한쪽 테이블만 참조하는 조건은 그 테이블 스캔으로 내린다(pushdown).
     * ON이 "a.x = b.y"이고 안쪽 열에 기본 키나 인덱스가 있으면 IndexLookupJoin, 없으면 NestedLoopJoin.
     * 안쪽에만 인덱스가 없고 바깥쪽 열에 있으면 둘을 바꾼다. 출력 행은 항상 FROM 테이블 열이 먼저다.
     */
    private fun planJoin(s: Statement.Select, from: TableDef, join: TableDef, conjuncts: List<Expr>, scope: Scope): PlanNode {
        val on = s.on!!
        fun onlyTable(e: Expr, t: Int) = Eval.columnsOf(e).let { cols -> cols.isNotEmpty() && cols.all { scope.tableIndexOf(it) == t } }
        val pushed = listOf(conjuncts.filter { onlyTable(it, 0) }, conjuncts.filter { onlyTable(it, 1) })
        val tableScopes = listOf(Scope(listOf(scope.tables[0])), Scope(listOf(scope.tables[1])))
        val defs = listOf(from, join)

        // ON이 두 테이블의 열 하나씩을 = 로 잇는 경우만 인덱스 조인 후보
        val equi = (on as? Expr.Binary)?.takeIf { it.op == Expr.Op.EQ && it.left is Expr.Column && it.right is Expr.Column }?.let {
            val l = it.left as Expr.Column
            val r = it.right as Expr.Column
            val lt = scope.tableIndexOf(l)
            val rt = scope.tableIndexOf(r)
            when {
                lt == 0 && rt == 1 -> l to r
                lt == 1 && rt == 0 -> r to l
                else -> null
            }
        }
        fun lookupFor(t: Int, column: Expr.Column): ((Value) -> Sequence<Row>)? {
            val def = defs[t]
            val position = scope.resolve(column) - scope.offsetOf(t)
            val alias = scope.tables[t].first
            return when {
                position == def.pk -> { v -> PkLookup(engine, def, alias, v).rows() }
                def.indexOn(position) != null -> { v -> IndexScan(engine, def, alias, def.indexOn(position)!!, KeyBound(v, true), KeyBound(v, true)).rows() }
                else -> null
            }
        }
        fun pushedNode(t: Int): PlanNode {
            val access = accessPath(defs[t], scope.tables[t].first, pushed[t], tableScopes[t])
            val predicate = pushed[t].reduceOrNull { a, b -> Expr.Binary(Expr.Op.AND, a, b) }
            return predicate?.let { FilterNode(access, it, tableScopes[t]) } ?: access
        }

        if (equi != null) {
            val columns = listOf(equi.first, equi.second)
            for ((outer, inner) in listOf(0 to 1, 1 to 0)) {
                val lookup = lookupFor(inner, columns[inner]) ?: continue
                val outerColumn = scope.resolve(columns[outer]) - scope.offsetOf(outer)
                val innerFilter = pushed[inner].reduceOrNull { a, b -> Expr.Binary(Expr.Op.AND, a, b) }
                return IndexLookupJoin(
                    pushedNode(outer), lookup, outerColumn, innerFilter, tableScopes[inner], outerIsFrom = outer == 0,
                    description = "${columns[outer]} → ${scope.tables[inner].first}.${columns[inner].name}",
                )
            }
        }
        return NestedLoopJoin(pushedNode(0), pushedNode(1), on, scope, outerIsFrom = true)
    }
}
