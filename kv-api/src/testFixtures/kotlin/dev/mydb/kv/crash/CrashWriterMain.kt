package dev.mydb.kv.crash

import dev.mydb.kv.WriteOp
import java.nio.file.Path

/**
 * 크래시 테스트의 자식 JVM 진입점.
 * 쓰기를 끝없이 반복하고, 쓰기가 반환될 때마다 "ACK i"를 출력한다. 부모가 SIGKILL로 죽일 때까지 멈추지 않는다.
 *
 * args: factoryClassName, dataDir, durable(true|false), mode(CrashMode)
 */
fun main(args: Array<String>) {
    val (factoryClassName, dataDir, durable, mode) = args
    val factory = Class.forName(factoryClassName).getDeclaredConstructor().newInstance() as CrashEngineFactory
    val engine = factory.open(Path.of(dataDir), durable.toBooleanStrict())
    val crashMode = CrashMode.valueOf(mode)
    var i = 0L
    while (true) {
        when (crashMode) {
            CrashMode.SINGLE -> engine.put(CrashWorkload.key(i), CrashWorkload.value(i))
            CrashMode.BATCH -> engine.writeBatch(
                CrashWorkload.batchKeys(i).map { k -> WriteOp.Put(CrashWorkload.key(k), CrashWorkload.value(k)) },
            )
            CrashMode.MAINTENANCE -> engine.put(CrashWorkload.key(i), CrashWorkload.value(i))
        }
        println("ACK $i")
        if (crashMode == CrashMode.MAINTENANCE && i % CrashWorkload.MAINTENANCE_EVERY == CrashWorkload.MAINTENANCE_EVERY - 1) {
            println(CrashWorkload.MAINTENANCE_SIGNAL)
            System.out.flush()
            factory.maintenance(engine)
        }
        System.out.flush()
        i++
    }
}
