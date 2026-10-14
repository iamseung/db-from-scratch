package dev.mydb.storage

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class DirectoryLockTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `같은 디렉터리를 두 번 잠글 수 없다`() {
        DirectoryLock.acquire(dir).use {
            assertFailsWith<IllegalStateException> { DirectoryLock.acquire(dir) }
        }
    }

    @Test
    fun `잠금을 풀면 다시 잠글 수 있다`() {
        DirectoryLock.acquire(dir).close()
        DirectoryLock.acquire(dir).close()
    }
}
