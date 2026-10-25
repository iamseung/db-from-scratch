dependencies {
    // BTreeEngine이 KvEngine을 구현하고 BTreeOptions가 SyncPolicy·RecoveryMode를 노출한다
    "api"(project(":kv-api"))
    "api"(project(":storage-common"))

    "testImplementation"(testFixtures(project(":kv-api")))
}
