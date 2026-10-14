package dev.mydb.engine.lsm.manifest

import dev.mydb.engine.lsm.sstable.TableMeta

/**
 * 특정 시점의 살아 있는 테이블 목록 (age 내림차순, 불변).
 * scan 반복자가 retain해 두면, 그 사이 compaction이 입력 파일을 지우지 못한다.
 */
class Version(tables: Collection<TableMeta>) {
    val tables: List<TableMeta> = tables.sortedByDescending { it.age }

    /** 엔진의 current 참조로 1에서 시작한다. 엔진의 동기화 안에서만 바뀐다. */
    var refs = 1
        private set

    fun retain() {
        check(refs > 0) { "이미 해제된 Version입니다" }
        refs++
    }

    /** 참조가 0이 되면 true. */
    fun release(): Boolean {
        check(refs > 0) { "이미 해제된 Version입니다" }
        return --refs == 0
    }
}
