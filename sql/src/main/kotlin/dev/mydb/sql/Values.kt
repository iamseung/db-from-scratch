package dev.mydb.sql

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SqlException(message: String) : RuntimeException(message)

enum class SqlType { INT, TEXT, BOOL }

/** SQL 값. NULL은 타입이 없다. */
sealed interface Value {
    data object Null : Value {
        override fun toString() = "NULL"
    }

    data class IntV(val v: Long) : Value {
        override fun toString() = v.toString()
    }

    data class TextV(val v: String) : Value {
        override fun toString() = "'$v'"
    }

    data class BoolV(val v: Boolean) : Value {
        override fun toString() = if (v) "TRUE" else "FALSE"
    }
}

fun Value.typeOrNull(): SqlType? = when (this) {
    Value.Null -> null
    is Value.IntV -> SqlType.INT
    is Value.TextV -> SqlType.TEXT
    is Value.BoolV -> SqlType.BOOL
}

/** 같은 타입끼리만 비교한다. TEXT는 UTF-8 바이트 순서라 인덱스 키 순서와 같다. NULL이 끼면 null. */
fun compareValues(a: Value, b: Value): Int? {
    if (a == Value.Null || b == Value.Null) return null
    return when {
        a is Value.IntV && b is Value.IntV -> a.v.compareTo(b.v)
        a is Value.TextV && b is Value.TextV -> java.util.Arrays.compareUnsigned(a.v.toByteArray(), b.v.toByteArray())
        a is Value.BoolV && b is Value.BoolV -> a.v.compareTo(b.v)
        else -> throw SqlException("타입이 다른 값을 비교할 수 없습니다: $a (${a.typeOrNull()}) vs $b (${b.typeOrNull()})")
    }
}

/**
 * 정렬 보존 키 인코딩 (기본 키, 인덱스 값). 바이트 사전순 = 값 순서.
 * NULL 0x01 < 값 0x02 / INT: 부호 비트를 뒤집은 빅엔디언 8 B / BOOL 1 B /
 * TEXT: UTF-8, 0x00 → 0x00 0xFF, 끝에 0x00 0x01 (M5 IndexKey와 같은 규칙)
 */
object KeyCodec {
    private const val TAG_NULL = 0x01
    private const val TAG_VALUE = 0x02

    fun encode(value: Value): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            Value.Null -> out.write(TAG_NULL)
            is Value.IntV -> {
                out.write(TAG_VALUE)
                out.write(ByteBuffer.allocate(8).putLong(value.v xor Long.MIN_VALUE).array())
            }
            is Value.BoolV -> {
                out.write(TAG_VALUE)
                out.write(if (value.v) 1 else 0)
            }
            is Value.TextV -> {
                out.write(TAG_VALUE)
                for (b in value.v.toByteArray(Charsets.UTF_8)) {
                    out.write(b.toInt() and 0xFF)
                    if (b.toInt() == 0) out.write(0xFF)
                }
                out.write(0x00)
                out.write(0x01)
            }
        }
        return out.toByteArray()
    }

    /** [bytes]의 [offset]에서 시작하는 인코딩 하나의 길이. 인덱스 키에서 값 뒤의 기본 키를 떼어 낼 때 쓴다. */
    fun encodedLength(bytes: ByteArray, offset: Int, type: SqlType): Int {
        if (bytes[offset].toInt() == TAG_NULL) return 1
        return when (type) {
            SqlType.INT -> 9
            SqlType.BOOL -> 2
            SqlType.TEXT -> {
                var pos = offset + 1
                while (!(bytes[pos].toInt() == 0 && bytes[pos + 1].toInt() == 1)) pos += if (bytes[pos].toInt() == 0) 2 else 1
                pos + 2 - offset
            }
        }
    }

    /** 키 범위의 값 부분 하한: NULL을 건너뛴 첫 값. */
    val FIRST_VALUE = byteArrayOf(TAG_VALUE.toByte())
}

/** 행 인코딩: | null 비트맵 | null이 아닌 값들 (정의 순서) |. 스키마가 이름·타입을 알므로 값만 저장한다. */
object RowCodec {
    fun encode(types: List<SqlType>, row: List<Value>): ByteArray {
        val out = ByteArrayOutputStream()
        val bitmap = ByteArray((types.size + 7) / 8)
        row.forEachIndexed { i, v -> if (v == Value.Null) bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        out.write(bitmap)
        row.forEach { v ->
            when (v) {
                Value.Null -> Unit
                is Value.IntV -> out.write(ByteBuffer.allocate(8).putLong(v.v).array())
                is Value.BoolV -> out.write(if (v.v) 1 else 0)
                is Value.TextV -> {
                    val bytes = v.v.toByteArray(Charsets.UTF_8)
                    out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                    out.write(bytes)
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(types: List<SqlType>, bytes: ByteArray): List<Value> {
        val buf = ByteBuffer.wrap(bytes)
        val bitmap = ByteArray((types.size + 7) / 8).also { buf.get(it) }
        return types.mapIndexed { i, type ->
            if (bitmap[i / 8].toInt() and (1 shl (i % 8)) != 0) {
                Value.Null
            } else {
                when (type) {
                    SqlType.INT -> Value.IntV(buf.getLong())
                    SqlType.BOOL -> Value.BoolV(buf.get().toInt() != 0)
                    SqlType.TEXT -> Value.TextV(String(ByteArray(buf.getInt()).also { buf.get(it) }, Charsets.UTF_8))
                }
            }
        }
    }
}

/** [prefix]로 시작하는 모든 키보다 큰 가장 작은 키. */
internal fun prefixEnd(prefix: ByteArray): ByteArray {
    val end = prefix.copyOf()
    for (i in end.indices.reversed()) {
        if (end[i] != 0xFF.toByte()) {
            end[i] = (end[i] + 1).toByte()
            return end.copyOf(i + 1)
        }
    }
    throw IllegalArgumentException("모든 바이트가 0xFF인 접두사는 상한을 만들 수 없습니다")
}

internal fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
