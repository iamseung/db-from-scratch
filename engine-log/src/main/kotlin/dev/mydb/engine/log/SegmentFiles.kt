package dev.mydb.engine.log

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** 세그먼트 파일 이름 규칙. id 순서 = 시간 순서라는 불변식이 복구의 근거다. */
internal object SegmentFiles {
    private val LOG_NAME = Regex("""^(\d{6})\.log$""")
    private val MERGE_NAME = Regex("""^(\d{6})\.merge$""")

    fun logPath(dir: Path, id: Int): Path = dir.resolve("%06d.log".format(id))

    /** merge 진행 중에만 존재한다. 완료되면 같은 id의 .log로 이름이 바뀐다. */
    fun mergePath(dir: Path, id: Int): Path = dir.resolve("%06d.merge".format(id))

    /** 디렉터리의 세그먼트를 id 오름차순으로 돌려준다. */
    fun listLogs(dir: Path): List<Pair<Int, Path>> = list(dir, LOG_NAME)

    fun listMerges(dir: Path): List<Pair<Int, Path>> = list(dir, MERGE_NAME)

    private fun list(dir: Path, pattern: Regex): List<Pair<Int, Path>> =
        Files.list(dir).use { paths ->
            paths.toList()
                .mapNotNull { path -> pattern.matchEntire(path.name)?.let { it.groupValues[1].toInt() to path } }
                .sortedBy { it.first }
        }
}
