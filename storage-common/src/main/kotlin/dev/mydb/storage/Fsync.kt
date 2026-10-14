package dev.mydb.storage

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

object Fsync {
    /**
     * 디렉터리 엔트리(파일 생성·삭제·이름 변경)를 디스크에 확정한다.
     * Linux에서는 동작하고, 디렉터리를 채널로 열 수 없는 OS에서는 아무것도 하지 않는다.
     */
    fun directory(dir: Path) {
        try {
            FileChannel.open(dir, READ).use { it.force(true) }
        } catch (e: IOException) {
            // 지원하지 않는 플랫폼: 보장 수준이 한 단계 낮아질 뿐 동작은 계속한다
        }
    }
}
