plugins {
    application
}

dependencies {
    "implementation"(project(":engine-log"))
    "implementation"(project(":engine-lsm"))
}

application {
    mainClass.set("dev.mydb.bench.MainKt")
    // 키 100만 개 keydir + 힙 측정 여유
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
