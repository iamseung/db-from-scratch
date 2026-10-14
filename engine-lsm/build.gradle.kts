dependencies {
    // LsmEngine이 KvEngine을 구현하고 LsmOptions가 SyncPolicy·RecoveryMode를 노출한다
    "api"(project(":kv-api"))
    "api"(project(":storage-common"))

    "testImplementation"(testFixtures(project(":kv-api")))
}
