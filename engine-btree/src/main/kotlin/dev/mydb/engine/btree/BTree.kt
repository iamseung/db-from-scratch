package dev.mydb.engine.btree

/**
 * 버퍼 풀 위의 B+tree. 모든 변경은 버퍼 풀의 페이지를 고치는 것(dirty)이고 디스크 반영은 엔진의 체크포인트가 한다.
 * split은 메모리에서만 일어나므로, 디스크에서는 체크포인트 단위로 원자적으로 보인다.
 *
 * 삭제는 셀만 지운다. 빈 리프도 남기고 merge·재분배를 하지 않는다 (설계 §2, §7.3).
 */
class BTree(private val pool: BufferPool, root: Int, height: Int) {
    var root: Int = root
        private set
    var height: Int = height
        private set

    /** 연산 하나가 새로 잡을 수 있는 프레임 수의 상한: 경로 h + split으로 새 페이지 h + 새 루트 1 + 여유 1. */
    val requiredHeadroom: Int
        get() = 2 * height + 2

    private class Step(val pageId: Int, val slot: Int)

    private class Split(val separator: ByteArray, val left: Int, val right: Int)

    fun get(key: ByteArray): ByteArray? {
        val leaf = descend(key, path = null)
        try {
            val index = leaf.page.search(key)
            return if (index >= 0) leaf.page.valueAt(index) else null
        } finally {
            pool.unpin(leaf)
        }
    }

    fun put(key: ByteArray, value: ByteArray) {
        val path = ArrayList<Step>()
        val leafFrame = descend(key, path)
        try {
            val leaf = leafFrame.page
            leafFrame.dirty = true
            val found = leaf.search(key)
            if (found >= 0) leaf.removeAt(found)
            val position = if (found >= 0) found else -(found + 1)
            if (leaf.insertLeaf(position, key, value)) return
            insertIntoParents(path, splitLeaf(leafFrame, position, key, value))
        } finally {
            pool.unpin(leafFrame)
        }
    }

    fun delete(key: ByteArray) {
        val leaf = descend(key, path = null)
        try {
            val index = leaf.page.search(key)
            if (index >= 0) {
                leaf.page.removeAt(index)
                leaf.dirty = true
            }
        } finally {
            pool.unpin(leaf)
        }
    }

    /** [from, to)를 키 순서로 복사해 돌려준다. 리프 연결 리스트를 따라간다. */
    fun range(from: ByteArray, to: ByteArray?): List<Pair<ByteArray, ByteArray>> {
        val result = ArrayList<Pair<ByteArray, ByteArray>>()
        var frame = descend(from, path = null)
        var start = frame.page.search(from).let { if (it >= 0) it else -(it + 1) }
        while (true) {
            val page = frame.page
            for (i in start until page.cellCount) {
                if (to != null && page.compareKeyAt(i, to) >= 0) {
                    pool.unpin(frame)
                    return result
                }
                result += page.keyAt(i) to page.valueAt(i)
            }
            val next = page.link
            pool.unpin(frame)
            if (next == 0) return result
            frame = pool.fetch(next)
            start = 0
        }
    }

    /** 리프까지 내려가 pin된 리프를 돌려준다. [path]가 있으면 거쳐 온 (페이지, 슬롯)을 기록한다 (슬롯 -1 = link). */
    private fun descend(key: ByteArray, path: MutableList<Step>?): Frame {
        var pageId = root
        repeat(height - 1) {
            val frame = pool.fetch(pageId)
            try {
                val slot = frame.page.upperBound(key)
                path?.add(Step(pageId, if (slot < frame.page.cellCount) slot else -1))
                pageId = frame.page.childFor(slot)
            } finally {
                pool.unpin(frame)
            }
        }
        return pool.fetch(pageId)
    }

