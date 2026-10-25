package dev.mydb.engine.btree

import dev.mydb.kv.KvEngine
import dev.mydb.kv.KvEngineContractTest
import dev.mydb.kv.KvEngineRandomTest
import dev.mydb.kv.crash.CrashEngineFactory
import dev.mydb.kv.crash.KvEngineCrashTest
import dev.mydb.storage.SyncPolicy
import java.nio.file.Path

/** 페이지 512 B·버퍼 풀 16장이라 split과 체크포인트가 쉴 새 없이 일어난다. */
internal fun tinyOptions(dir: Path) = BTreeOptions(dir, pageSize = 512, bufferPoolPages = 16)

class BTreeEngineContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(BTreeOptions(dir))
}

class BTreeEngineTinyContractTest : KvEngineContractTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(tinyOptions(dir))
}

class BTreeEngineRandomTest : KvEngineRandomTest() {
    override fun openEngine(dir: Path): KvEngine = BTreeEngine.open(tinyOptions(dir).copy(syncPolicy = SyncPolicy.MANUAL))

    /** 체크포인트와 함께 트리 불변식을 매번 검사한다. */
    override fun maintenance(engine: KvEngine) {
        (engine as BTreeEngine).verify()
    }
}

class BTreeEngineCrashTest : KvEngineCrashTest() {
    override val factory = BTreeCrashEngineFactory::class
}

/** 키 14 B + 값 100 B = 114 B ≤ 512 B 페이지의 셀 상한 120 B. 유지 작업 = 체크포인트. */
class BTreeCrashEngineFactory : CrashEngineFactory {
    override fun open(dataDir: Path, durable: Boolean): KvEngine = BTreeEngine.open(
        BTreeOptions(dataDir, pageSize = 512, bufferPoolPages = 32, syncPolicy = if (durable) SyncPolicy.EVERY_WRITE else SyncPolicy.MANUAL),
    )

    override fun maintenance(engine: KvEngine) {
        (engine as BTreeEngine).checkpoint()
    }
}
