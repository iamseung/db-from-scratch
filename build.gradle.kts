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

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // KvEngineCrashTest가 자식 JVM을 띄울 때 쓸 classpath. 테스트 워커의 java.class.path는 전체 classpath를 담지 않을 수 있다.
    tasks.named<Test>("test") {
        val testRuntimeClasspath = project.the<SourceSetContainer>()["test"].runtimeClasspath
        inputs.files(testRuntimeClasspath)
        doFirst {
            systemProperty("crash.child.classpath", testRuntimeClasspath.asPath)
        }
    }
}
