plugins {
    application
}

dependencies {
    "implementation"(project(":engine-log"))
}

application {
    mainClass.set("dev.mydb.bench.LogEngineBenchKt")
    // 키 100만 개 keydir + 힙 측정 여유
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
