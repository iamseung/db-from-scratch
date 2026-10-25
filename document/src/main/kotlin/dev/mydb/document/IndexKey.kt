package dev.mydb.document

import java.io.ByteArrayOutputStream

/**
 * 인덱스 키 안에 넣는 정렬 보존 값 인코딩. 인코딩한 바이트의 사전순 = 값의 순서.
 *
 * 타입 태그: null 0x01 < false 0x02 < true 0x03 < 숫자 0x04 < 문자열 0x05
 * 숫자: double 8 B. 양수는 부호 비트만, 음수는 모든 비트를 뒤집는다 → 바이트 순서가 수 순서와 같다
 * 문자열: UTF-8, 0x00은 0x00 0xFF로 이스케이프, 끝에 0x00 0x01
 *         → "ab"가 "abc"보다 앞서고, 뒤에 붙는 문서 id와 섞이지 않는다
 * 배열·객체는 인덱스하지 않는다 (null 반환).
 */
object IndexKey {
    const val TAG_NULL: Byte = 0x01
    const val TAG_FALSE: Byte = 0x02
    const val TAG_TRUE: Byte = 0x03
    const val TAG_NUMBER: Byte = 0x04
    const val TAG_STRING: Byte = 0x05

    fun encode(value: JsonValue): ByteArray? {
        val out = ByteArrayOutputStream()
        when (value) {
            JsonValue.Null -> out.write(TAG_NULL.toInt())
            is JsonValue.Bool -> out.write(if (value.value) TAG_TRUE.toInt() else TAG_FALSE.toInt())
            is JsonValue.Num -> {
                out.write(TAG_NUMBER.toInt())
                // -0.0과 0.0을 같은 값으로 취급한다 (JSON 비교에서 같다)
                val bits = java.lang.Double.doubleToLongBits(if (value.value == 0.0) 0.0 else value.value)
                val ordered = if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE
                for (shift in 56 downTo 0 step 8) out.write((ordered ushr shift).toInt() and 0xFF)
            }
            is JsonValue.Str -> {
                out.write(TAG_STRING.toInt())
                for (b in value.value.toByteArray(Charsets.UTF_8)) {
                    out.write(b.toInt() and 0xFF)
                    if (b.toInt() == 0) out.write(0xFF)
                }
                out.write(0x00)
                out.write(0x01)
            }
            is JsonValue.Arr, is JsonValue.Obj -> return null
        }
        return out.toByteArray()
    }
}
