package dev.mydb.txn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 버전 키: "v" | escape(사용자 키) | 0x00 0x01 | (~ts) u64 빅엔디언
 *
 * - escape: 0x00 → 0x00 0xFF. 종결자 0x00 0x01 덕분에 접두사 관계인 키들의 버전이 섞이지 않고 사용자 키 순서가 보존된다.
 * - ~ts(비트 반전): 같은 키의 버전들이 **최신이 먼저** 정렬된다. 스냅샷 readTs에서 읽으려면 (K, readTs)부터 scan해 첫 항목만 보면 된다.
 */
object VersionKey {
    private val TAG = byteArrayOf('v'.code.toByte())

    /** 모든 버전 키의 접두사. */
    val ALL: ByteArray = TAG

    /** 사용자 키 K의 모든 버전이 공유하는 접두사. */
    fun userPrefix(userKey: ByteArray): ByteArray = TAG + escape(userKey) + byteArrayOf(0x00, 0x01)

    fun encode(userKey: ByteArray, ts: Long): ByteArray = userPrefix(userKey) + ByteBuffer.allocate(8).putLong(ts.inv()).array()

    /** 사용자 키 범위 [from, to)를 덮는 저장 키 범위의 경계. 종결자 없이 escape만 붙인다. */
    fun rangeBound(userKey: ByteArray): ByteArray = TAG + escape(userKey)

    /** 저장 키 → (사용자 키, ts). */
    fun decode(storageKey: ByteArray): Pair<ByteArray, Long> {
        val user = ByteArrayOutputStream()
        var pos = TAG.size
        while (true) {
            val b = storageKey[pos]
            if (b.toInt() == 0) {
                if (storageKey[pos + 1].toInt() == 0x01) break
                user.write(0) // 0x00 0xFF → 0x00
                pos += 2
            } else {
                user.write(b.toInt())
                pos += 1
            }
        }
        val ts = ByteBuffer.wrap(storageKey, pos + 2, 8).getLong().inv()
        return user.toByteArray() to ts
    }

    private fun escape(key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(key.size + 2)
        for (b in key) {
            out.write(b.toInt() and 0xFF)
            if (b.toInt() == 0) out.write(0xFF)
        }
        return out.toByteArray()
    }
}

/** 저장 값의 첫 바이트: 0x01 = 값, 0x02 = 삭제 표시. */
internal object VersionValue {
    private const val PUT: Byte = 0x01
    private const val TOMBSTONE: Byte = 0x02

    fun put(value: ByteArray): ByteArray = byteArrayOf(PUT) + value

    val TOMBSTONE_BYTES = byteArrayOf(TOMBSTONE)

    /** 삭제 표시면 null. */
    fun decode(stored: ByteArray): ByteArray? = if (stored[0] == TOMBSTONE) null else stored.copyOfRange(1, stored.size)

    fun isTombstone(stored: ByteArray) = stored[0] == TOMBSTONE
}

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
