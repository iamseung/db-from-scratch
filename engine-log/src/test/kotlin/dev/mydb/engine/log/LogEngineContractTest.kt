package dev.mydb.engine.log

import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEngineContractTest
import java.nio.file.Path

class LogEngineContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir))
}

/** 쓰기마다 새 세그먼트로 넘어가도 의미가 같아야 한다. */
class LogEngineTinySegmentContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = LogEngine.open(LogEngineOptions(dir, segmentSizeBytes = 1))
}
