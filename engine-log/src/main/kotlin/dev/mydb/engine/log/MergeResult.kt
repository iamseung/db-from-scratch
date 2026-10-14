package dev.mydb.engine.log

data class MergeResult(
    /** 병합되어 사라진 세그먼트 id. 결과는 이 중 가장 큰 id의 세그먼트 하나로 남는다. */
    val mergedSegmentIds: List<Int>,
    val liveEntries: Int,
    val bytesBefore: Long,
    val bytesAfter: Long,
) {
    val reclaimedBytes: Long
        get() = bytesBefore - bytesAfter
}
