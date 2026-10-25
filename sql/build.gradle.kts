plugins {
    application
}

dependencies {
    // SQL 계층은 KvEngine 인터페이스에만 의존한다. REPL과 테스트에서 세 엔진을 꽂는다
    "api"(project(":kv-api"))
    "implementation"(project(":engine-log"))
    "implementation"(project(":engine-lsm"))
    "implementation"(project(":engine-btree"))
}

application {
    mainClass.set("dev.mydb.sql.ReplKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
