plugins {
    application
}

dependencies {
    "implementation"(project(":engine-log"))
    "implementation"(project(":engine-lsm"))
    "implementation"(project(":engine-btree"))
    "implementation"(project(":document"))
}

application {
    mainClass.set("dev.mydb.bench.MainKt")
    // 키 100만 개 keydir + 힙 측정 여유
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
