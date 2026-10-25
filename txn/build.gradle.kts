dependencies {
    // MVCC 계층은 KvEngine 인터페이스에만 의존한다. 테스트에서 세 엔진 위에 얹어 본다
    "api"(project(":kv-api"))

    "testImplementation"(testFixtures(project(":kv-api")))
    "testImplementation"(project(":engine-log"))
    "testImplementation"(project(":engine-lsm"))
    "testImplementation"(project(":engine-btree"))
}
