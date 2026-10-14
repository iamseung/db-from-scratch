plugins {
    kotlin("jvm") version "2.2.20" apply false
}

allprojects {
    group = "dev.mydb"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        testImplementation(platform("org.junit:junit-bom:5.11.4"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation(kotlin("test"))
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    // 크래시·무작위 테스트는 @Tag("slow")라 기본 test에서 빼고 slowTest로 따로 돌린다 (합쳐서 수 분).
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("slow") }
    }
    val sourceSets = project.the<SourceSetContainer>()
    tasks.register<Test>("slowTest") {
        description = "크래시(kill -9)·모델 기반 무작위 테스트"
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("slow") }
    }

    // KvEngineCrashTest가 자식 JVM을 띄울 때 쓸 classpath. 테스트 워커의 java.class.path는 전체 classpath를 담지 않을 수 있다.
    tasks.withType<Test>().configureEach {
        val testRuntimeClasspath = sourceSets["test"].runtimeClasspath
        inputs.files(testRuntimeClasspath)
        doFirst {
            systemProperty("crash.child.classpath", testRuntimeClasspath.asPath)
        }
    }
}
