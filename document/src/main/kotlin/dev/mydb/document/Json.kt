package dev.mydb.document

/** JSON 값. 숫자는 모두 double로 다룬다 (2^53 초과 정수는 정밀도를 잃는다). */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    /** 필드 순서를 보존한다 (직렬화 결과가 넣은 순서와 같도록). */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        /** 점으로 구분한 경로. 중간이 객체가 아니거나 필드가 없으면 null (= 필드 없음). */
        fun at(path: String): JsonValue? {
            var current: JsonValue = this
            for (part in path.split('.')) {
                current = (current as? Obj)?.fields?.get(part) ?: return null
            }
            return current
        }
    }
}

typealias JsonObject = JsonValue.Obj

object Json {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("값 뒤에 남은 문자가 있습니다")
        return value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw IllegalArgumentException("JSON 객체가 아닙니다: ${text.take(40)}")

    fun stringify(value: JsonValue): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> {
                val d = value.value
                require(d.isFinite()) { "JSON은 NaN·무한대를 표현할 수 없습니다" }
                if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d)
            }
            is JsonValue.Str -> writeString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item -> if (i > 0) out.append(','); write(out, item) }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(out, k)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** 재귀 하강 파서. M6의 SQL 파서와 같은 방식이다. */
    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun fail(message: String): Nothing = throw IllegalArgumentException("JSON 파싱 실패 (위치 $pos): $message")

        fun value(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("값이 필요합니다")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) number() else fail("예상하지 못한 문자 '$c'")
            }
        }

        private fun obj(): JsonValue.Obj {
            pos++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("필드 이름(문자열)이 필요합니다")
                val key = string()
                skipWhitespace()
                expect(':')
                if (fields.put(key, value()) != null) fail("필드 이름이 중복됩니다: $key")
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(fields)
                    else -> fail("',' 또는 '}'가 필요합니다")
                }
            }
        }

        private fun arr(): JsonValue.Arr {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items += value()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> fail("',' 또는 ']'가 필요합니다")
                }
            }
        }

        private fun string(): String {
            pos++ // "
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("문자열이 닫히지 않았습니다")
                when (val c = s[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail("이스케이프가 끝나지 않았습니다")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u 뒤에 16진수 4자리가 필요합니다")
                                out.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("잘못된 \\u 이스케이프"))
                                pos += 4
                            }
                            else -> fail("알 수 없는 이스케이프 \\$e")
                        }
                    }
                    else -> if (c < ' ') fail("문자열 안에 제어 문자가 있습니다") else out.append(c)
                }
            }
        }

        private fun number(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return JsonValue.Num(text.toDoubleOrNull() ?: fail("잘못된 숫자 '$text'"))
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) fail("'$word'가 필요합니다")
            pos += word.length
            return value
        }

        private fun peek(): Char? = s.getOrNull(pos)

        private fun next(): Char? = s.getOrNull(pos++)

        private fun expect(c: Char) {
            if (next() != c) fail("'$c'가 필요합니다")
        }
    }
}
