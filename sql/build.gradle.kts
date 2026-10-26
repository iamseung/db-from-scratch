plugins {
    application
}

dependencies {
    // SQL 계층은 KvEngine 인터페이스에만 의존한다. REPL과 테스트에서 세 엔진을 꽂는다
    "api"(project(":kv-api"))
    // BEGIN … COMMIT: M7 MVCC 트랜잭션 위에서 문장을 실행한다. Isolation이 공개 API(Statement.Begin, Database)에 나오므로 api
    "api"(project(":txn"))
    "implementation"(project(":engine-log"))
    "implementation"(project(":engine-lsm"))
    "implementation"(project(":engine-btree"))

    // M8 D5: 관계형과 문서형의 일관성 경계 비교 테스트
    "testImplementation"(project(":document"))
}

application {
    mainClass.set("dev.mydb.sql.ReplKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
