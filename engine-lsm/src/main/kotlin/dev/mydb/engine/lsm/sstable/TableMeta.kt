package dev.mydb.engine.lsm.sstable

/** MANIFEST가 기억하는 테이블 정보. 읽기는 [minKey], [maxKey]로 범위 밖 테이블을 비용 없이 건너뛴다. */
class TableMeta(
    val fileId: Long,
    /** 최신 값 판정 순서. 클수록 새 데이터다. 파일 번호와 다르다 (compaction 결과는 입력의 최대 age를 물려받는다). */
    val age: Long,
    val entryCount: Long,
    val fileSize: Long,
    val minKey: ByteArray,
    val maxKey: ByteArray,
) {
    override fun toString() = "TableMeta(id=$fileId, age=$age, entries=$entryCount, size=$fileSize)"
}
