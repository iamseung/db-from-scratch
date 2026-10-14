package dev.mydb.engine.lsm

import dev.mydb.kv.ByteKeys
import dev.mydb.storage.LogRecord
import java.util.concurrent.ConcurrentSkipListMap

/**
 * 쓰기를 모으는 정렬된 메모리 표. 가득 차면 SSTable 하나로 flush된다.
 * 크기는 근사값이다: 키 + 값 + 엔트리당 고정 오버헤드.
 */
class MemTable {
    private val map = ConcurrentSkipListMap<ByteArray, MemValue>(ByteKeys.COMPARATOR)

    var approximateBytes = 0L
        private set

    val size: Int
        get() = map.size

    fun isEmpty() = map.isEmpty()

    fun get(key: ByteArray): MemValue? = map[key]

    /** WAL 레코드를 그대로 반영한다. 쓰기 경로와 WAL 재생이 같은 규칙을 쓰도록 한 곳에 둔다. */
    fun apply(record: LogRecord) {
        when (record) {
            is LogRecord.Put -> set(record.key, MemValue.Put(record.value.copyOf()))
            is LogRecord.Delete -> set(record.key, MemValue.Tombstone)
            is LogRecord.Batch -> record.ops.forEach { apply(it) }
            is LogRecord.MergeFooter -> Unit
        }
    }

    /** 정렬 순서 전체. flush가 쓴다. */
    fun entries(): Iterator<Entry> = map.entries.asSequence().map { Entry(it.key, it.value) }.iterator()

    /** [from, to) 범위를 복사한다. scan이 생성 시점의 스냅샷을 갖게 한다. */
    fun rangeSnapshot(from: ByteArray, to: ByteArray?): List<Entry> {
        val range = if (to == null) map.tailMap(from, true) else map.subMap(from, true, to, false)
        return range.entries.map { Entry(it.key, it.value) }
    }

    private fun set(key: ByteArray, value: MemValue) {
        val old = map.put(key.copyOf(), value)
        approximateBytes += if (old == null) key.size + value.bytes() + ENTRY_OVERHEAD else value.bytes() - old.bytes()
    }

    private fun MemValue.bytes(): Int = if (this is MemValue.Put) value.size else 0

    private companion object {
        /** skiplist 노드, 키·값 배열 헤더 등을 대략 반영한 값. 정확할 필요는 없다. */
        const val ENTRY_OVERHEAD = 32
    }
}
