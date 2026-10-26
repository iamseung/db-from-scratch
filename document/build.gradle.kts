dependencies {
    // 문서 계층은 KvEngine 인터페이스에만 의존한다. 저장 엔진은 테스트에서 셋 다 꽂아 본다
    "api"(project(":kv-api"))
    // 다중 문서 트랜잭션: M7 MVCC 위에서 실행한다. WriteConflictException이 공개 API에 나오므로 api
    "api"(project(":txn"))

    "testImplementation"(project(":engine-log"))
    "testImplementation"(project(":engine-lsm"))
    "testImplementation"(project(":engine-btree"))
}
