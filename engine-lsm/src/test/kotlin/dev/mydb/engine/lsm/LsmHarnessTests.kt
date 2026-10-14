package dev.mydb.engine.lsm

import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEngineContractTest
import dev.mydb.kv.KvEngineRandomTest
import dev.mydb.kv.crash.CrashEngineFactory
import dev.mydb.kv.crash.KvEngineCrashTest
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

/** memtable 256 B·블록 64 B·임계값 2라 쓰기 몇 번마다 flush와 compaction이 일어난다. */
internal fun tinyOptions(dir: Path) =
    LsmOptions(dir, memtableSizeBytes = 256, blockSizeBytes = 64, compactionMinThreshold = 2, compactionMaxThreshold = 4)

class LsmEngineContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(LsmOptions(dir))
}

/** flush와 compaction이 쉴 새 없이 일어나는 설정에서도 의미가 같아야 한다. */
class LsmEngineTinyContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = LsmEngine.open(tinyOptions(dir))
}

/**
 * memtable 256 B면 3,000번 연산에 flush가 수백 번이고, flush마다 SSTable·MANIFEST fsync(macOS에서 각 약 4 ms)가 들어
 * 77초가 걸렸다. 1 KiB로 늘려도 flush·compaction은 충분히 자주 일어난다.
 */
class LsmEngineRandomTest : KvEngineRandomTest() {
    override fun openEngine(dir: Path): KvEngine =
        LsmEngine.open(tinyOptions(dir).copy(memtableSizeBytes = 1024, syncPolicy = SyncPolicy.MANUAL))

    override fun maintenance(engine: KvEngine) {
        (engine as LsmEngine).compactAll()
    }
}

class LsmEngineCrashTest : KvEngineCrashTest() {
    override val factory = LsmCrashEngineFactory::class
}

/** memtable 2 KiB라 단일 put 모드에서도 flush·compaction이 자주 일어난다. 유지 작업 = compactAll. */
class LsmCrashEngineFactory : CrashEngineFactory {
    override fun open(dataDir: Path, durable: Boolean): KvEngine = LsmEngine.open(
        LsmOptions(
            dataDir,
            memtableSizeBytes = 2048,
            blockSizeBytes = 256,
            compactionMinThreshold = 2,
            syncPolicy = if (durable) SyncPolicy.EVERY_WRITE else SyncPolicy.MANUAL,
        ),
    )

    override fun maintenance(engine: KvEngine) {
        (engine as LsmEngine).compactAll()
    }
}
