package dev.mydb.kv

/**
 * 바이트 키 비교와 정렬 보존 인코딩.
 * 인코딩된 바이트의 사전순이 원래 값의 순서와 같아야 범위 스캔·인덱스가 동작한다.
 */
object ByteKeys {
    val COMPARATOR: Comparator<ByteArray> = Comparator(::compare)

    /** 부호 없는 바이트 사전순 비교. */
    fun compare(a: ByteArray, b: ByteArray): Int = java.util.Arrays.compareUnsigned(a, b)

    /** 부호 비트를 뒤집은 빅엔디언 8바이트. 음수 < 0 < 양수 순서가 바이트 순서로 유지된다. */
    fun encodeLong(value: Long): ByteArray {
        val flipped = value xor Long.MIN_VALUE
        return ByteArray(8) { i -> (flipped ushr (56 - 8 * i)).toByte() }
    }

    fun decodeLong(bytes: ByteArray, offset: Int = 0): Long {
        require(bytes.size - offset >= 8) { "8바이트가 필요하지만 ${bytes.size - offset}바이트뿐입니다" }
        var flipped = 0L
        for (i in 0 until 8) {
            flipped = (flipped shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return flipped xor Long.MIN_VALUE
    }
}
