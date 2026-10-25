package dev.mydb.document

import dev.mydb.kv.ByteKeys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun `파싱과 직렬화를 왕복한다`() {
        val text = """{"name":"kim","age":31,"score":-1.5e3,"ok":true,"none":null,"tags":["a","b"],"addr":{"city":"Seoul","zip":"04524"}}"""

        val value = Json.parse(text)

        assertEquals(text.replace("-1.5e3", "-1500"), Json.stringify(value))
        assertEquals(JsonValue.Str("Seoul"), (value as JsonValue.Obj).at("addr.city"))
        assertNull(value.at("addr.city.x"))
        assertNull(value.at("missing"))
    }

    @Test
    fun `이스케이프와 유니코드를 처리한다`() {
        val original = JsonValue.Str("줄\n바꿈 \"따옴표\" \\ 탭\t 제어\u0001 널\u0000")

        assertEquals(original, Json.parse(Json.stringify(original)))
        assertEquals(JsonValue.Str("é"), Json.parse("\"\\u00e9\""))
    }

    @Test
    fun `공백을 허용하고 빈 컨테이너를 읽는다`() {
        assertEquals(JsonValue.Obj(mapOf("a" to JsonValue.Arr(emptyList()), "b" to JsonValue.Obj(emptyMap()))), Json.parse(" { \"a\" : [ ] , \"b\" : { } } "))
    }

    @Test
    fun `잘못된 입력을 거부한다`() {
        listOf("", "{", "[1,", "{\"a\" 1}", "{\"a\":1,}", "tru", "\"abc", "{\"a\":1}x", "{\"a\":1,\"a\":2}", "01x", "\"\\q\"").forEach {
            assertFailsWith<IllegalArgumentException>("입력: $it") { Json.parse(it) }
        }
    }
}

class IndexKeyTest {
    /** 인덱스가 따라야 하는 값 순서: 타입 순서, 같은 타입은 값 순서 (문자열은 UTF-8 바이트 순서). */
    private fun compareValues(a: JsonValue, b: JsonValue): Int {
        fun rank(v: JsonValue) = when (v) {
            JsonValue.Null -> 0
            is JsonValue.Bool -> if (v.value) 2 else 1
            is JsonValue.Num -> 3
            is JsonValue.Str -> 4
            else -> error("인덱스 대상 아님")
        }
        val r = rank(a).compareTo(rank(b))
        if (r != 0) return r
        return when {
            a is JsonValue.Num && b is JsonValue.Num -> if (a.value == b.value) 0 else a.value.compareTo(b.value)
            a is JsonValue.Str && b is JsonValue.Str -> ByteKeys.compare(a.value.toByteArray(), b.value.toByteArray())
            else -> 0
        }
    }

    private fun randomValue(random: Random): JsonValue = when (random.nextInt(5)) {
        0 -> JsonValue.Null
        1 -> JsonValue.Bool(random.nextBoolean())
        2 -> JsonValue.Num(
            when (random.nextInt(4)) {
                0 -> random.nextInt(-1000, 1000).toDouble()
                1 -> random.nextDouble(-1e9, 1e9)
                2 -> listOf(0.0, -0.0, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE).random(random)
                else -> random.nextDouble()
            },
        )
        else -> JsonValue.Str(String(CharArray(random.nextInt(0, 4)) { "a\u0000bé가\uFFFF".random(random) }))
    }

    @Test
    fun `무작위 값 쌍에서 인코딩 바이트 순서가 값 순서와 같다`() {
        val random = Random(1)
        repeat(100_000) {
            val a = randomValue(random)
            val b = randomValue(random)
            val expected = compareValues(a, b).coerceIn(-1, 1)
            val actual = ByteKeys.compare(IndexKey.encode(a)!!, IndexKey.encode(b)!!).coerceIn(-1, 1)
            assertEquals(expected, actual, "$a vs $b")
        }
    }

    @Test
    fun `문자열 접두사와 널 바이트, 뒤에 붙는 문서 id가 섞이지 않는다`() {
        val ab = IndexKey.encode(JsonValue.Str("ab"))!!
        val abc = IndexKey.encode(JsonValue.Str("abc"))!!
        assertTrue(ByteKeys.compare(ab + "zzz".toByteArray(), abc) < 0, "\"ab\" + 문서 id는 \"abc\"보다 앞서야 한다")
        assertTrue(ByteKeys.compare(IndexKey.encode(JsonValue.Str("a"))!!, IndexKey.encode(JsonValue.Str("a\u0000"))!!) < 0)
        assertEquals(0, ByteKeys.compare(IndexKey.encode(JsonValue.Num(0.0))!!, IndexKey.encode(JsonValue.Num(-0.0))!!))
    }

    @Test
    fun `배열과 객체는 인덱스하지 않는다`() {
        assertNull(IndexKey.encode(JsonValue.Arr(emptyList())))
        assertNull(IndexKey.encode(JsonValue.Obj(emptyMap())))
    }
}
