dependencies {
    // 문서 계층은 KvEngine 인터페이스에만 의존한다. 저장 엔진은 테스트에서 셋 다 꽂아 본다
    "api"(project(":kv-api"))

    "testImplementation"(project(":engine-log"))
    "testImplementation"(project(":engine-lsm"))
    "testImplementation"(project(":engine-btree"))
}
