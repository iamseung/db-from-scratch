plugins {
    `java-test-fixtures`
}

dependencies {
    // KvEngineContractTest: 모든 엔진이 상속해 같은 의미를 갖는지 검증한다
    testFixturesImplementation(platform("org.junit:junit-bom:5.11.4"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    testFixturesImplementation(kotlin("test-junit5"))
}
