package dev.mydb.storage

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** 데이터 디렉터리를 한 번에 하나의 엔진만 열도록 LOCK 파일에 배타 잠금을 건다. */
class DirectoryLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        const val FILE_NAME = "LOCK"

        fun acquire(dir: Path): DirectoryLock {
            val channel = FileChannel.open(dir.resolve(FILE_NAME), CREATE, WRITE)
            // 같은 JVM 안의 두 번째 시도는 null 대신 OverlappingFileLockException으로 실패한다
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw IllegalStateException("다른 엔진이 이 디렉터리를 사용 중입니다: $dir")
            }
            return DirectoryLock(channel, lock)
        }
    }
}
