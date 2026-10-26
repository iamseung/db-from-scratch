package dev.mydb.sql

import dev.mydb.kv.KvEngine
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class IndexDef(val id: Int, val name: String, val column: Int)

/** 테이블 스키마. 행·인덱스 키를 만드는 규칙도 여기 둔다 (설계 §3). */
class TableDef(val id: Int, val name: String, val columns: List<ColumnDef>, val indexes: List<IndexDef>) {
    val pk: Int = columns.indexOfFirst { it.primaryKey }
    val types: List<SqlType> = columns.map { it.type }
    val rowPrefix: ByteArray = "r/".toByteArray() + u32(id)

    fun columnIndex(name: String): Int? = columns.indexOfFirst { it.name == name }.takeIf { it >= 0 }

    fun rowKey(pkValue: Value): ByteArray = rowPrefix + KeyCodec.encode(pkValue)

    fun indexPrefix(index: IndexDef): ByteArray = "i/".toByteArray() + u32(index.id)

    /** 인덱스 키 = i/<인덱스 id>/<값>/<기본 키>. 같은 값을 가진 행이 여러 개여도 기본 키가 붙어 겹치지 않는다. */
    fun indexKey(index: IndexDef, row: List<Value>): ByteArray = indexPrefix(index) + KeyCodec.encode(row[index.column]) + KeyCodec.encode(row[pk])

    /** 인덱스 키에서 기본 키 부분을 떼어 행 키로 바꾼다. */
    fun rowKeyFromIndexKey(index: IndexDef, key: ByteArray): ByteArray {
        val valueStart = indexPrefix(index).size
        val valueLength = KeyCodec.encodedLength(key, valueStart, types[index.column])
        return rowPrefix + key.copyOfRange(valueStart + valueLength, key.size)
    }

    fun indexOn(column: Int): IndexDef? = indexes.firstOrNull { it.column == column }

    fun withIndex(index: IndexDef) = TableDef(id, name, columns, indexes + index)

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(id)
            out.writeUTF(name)
            out.writeShort(columns.size)
            columns.forEach { c ->
                out.writeUTF(c.name)
                out.writeByte(c.type.ordinal)
                out.writeBoolean(c.notNull)
                out.writeBoolean(c.primaryKey)
            }
            out.writeShort(indexes.size)
            indexes.forEach { i ->
                out.writeInt(i.id)
                out.writeUTF(i.name)
                out.writeShort(i.column)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): TableDef = DataInputStream(bytes.inputStream()).use { input ->
            val id = input.readInt()
            val name = input.readUTF()
            val columns = List(input.readShort().toInt()) {
                ColumnDef(input.readUTF(), SqlType.entries[input.readByte().toInt()], input.readBoolean(), input.readBoolean())
            }
            val indexes = List(input.readShort().toInt()) { IndexDef(input.readInt(), input.readUTF(), input.readShort().toInt()) }
            TableDef(id, name, columns, indexes)
        }
    }
}

/** 스키마도 같은 KV에 저장한다: s/next → 다음 id, s/t/<이름> → 테이블 정의. */
class Catalog(private val engine: KvEngine) {
    fun table(name: String): TableDef? = engine.get(key(name))?.let(TableDef::decode)

    fun require(name: String): TableDef = table(name) ?: throw SqlException("없는 테이블입니다: $name")

    fun nextId(): Int = engine.get(NEXT)?.let { java.nio.ByteBuffer.wrap(it).getInt() } ?: 1

    /** 다음 id 갱신과 정의 저장을 함께 쓸 배치 연산. */
    fun saveOps(def: TableDef, nextId: Int) = listOf(
        dev.mydb.kv.WriteOp.Put(NEXT, u32(nextId)),
        dev.mydb.kv.WriteOp.Put(key(def.name), def.encode()),
    )

    /** 테이블 정의 키. 트랜잭션 모드에서 행을 쓰는 문장이 지켜본다 (Executor). */
    fun key(name: String) = "s/t/".toByteArray() + name.toByteArray(Charsets.UTF_8)

    private companion object {
        val NEXT = "s/next".toByteArray()
    }
}
