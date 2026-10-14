package dev.mydb.engine.log

import dev.mydb.kv.KvEngine
import dev.mydb.kv.crash.CrashEngineFactory
import dev.mydb.kv.crash.KvEngineCrashTest
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path
import kotlin.test.assertEquals

class LogEngineCrashTest : KvEngineCrashTest() {
    override val factory = LogCrashEngineFactory::class

    override fun verifyFiles(dataDir: Path, context: String) {
        assertEquals(emptyList(), SegmentFiles.listMerges(dataDir), "재시작 후 merge 파일이 남았습니다 $context")
    }
}

/** 세그먼트를 4 KiB로 작게 잡아 rollover 도중 크래시도 포함한다. 유지 작업 = merge. */
class LogCrashEngineFactory : CrashEngineFactory {
    override fun open(dataDir: Path, durable: Boolean): KvEngine = LogEngine.open(
        LogEngineOptions(dataDir, segmentSizeBytes = 4096, syncPolicy = if (durable) SyncPolicy.EVERY_WRITE else SyncPolicy.MANUAL),
    )

    override fun maintenance(engine: KvEngine) {
        (engine as LogEngine).merge()
    }
}
