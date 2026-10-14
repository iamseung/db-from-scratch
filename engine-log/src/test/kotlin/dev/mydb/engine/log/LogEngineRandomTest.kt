package dev.mydb.engine.log

import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEngineRandomTest
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

class LogEngineRandomTest : KvEngineRandomTest() {
    override fun openEngine(dir: Path): KvEngine =
        LogEngine.open(LogEngineOptions(dir, segmentSizeBytes = 512, syncPolicy = SyncPolicy.MANUAL))

    override fun maintenance(engine: KvEngine) {
        (engine as LogEngine).merge()
    }
}
