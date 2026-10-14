package dev.mydb.engine.log

import dev.mydb.kv.ByteKeys
import dev.mydb.storage.LogRecord

/**
 * 메모리 인덱스: 키 → 최신 값이 있는 디스크 위치.
 * 정렬하지 않는 HashMap을 일부러 쓴다. 범위 스캔이 비싸다는 해시 인덱스의 약점을 그대로 드러내기 위해서다.
 */
internal class Keydir {
    private val map = HashMap<BytesKey, Location>()

    val size: Int
        get() = map.size

    fun get(key: ByteArray): Location? = map[BytesKey(key)]

    /** 저장하는 키만 복사한다. 조회용 BytesKey는 호출자 배열을 그대로 감싼다. */
    fun put(key: ByteArray, location: Location) {
        map[BytesKey(key.copyOf())] = location
    }

    fun remove(key: ByteArray): Location? = map.remove(BytesKey(key))

    /** id ≤ [maxSegmentId] 세그먼트에 있는 엔트리를 디스크 순서로 돌려준다. merge가 순차 읽기를 하도록 정렬한다. */
    fun entriesUpTo(maxSegmentId: Int): List<Pair<ByteArray, Location>> =
        map.entries
            .filter { (_, location) -> location.segmentId <= maxSegmentId }
            .map { (key, location) -> key.bytes to location }
            .sortedWith(compareBy({ it.second.segmentId }, { it.second.frameOffset }, { it.second.indexInBatch }))

    /** 쓰기 경로와 복구 경로가 같은 규칙으로 keydir를 바꾸도록 한 곳에 둔다. */
    fun apply(record: LogRecord, location: Location) {
        when (record) {
            is LogRecord.Put -> put(record.key, location)
            is LogRecord.Delete -> remove(record.key)
            is LogRecord.Batch -> record.ops.forEachIndexed { index, op -> apply(op, location.copy(indexInBatch = index)) }
            is LogRecord.MergeFooter -> Unit
        }
    }

    /** [from, to) 범위의 (키, 위치)를 키 순서로 복사해 돌려준다. 전체 키를 훑어야 하므로 O(N + M log M)이다. */
    fun rangeSnapshot(from: ByteArray, to: ByteArray?): List<Pair<ByteArray, Location>> =
        map.entries
            .filter { (key, _) -> ByteKeys.compare(key.bytes, from) >= 0 && (to == null || ByteKeys.compare(key.bytes, to) < 0) }
            .map { (key, location) -> key.bytes to location }
            .sortedWith(compareBy(ByteKeys.COMPARATOR) { it.first })
}

/** ByteArray의 equals는 참조 비교라 HashMap 키로 쓰려면 내용 비교로 감싸야 한다. */
internal class BytesKey(val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    override fun equals(other: Any?) = other is BytesKey && bytes.contentEquals(other.bytes)

    override fun hashCode() = hash
}

internal data class Location(
    val segmentId: Int,
    /** 프레임 시작 위치. */
    val frameOffset: Long,
    /** 프레임 전체 크기. merge 때 죽은 바이트 계산에 쓴다. */
    val frameSize: Int,
    /** BATCH 프레임 안에서의 op 위치. -1이면 단일 레코드다. */
    val indexInBatch: Int = -1,
)
