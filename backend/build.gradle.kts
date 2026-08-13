plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "com.miriyum"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.github.openfeign.querydsl:querydsl-jpa:7.5")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("com.ibm.icu:icu4j:78.3")
    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("com.fasterxml.jackson.core:jackson-databind")

    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    compileOnly("org.projectlombok:lombok")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:7.5:jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testRuntimeOnly("com.h2database:h2")
    testCompileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("miriyum.menu.schedule.enabled", "false")
    systemProperty("miriyum.reservation.time-policy.activation-enabled", "false")
    systemProperty("miriyum.store.schedule.activation-enabled", "false")
}

val integrationTag = "integration"
val integrationShardATag = "integration-shard-a"
val integrationShardBTag = "integration-shard-b"
val integrationShardCTag = "integration-shard-c"
val integrationShardDTag = "integration-shard-d"
val integrationShardTags = listOf(
    integrationShardATag,
    integrationShardBTag,
    integrationShardCTag,
    integrationShardDTag,
)

val verifyIntegrationTestTags = tasks.register("verifyIntegrationTestTags") {
    group = "verification"
    description = "통합 테스트가 integration 태그와 정확히 하나의 CI shard 태그를 갖는지 확인합니다."

    doLast {
        val candidates = fileTree("src/test/java") {
            include("**/*.java")
        }.files.filter { file ->
            val source = file.readText()
            source.contains("@Testcontainers")
                || source.contains("MySQLContainer")
                || source.contains("@SpringBootTest")
                || source.contains("@Tag(\"$integrationTag\")")
        }
        val missingIntegrationTags = candidates.filterNot { file ->
            file.readText().contains("@Tag(\"$integrationTag\")")
        }
        val invalidShardTags = candidates.filter { file ->
            val source = file.readText()
            integrationShardTags.count { tag -> source.contains("@Tag(\"$tag\")") } != 1
        }

        check(missingIntegrationTags.isEmpty()) {
            "Integration test tag is missing: ${missingIntegrationTags.joinToString { it.relativeTo(projectDir).path }}"
        }
        check(invalidShardTags.isEmpty()) {
            "Integration test must have exactly one shard tag: ${invalidShardTags.joinToString { it.relativeTo(projectDir).path }}"
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags(integrationTag)
    }
    dependsOn(verifyIntegrationTestTags)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Testcontainers 및 Spring 통합 테스트를 실행합니다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags(integrationTag)
    }
    dependsOn(verifyIntegrationTestTags)
}

fun registerIntegrationTestShard(taskName: String, shardTag: String) = tasks.register<Test>(taskName) {
    group = "verification"
    description = "${shardTag}에 분류된 Testcontainers 및 Spring 통합 테스트를 실행합니다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags(shardTag)
    }
    dependsOn(verifyIntegrationTestTags)
}

val integrationTestShardA = registerIntegrationTestShard("integrationTestShardA", integrationShardATag)
val integrationTestShardB = registerIntegrationTestShard("integrationTestShardB", integrationShardBTag)
val integrationTestShardC = registerIntegrationTestShard("integrationTestShardC", integrationShardCTag)
val integrationTestShardD = registerIntegrationTestShard("integrationTestShardD", integrationShardDTag)

tasks.check {
    dependsOn(integrationTest)
}
