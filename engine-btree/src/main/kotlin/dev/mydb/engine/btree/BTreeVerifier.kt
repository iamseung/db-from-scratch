package dev.mydb.engine.btree

import dev.mydb.kv.ByteKeys

data class VerifyReport(
    val height: Int,
    val pages: Int,
    val leaves: Int,
    val keys: Long,
    /** 리프 평균 채움률 (사용 바이트 / 페이지 크기). */
    val averageLeafFill: Double,
    /** 마지막 리프를 제외한 리프 중 최소 채움률. 리프가 하나면 1.0. */
    val minLeafFill: Double,
)

/**
 * B+tree 불변식 검사. 위반이면 IllegalStateException.
 * - 페이지 안 키 엄격 오름차순, 각 키가 부모가 정한 [하한, 상한) 안
 * - 모든 리프 깊이 = height, internal은 셀이 하나 이상
 * - 리프 연결 리스트 순서 = 트리 순회 순서, 마지막 link = 0
 */
object BTreeVerifier {
    fun verify(tree: BTree): VerifyReport {
        val leaves = ArrayList<Int>()
        val fills = ArrayList<Double>()
        var pages = 0
        var keys = 0L

        fun visit(pageId: Int, depth: Int, low: ByteArray?, high: ByteArray?) {
            pages++
            val children = tree.withPage(pageId) { page ->
                val expectLeaf = depth == tree.height
                check(page.isLeaf == expectLeaf) { "페이지 $pageId: 깊이 $depth 에서 leaf=${page.isLeaf} (height ${tree.height})" }
                var previous: ByteArray? = null
                for (i in 0 until page.cellCount) {
                    val key = page.keyAt(i)
                    previous?.let { check(ByteKeys.compare(it, key) < 0) { "페이지 $pageId: 키가 오름차순이 아닙니다 (슬롯 $i)" } }
                    low?.let { check(ByteKeys.compare(key, it) >= 0) { "페이지 $pageId: 키가 하한보다 작습니다 (슬롯 $i)" } }
                    high?.let { check(ByteKeys.compare(key, it) < 0) { "페이지 $pageId: 키가 상한 이상입니다 (슬롯 $i)" } }
                    previous = key
                }
                if (page.isLeaf) {
                    leaves += pageId
                    fills += page.usedBytes().toDouble() / page.size
                    keys += page.cellCount
                    emptyList()
                } else {
                    check(page.cellCount > 0) { "internal 페이지 $pageId 에 셀이 없습니다" }
                    // (자식, 하한, 상한): 슬롯 i는 [이전 키, key_i), link는 [마지막 키, high)
                    val result = ArrayList<Triple<Int, ByteArray?, ByteArray?>>()
                    var lower = low
                    for (i in 0 until page.cellCount) {
                        val key = page.keyAt(i)
                        result += Triple(page.childAt(i), lower, key)
                        lower = key
                    }
                    result += Triple(page.link, lower, high)
                    result
                }
            }
            children.forEach { (child, lo, hi) -> visit(child, depth + 1, lo, hi) }
        }

        visit(tree.root, 1, null, null)

        val chain = ArrayList<Int>()
        var next = leaves.first()
        while (next != 0) {
            check(chain.size <= leaves.size) { "리프 연결 리스트에 순환이 있습니다" }
            chain += next
            next = tree.withPage(next) { it.link }
        }
        check(chain == leaves) { "리프 연결 리스트 순서가 트리 순회와 다릅니다: chain=${chain.take(10)}… tree=${leaves.take(10)}…" }

        val interior = if (fills.size > 1) fills.dropLast(1) else fills
        return VerifyReport(
            height = tree.height,
            pages = pages,
            leaves = leaves.size,
            keys = keys,
            averageLeafFill = fills.average(),
            minLeafFill = if (fills.size > 1) interior.min() else 1.0,
        )
    }
}
