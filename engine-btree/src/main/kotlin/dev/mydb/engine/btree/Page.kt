package dev.mydb.engine.btree

import java.util.Arrays
import java.util.zip.CRC32C

/**
 * 고정 크기 페이지 한 장. 리프·internal 모두 슬롯 페이지다.
 *
 * ```
 * offset size field
 * 0      4    checksum   CRC32C(bytes[4..size)) — 디스크에 쓸 때 기록, 읽을 때 검사
 * 4      1    type       0 meta, 1 leaf, 2 internal
 * 6      2    cellCount
 * 8      2    freeStart  슬롯 배열 끝 (앞→뒤)
 * 10     2    freeEnd    셀 영역 시작 (뒤→앞)
 * 12     4    link       leaf: 오른쪽 형제 (0 = 없음) / internal: 가장 오른쪽 자식
 * 16     ...  슬롯 배열 (u16 셀 오프셋 × cellCount, 키 순서)
 * ```
 *
 * 셀: leaf `| keyLen u16 | valLen u16 | key | value |`, internal `| keyLen u16 | child u32 | key |`.
 * internal 슬롯 i의 (key_i, child_i)는 "key < key_i 인 키는 child_i"이고, 모든 key_i 이상은 link로 간다.
 */
class Page(val id: Int, val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    var type: Int
        get() = bytes[4].toInt()
        set(value) {
            bytes[4] = value.toByte()
        }

    var cellCount: Int
        get() = u16(6)
        private set(value) = putU16(6, value)

    private var freeStart: Int
        get() = u16(8)
        set(value) = putU16(8, value)

    private var freeEnd: Int
        get() = u16(10).let { if (it == 0) size else it } // 65536은 u16에 안 들어가므로 0으로 저장한다
        set(value) = putU16(10, if (value == size) 0 else value)

    var link: Int
        get() = i32(12)
        set(value) = putI32(12, value)

    val isLeaf: Boolean
        get() = type == LEAF

    fun init(type: Int) {
        Arrays.fill(bytes, 0)
        this.type = type
        cellCount = 0
        freeStart = HEADER_SIZE
        freeEnd = size
        link = 0
    }

    /** 셀 영역의 구멍까지 합친 실제 사용 가능 공간. */
    fun reclaimableSpace(): Int = size - HEADER_SIZE - cellCount * 2 - (0 until cellCount).sumOf { cellSize(cellOffset(it)) }

    fun usedBytes(): Int = size - reclaimableSpace()

    // ---- 키 탐색 ----

    fun keyAt(i: Int): ByteArray {
        val off = cellOffset(i)
        val keyStart = off + keyOffsetInCell()
        return bytes.copyOfRange(keyStart, keyStart + u16(off))
    }

    /** i번째 키와 [key]의 부호 없는 바이트 비교. 배열을 만들지 않는다. */
    fun compareKeyAt(i: Int, key: ByteArray): Int {
        val off = cellOffset(i)
        val keyStart = off + keyOffsetInCell()
        return Arrays.compareUnsigned(bytes, keyStart, keyStart + u16(off), key, 0, key.size)
    }

    /** 찾으면 인덱스, 없으면 -(삽입 위치) - 1. */
    fun search(key: ByteArray): Int {
        var low = 0
        var high = cellCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareKeyAt(mid, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    /** key_i > key 인 첫 슬롯. internal에서 내려갈 자식을 고른다 (없으면 cellCount → link). */
    fun upperBound(key: ByteArray): Int {
        var low = 0
        var high = cellCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareKeyAt(mid, key) <= 0) low = mid + 1 else high = mid
        }
        return low
    }

    // ---- leaf ----

    fun valueAt(i: Int): ByteArray {
        val off = cellOffset(i)
        val valueStart = off + 4 + u16(off)
        return bytes.copyOfRange(valueStart, valueStart + u16(off + 2))
    }

    /** 공간이 모자라면 compact 후 재시도하고, 그래도 모자라면 false. */
    fun insertLeaf(index: Int, key: ByteArray, value: ByteArray): Boolean {
        val cellSize = 4 + key.size + value.size
        if (!makeRoom(cellSize)) return false
        val off = allocateCell(cellSize)
        putU16(off, key.size)
        putU16(off + 2, value.size)
        key.copyInto(bytes, off + 4)
        value.copyInto(bytes, off + 4 + key.size)
        insertSlot(index, off)
        return true
    }

    // ---- internal ----

    fun childAt(i: Int): Int = i32(cellOffset(i) + 2)

    fun setChildAt(i: Int, child: Int) = putI32(cellOffset(i) + 2, child)

    /** 슬롯 i의 자식, i == cellCount면 link. */
    fun childFor(slot: Int): Int = if (slot < cellCount) childAt(slot) else link

    fun insertInternal(index: Int, key: ByteArray, child: Int): Boolean {
        val cellSize = 6 + key.size
        if (!makeRoom(cellSize)) return false
        val off = allocateCell(cellSize)
        putU16(off, key.size)
        putI32(off + 2, child)
        key.copyInto(bytes, off + 6)
        insertSlot(index, off)
        return true
    }

    // ---- 공통 ----

    /** 슬롯만 지운다. 셀 바이트는 구멍으로 남고 다음 compact 때 회수된다. */
    fun removeAt(index: Int) {
        val slotPos = HEADER_SIZE + index * 2
        System.arraycopy(bytes, slotPos + 2, bytes, slotPos, freeStart - slotPos - 2)
        cellCount -= 1
        freeStart -= 2
    }

    /** 셀을 빈틈없이 다시 쌓아 구멍을 없앤다. 슬롯 순서(키 순서)는 그대로다. */
    fun compact() {
        val cells = (0 until cellCount).map { i -> val off = cellOffset(i); bytes.copyOfRange(off, off + cellSize(off)) }
        var end = size
        cells.forEachIndexed { i, cell ->
            end -= cell.size
            cell.copyInto(bytes, end)
            putU16(HEADER_SIZE + i * 2, end)
        }
        Arrays.fill(bytes, freeStart, end, 0)
        freeEnd = end
    }

    fun writeChecksum() = putI32(0, checksum())

    fun checksumMatches(): Boolean = i32(0) == checksum()

    private fun checksum(): Int = CRC32C().apply { update(bytes, 4, size - 4) }.value.toInt()

    private fun makeRoom(cellSize: Int): Boolean {
        if (freeEnd - freeStart >= cellSize + 2) return true
        if (reclaimableSpace() < cellSize + 2) return false
        compact()
        return true
    }

    private fun allocateCell(cellSize: Int): Int {
        freeEnd -= cellSize
        return freeEnd
    }

    private fun insertSlot(index: Int, cellOffset: Int) {
        val slotPos = HEADER_SIZE + index * 2
        System.arraycopy(bytes, slotPos, bytes, slotPos + 2, freeStart - slotPos)
        putU16(slotPos, cellOffset)
        cellCount += 1
        freeStart += 2
    }

    private fun cellOffset(i: Int): Int = u16(HEADER_SIZE + i * 2)

    private fun keyOffsetInCell(): Int = if (isLeaf) 4 else 6

    private fun cellSize(off: Int): Int = if (isLeaf) 4 + u16(off) + u16(off + 2) else 6 + u16(off)

    internal fun u16(pos: Int): Int = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)

    internal fun putU16(pos: Int, value: Int) {
        bytes[pos] = (value ushr 8).toByte()
        bytes[pos + 1] = value.toByte()
    }

    internal fun i32(pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)

    internal fun putI32(pos: Int, value: Int) {
        bytes[pos] = (value ushr 24).toByte()
        bytes[pos + 1] = (value ushr 16).toByte()
        bytes[pos + 2] = (value ushr 8).toByte()
        bytes[pos + 3] = value.toByte()
    }

    companion object {
        const val HEADER_SIZE = 16
        const val META = 0
        const val LEAF = 1
        const val INTERNAL = 2

        /** 리프 하나에 셀이 최소 4개 들어가도록 하는 key + value 상한. */
        fun maxCellPayload(pageSize: Int): Int = (pageSize - HEADER_SIZE) / 4 - 6
    }
}
