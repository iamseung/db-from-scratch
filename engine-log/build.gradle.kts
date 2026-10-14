dependencies {
    // LogEngine이 KvEngine을 구현하고 LogEngineOptions가 SyncPolicy·RecoveryMode를 노출하므로
    // 사용하는 쪽에도 두 모듈의 타입이 보여야 한다
    "api"(project(":kv-api"))
    "api"(project(":storage-common"))

    "testImplementation"(testFixtures(project(":kv-api")))
}

// 크래시 테스트가 자식 JVM을 띄울 때 쓸 classpath. 테스트 워커의 java.class.path는 전체 classpath를 담지 않을 수 있다.
tasks.named<Test>("test") {
    val testRuntimeClasspath = project.the<SourceSetContainer>()["test"].runtimeClasspath
    inputs.files(testRuntimeClasspath)
    doFirst {
        systemProperty("crash.child.classpath", testRuntimeClasspath.asPath)
    }
}
