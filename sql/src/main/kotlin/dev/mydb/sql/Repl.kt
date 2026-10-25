package dev.mydb.sql

import dev.mydb.engine.btree.BTreeEngine
import dev.mydb.engine.btree.BTreeOptions
import dev.mydb.engine.log.LogEngine
import dev.mydb.engine.log.LogEngineOptions
import dev.mydb.engine.lsm.LsmEngine
import dev.mydb.engine.lsm.LsmOptions
import dev.mydb.kv.KvEngine
import java.nio.file.Path

/**
 * ./gradlew :sql:run --args="--engine=lsm --dir=data/sql" -q --console=plain
 * 문장은 세미콜론으로 끝낸다. 빈 줄이나 \q로 끝낸다.
 */
fun main(args: Array<String>) {
    val options = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
    val dir = Path.of(options["dir"] ?: "data/sql-${options["engine"] ?: "lsm"}")
    val engine: KvEngine = when (val name = options["engine"] ?: "lsm") {
        "log" -> LogEngine.open(LogEngineOptions(dir))
        "lsm" -> LsmEngine.open(LsmOptions(dir))
        "btree" -> BTreeEngine.open(BTreeOptions(dir))
        else -> error("엔진은 log | lsm | btree 중 하나입니다: $name")
    }
    engine.use {
        val db = Database(it)
        println("mydb (${options["engine"] ?: "lsm"}, $dir) — 문장은 ;로 끝내세요. \\q로 종료")
        val buffer = StringBuilder()
        while (true) {
            print(if (buffer.isEmpty()) "mydb> " else "   -> ")
            val line = readlnOrNull() ?: break
            if (line.trim() == "\\q") break
            buffer.appendLine(line)
            if (!line.trimEnd().endsWith(";")) continue
            val sql = buffer.toString()
            buffer.clear()
            try {
                println(render(db.execute(sql)))
            } catch (e: SqlException) {
                println("오류: ${e.message}")
            }
        }
    }
}

fun render(result: Result): String = when (result) {
    Result.Ok -> "OK"
    is Result.Affected -> "${result.count}개 행"
    is Result.Plan -> result.text
    is Result.Rows -> {
        val cells = listOf(result.columns) + result.rows.map { row -> row.map { v -> if (v is Value.TextV) v.v else v.toString() } }
        val widths = result.columns.indices.map { c -> cells.maxOf { it[c].length } }
        val lines = cells.map { row -> row.mapIndexed { c, s -> s.padEnd(widths[c]) }.joinToString(" | ") }
        (listOf(lines[0], widths.joinToString("-+-") { "-".repeat(it) }) + lines.drop(1) + "(${result.rows.size}개 행)").joinToString("\n")
    }
}