    /** 새 엔트리를 포함한 셀들을 바이트 기준 절반으로 나눠, 오른쪽 절반을 새 리프로 옮긴다. */
    private fun splitLeaf(leftFrame: Frame, position: Int, key: ByteArray, value: ByteArray): Split {
        val left = leftFrame.page
        val cells = (0 until left.cellCount).map { left.keyAt(it) to left.valueAt(it) }.toMutableList()
        cells.add(position, key to value)
        val sizes = cells.map { (k, v) -> 4 + k.size + v.size + 2 }
        val half = sizes.sum() / 2
        var mid = 0
        var acc = 0
        while (mid < cells.size - 1 && acc + sizes[mid] <= half) acc += sizes[mid++]
        mid = mid.coerceIn(1, cells.size - 1)

        val rightFrame = pool.newPage(Page.LEAF)
        try {
            val right = rightFrame.page
            val oldSibling = left.link
            left.init(Page.LEAF)
            cells.subList(0, mid).forEachIndexed { i, (k, v) -> check(left.insertLeaf(i, k, v)) { "split 왼쪽이 넘칩니다" } }
            cells.subList(mid, cells.size).forEachIndexed { i, (k, v) -> check(right.insertLeaf(i, k, v)) { "split 오른쪽이 넘칩니다" } }
            right.link = oldSibling
            left.link = right.id
            leftFrame.dirty = true
            return Split(cells[mid].first, left.id, right.id)
        } finally {
            pool.unpin(rightFrame)
        }
    }

    /**
     * 부모에서 왼쪽을 가리키던 포인터를 오른쪽으로 바꾸고 (구분 키, 왼쪽)을 그 앞에 넣는다.
     * → "구분 키 미만은 왼쪽, 이상은 오른쪽". 부모도 넘치면 위로 반복하고, 루트가 넘치면 새 루트를 만든다.
     */
    private fun insertIntoParents(path: List<Step>, firstSplit: Split) {
        var split = firstSplit
        for (step in path.asReversed()) {
            val frame = pool.fetch(step.pageId)
            try {
                val page = frame.page
                frame.dirty = true
                val slot = if (step.slot < 0) page.cellCount else step.slot
                if (slot < page.cellCount) page.setChildAt(slot, split.right) else page.link = split.right
                if (page.insertInternal(slot, split.separator, split.left)) return
                split = splitInternal(frame, slot, split.separator, split.left)
            } finally {
                pool.unpin(frame)
            }
        }
        val newRoot = pool.newPage(Page.INTERNAL)
        try {
            check(newRoot.page.insertInternal(0, split.separator, split.left))
            newRoot.page.link = split.right
            root = newRoot.page.id
            height += 1
        } finally {
            pool.unpin(newRoot)
        }
    }

    /** 가운데 키를 부모로 올리고(자신에게서는 제거) 나머지를 둘로 나눈다. */
    private fun splitInternal(leftFrame: Frame, slot: Int, key: ByteArray, child: Int): Split {
        val left = leftFrame.page
        val entries = (0 until left.cellCount).map { left.keyAt(it) to left.childAt(it) }.toMutableList()
        entries.add(slot, key to child)
        val rightmost = left.link
        val mid = entries.size / 2
        val (promoted, midChild) = entries[mid]

        val rightFrame = pool.newPage(Page.INTERNAL)
        try {
            val right = rightFrame.page
            left.init(Page.INTERNAL)
            entries.subList(0, mid).forEachIndexed { i, (k, c) -> check(left.insertInternal(i, k, c)) }
            left.link = midChild
            entries.subList(mid + 1, entries.size).forEachIndexed { i, (k, c) -> check(right.insertInternal(i, k, c)) }
            right.link = rightmost
            leftFrame.dirty = true
            return Split(promoted, left.id, right.id)
        } finally {
            pool.unpin(rightFrame)
        }
    }

    /** 검증기용: 페이지를 pin해서 읽고 놓는다. */
    internal fun <T> withPage(pageId: Int, block: (Page) -> T): T {
        val frame = pool.fetch(pageId)
        try {
            return block(frame.page)
        } finally {
            pool.unpin(frame)
        }
    }
}
