package dev.mydb.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** 0 채움이라 바이트 순서 = 번호 순서. 14 B. */
internal fun key(n: Int) = "key-%010d".format(n).toByteArray()

internal fun usedHeapAfterGc(): Long {
    repeat(3) {
        System.gc()
        Thread.sleep(200)
    }
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

@OptIn(ExperimentalPathApi::class)
internal fun <T> withTempDir(block: (Path) -> T): T {
    val dir = Files.createTempDirectory("mydb-bench")
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

internal fun fmt(n: Number) = "%,d".format(n)

internal fun mib(bytes: Long) = "%.1f MiB".format(bytes / 1024.0 / 1024.0)

internal fun ms(nanos: Long) = "%.3f ms".format(nanos / 1e6)
