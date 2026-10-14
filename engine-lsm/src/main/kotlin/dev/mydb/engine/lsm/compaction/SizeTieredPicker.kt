package dev.mydb.engine.lsm.compaction

import dev.mydb.engine.lsm.sstable.TableMeta

/**
 * size-tiered: 크기가 비슷한 테이블끼리 합친다.
 *
 * 입력은 반드시 **나이순으로 연속**이어야 한다. 엔트리에 sequence number가 없고 테이블 순서로 최신 값을 판정하므로,
 * A(오래됨)-B-C(새것)에서 A와 C만 합치면 결과가 B보다 새것으로 취급되어 A의 옛 값이 B의 값을 가린다.
 */
class SizeTieredPicker(
    private val minThreshold: Int,
    private val maxThreshold: Int,
    private val bucketLow: Double = 0.5,
    private val bucketHigh: Double = 1.5,
) {
    init {
        require(minThreshold >= 2) { "minThreshold는 2 이상이어야 합니다: $minThreshold" }
        require(maxThreshold >= minThreshold) { "maxThreshold는 minThreshold 이상이어야 합니다" }
    }

    /**
     * @param tables 살아 있는 테이블 (순서 무관)
     * @return 합칠 테이블 (age 오름차순)과 그 구간이 가장 오래된 테이블을 포함하는지. 고를 구간이 없으면 null.
     */
    fun pick(tables: Collection<TableMeta>): Pick? {
        val byAge = tables.sortedBy { it.age }
        var best: IntRange? = null
        for (start in byAge.indices) {
            var end = start
            var lastValid = -1
            while (end < byAge.size && end - start + 1 <= maxThreshold) {
                val window = byAge.subList(start, end + 1)
                val avg = window.sumOf { it.fileSize }.toDouble() / window.size
                if (window.any { it.fileSize < avg * bucketLow || it.fileSize > avg * bucketHigh }) break
                lastValid = end
                end++
            }
            if (lastValid < 0) continue
            val count = lastValid - start + 1
            // 더 많은 테이블을 합치는 구간을 고르고, 같으면 먼저 찾은(더 오래된) 구간을 유지한다
            if (count >= minThreshold && (best == null || count > best.count())) best = start..lastValid
        }
        return best?.let { range -> Pick(byAge.slice(range), includesOldest = range.first == 0) }
    }

    class Pick(val tables: List<TableMeta>, val includesOldest: Boolean)
}
