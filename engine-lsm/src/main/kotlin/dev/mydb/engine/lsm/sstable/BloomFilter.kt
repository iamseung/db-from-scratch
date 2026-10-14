package dev.mydb.engine.lsm.sstable

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * "확실히 없음" 또는 "있을 수도 있음"을 답하는 비트 배열.
 * 해시: 키의 64비트 FNV-1a를 상·하위 32비트(h1, h2)로 나눠 h1 + i·h2로 k개 위치를 만든다 (Kirsch–Mitzenmacher).
 *
 * 직렬화: | bitCount u32 | hashCount u8 | bits |
 * bitCount = 0이면 필터가 없는 것이고 항상 "있을 수도 있음"이다.
 */
class BloomFilter private constructor(private val bitCount: Int, private val hashCount: Int, private val bits: ByteArray) {

    fun mightContain(key: ByteArray): Boolean {
        if (bitCount == 0) return true
        val hash = hash64(key)
        val h1 = (hash ushr 32).toInt()
        val h2 = hash.toInt()
        for (i in 0 until hashCount) {
            if (!getBit(position(h1, h2, i))) return false
        }
        return true
    }

    fun encode(): ByteArray = ByteBuffer.allocate(4 + 1 + bits.size).putInt(bitCount).put(hashCount.toByte()).put(bits).array()

    private fun getBit(index: Int) = bits[index ushr 3].toInt() and (1 shl (index and 7)) != 0

    private fun position(h1: Int, h2: Int, i: Int): Int = ((h1 + i * h2).toLong() and 0xFFFFFFFFL).rem(bitCount).toInt()

    companion object {
        val NONE = BloomFilter(0, 0, ByteArray(0))

        /** 키당 [bitsPerKey] 비트. 해시 수는 위양성률을 최소로 하는 bitsPerKey × ln 2. */
        fun build(keyHashes: LongArray, count: Int, bitsPerKey: Int): BloomFilter {
            if (bitsPerKey == 0) return NONE
            val bitCount = maxOf(64, count * bitsPerKey)
            val hashCount = (bitsPerKey * 0.69).roundToInt().coerceIn(1, 30)
            val filter = BloomFilter(bitCount, hashCount, ByteArray((bitCount + 7) / 8))
            for (n in 0 until count) {
                val h1 = (keyHashes[n] ushr 32).toInt()
                val h2 = keyHashes[n].toInt()
                for (i in 0 until hashCount) {
                    val index = filter.position(h1, h2, i)
                    filter.bits[index ushr 3] = (filter.bits[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
                }
            }
            return filter
        }

        fun decode(payload: ByteArray): BloomFilter {
            val buf = ByteBuffer.wrap(payload)
            val bitCount = buf.getInt()
            val hashCount = buf.get().toInt()
            val bits = ByteArray(buf.remaining()).also { buf.get(it) }
            return BloomFilter(bitCount, hashCount, bits)
        }

        fun hash64(key: ByteArray): Long {
            var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
            for (b in key) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
