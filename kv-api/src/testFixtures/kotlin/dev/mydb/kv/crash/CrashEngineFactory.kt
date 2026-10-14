package dev.mydb.kv.crash

import dev.mydb.kv.KvEngine
import java.nio.file.Path

/**
 * 크래시 테스트의 자식 JVM이 엔진을 만드는 방법. 엔진 모듈의 테스트가 구현한다.
 * 자식은 클래스 이름으로 인스턴스를 만들므로 인자 없는 public 생성자가 있어야 한다.
 */
interface CrashEngineFactory {
    /** [durable]이면 쓰기가 반환될 때 디스크에 확정되는 설정(EVERY_WRITE), 아니면 fsync 없는 설정(MANUAL). */
    fun open(dataDir: Path, durable: Boolean): KvEngine

    /** 엔진별 유지 작업. M1은 merge, M2는 compaction. */
    fun maintenance(engine: KvEngine) = Unit
}
