dependencies {
    // LogEngine이 KvEngine을 구현하고 LogEngineOptions가 SyncPolicy·RecoveryMode를 노출하므로
    // 사용하는 쪽에도 두 모듈의 타입이 보여야 한다
    "api"(project(":kv-api"))
    "api"(project(":storage-common"))

    "testImplementation"(testFixtures(project(":kv-api")))
}

