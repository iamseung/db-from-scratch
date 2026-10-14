package dev.mydb.engine.lsm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** .wal과 .sst는 하나의 파일 번호 카운터를 공유한다 (LevelDB와 같다). */
internal object FileNames {
    private val WAL = Regex("""^(\d{6,})\.wal$""")
    private val SST = Regex("""^(\d{6,})\.sst$""")

    fun wal(dir: Path, id: Long): Path = dir.resolve("%06d.wal".format(id))

    fun sst(dir: Path, id: Long): Path = dir.resolve("%06d.sst".format(id))

    fun listWals(dir: Path): List<Pair<Long, Path>> = list(dir, WAL)

    fun listTables(dir: Path): List<Pair<Long, Path>> = list(dir, SST)

    fun listTmp(dir: Path): List<Path> = Files.list(dir).use { paths -> paths.toList().filter { it.name.endsWith(".tmp") } }

    private fun list(dir: Path, pattern: Regex): List<Pair<Long, Path>> =
        Files.list(dir).use { paths ->
            paths.toList()
                .mapNotNull { path -> pattern.matchEntire(path.name)?.let { it.groupValues[1].toLong() to path } }
                .sortedBy { it.first }
        }
}
