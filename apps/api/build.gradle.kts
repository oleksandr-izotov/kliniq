import nu.studer.gradle.jooq.JooqEdition
import org.jooq.meta.jaxb.Logging

// The Flyway Gradle plugin runs in the Gradle build classpath, separate from
// our application's runtime classpath. It needs the Postgres JDBC driver and
// the Postgres-specific Flyway adapter on its classpath here at buildscript
// scope, in addition to the runtime deps below.
buildscript {
    dependencies {
        classpath("org.postgresql:postgresql:42.7.9")
        classpath("org.flywaydb:flyway-database-postgresql:10.20.1")
    }
}

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.5.10"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    // Flyway and jOOQ work as a pair: Flyway applies migrations against a
    // running Postgres, jOOQ then introspects that schema to generate
    // type-safe Kotlin code.
    id("org.flywaydb.flyway") version "10.20.1"
    id("nu.studer.jooq") version "9.0"
    jacoco
}

group = "com.kliniq"
version = "0.0.1-SNAPSHOT"

// Database connection used for `flywayMigrate` and jOOQ codegen during the
// Gradle build. Defaults match the dev compose stack (host port 55432). CI
// overrides to the canonical 5432 with `-PdbPort=5432`.
val dbHost: String = (project.findProperty("dbHost") as String?) ?: "localhost"
val dbPort: String = (project.findProperty("dbPort") as String?) ?: "55432"
val dbName: String = (project.findProperty("dbName") as String?) ?: "kliniq"
val dbUser: String = (project.findProperty("dbUser") as String?) ?: "kliniq"
val dbPassword: String = (project.findProperty("dbPassword") as String?) ?: "kliniq_dev_only"
val dbUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Argon2id password hashing
    implementation("de.mkammerer:argon2-jvm:2.11")

    // WebAuthn / passkeys. webauthn4j-core gives us the registration and
    // authentication ceremony validators; we wire the cookie-session flow
    // around it ourselves rather than pulling in webauthn4j-spring-security.
    implementation("com.webauthn4j:webauthn4j-core:0.29.1.RELEASE")

    // Kotlin runtime support
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Persistence
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Sessions stored in Redis
    implementation("org.springframework.session:spring-session-data-redis")

    // UUID v7 — time-ordered, used everywhere as primary key
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // jOOQ codegen needs the JDBC driver on its own classpath at build time.
    jooqGenerator("org.postgresql:postgresql")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Emulated authenticator + ClientPlatform helpers for full WebAuthn
    // ceremonies in integration tests, no browser required.
    testImplementation("com.webauthn4j:webauthn4j-test:0.29.1.RELEASE")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.named<JacocoReport>("jacocoTestReport"))
}

// JaCoCo: aggregate coverage across the integration tests so we can
// answer DoD claims like "auth packages > 70% coverage" with a real
// number, not a vibe. Reports land in build/reports/jacoco/test/.
tasks.named<JacocoReport>("jacocoTestReport") {
    // generateJooq writes into build/classes paths that the report walks.
    dependsOn(tasks.named("test"), tasks.named("writeJooqEditorConfig"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // jOOQ generated classes track the schema 1:1 — measuring
                    // coverage on them tells us nothing about our own code.
                    exclude("com/kliniq/db/**")
                }
            },
        ),
    )
}

// `gradle bootRun` should execute with the monorepo root as the working
// directory so relative paths like "certs/localhost.p12" in application-local.yml
// resolve to Kliniq/certs/localhost.p12 regardless of where it's invoked from.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir.parentFile.parentFile
}

// -----------------------------------------------------------------------------
// Flyway plugin — applies migrations against the dev / CI database. Used only
// at build time (codegen). The runtime app uses Spring Boot's Flyway
// auto-configuration, which is independent of this plugin.
// -----------------------------------------------------------------------------
flyway {
    url = dbUrl
    user = dbUser
    password = dbPassword
    schemas = arrayOf("public")
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    cleanDisabled = false // CI may need a clean slate before regenerating
}

// -----------------------------------------------------------------------------
// jOOQ codegen — introspects the migrated database and produces type-safe
// Kotlin classes under com.kliniq.db. The generated source lives in
// build/generated-sources/jooq/ (gitignored) so it always tracks the schema.
// -----------------------------------------------------------------------------
jooq {
    // Pinned to the version Spring Boot 3.5.x manages — keeping codegen and
    // runtime jOOQ in sync prevents NoSuchMethodError at codegen time.
    version.set("3.19.29")
    edition.set(JooqEdition.OSS)

    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false)

            jooqConfiguration.apply {
                logging = Logging.WARN
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = dbUrl
                    user = dbUser
                    password = dbPassword
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        // Skip Flyway/Spring-Session bookkeeping plus the
                        // pgcrypto/btree_gist helper tables that the extensions
                        // expose. We don't query them from application code.
                        excludes = "flyway_schema_history|schema_marker|spring_session.*|pgp_armor_headers"
                    }
                    target.apply {
                        packageName = "com.kliniq.db"
                        directory = "build/generated-sources/jooq"
                    }
                    generate.apply {
                        isPojos = false
                        isRecords = true
                        isImmutablePojos = false
                        isJavaTimeTypes = true
                        isFluentSetters = false
                        isDeprecated = false
                        // Skip pgcrypto / btree_gist function bindings — we
                        // never call those SQL functions from Kotlin.
                        isRoutines = false
                        isUdts = false
                        isSequences = false
                    }
                }
            }
        }
    }
}

// Every codegen run starts from a fresh, fully-migrated schema: clean wipes
// the public schema, migrate re-applies V1+V2+..., then jOOQ introspects.
// Without this dependency chain you get stale generated code when migrations
// are added or modified.
tasks.named("generateJooq") {
    dependsOn("flywayMigrate")
}
tasks.named("flywayMigrate") {
    mustRunAfter("flywayClean")
}

// Compiling Kotlin requires the generated jOOQ classes — chain it in.
tasks.named("compileKotlin") {
    dependsOn("generateJooq")
}

ktlint {
    version.set("1.4.1")
}

// jOOQ-generated Kotlin doesn't follow ktlint conventions and is regenerated
// every build, so disable ktlint inside its output dir via a marker
// .editorconfig. ktlint discovers it automatically when walking up from each
// file; `ktlint = disabled` short-circuits all standard rules for that dir.
val writeJooqEditorConfig =
    tasks.register("writeJooqEditorConfig") {
        val targetDir = layout.buildDirectory.dir("generated-sources/jooq")
        outputs.file(targetDir.map { it.file(".editorconfig") })
        doLast {
            val dir = targetDir.get().asFile
            dir.mkdirs()
            dir.resolve(".editorconfig").writeText(
                """
                root = false

                [*]
                ktlint = disabled
                """.trimIndent() + "\n",
            )
        }
    }
tasks.named("generateJooq") {
    finalizedBy(writeJooqEditorConfig)
}

// ktlint walks every file under the source set, including the generated
// jOOQ tree. Without an explicit dependency Gradle 8.x flags the implicit
// ordering as a validation error when both run in the same invocation
// (`./gradlew check`). Tying ktlint's main task to generateJooq matches
// the real-world expectation: codegen must run first.
tasks
    .matching { it.name in setOf("runKtlintCheckOverMainSourceSet", "runKtlintFormatOverMainSourceSet") }
    .configureEach { dependsOn("generateJooq", "writeJooqEditorConfig") }

detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
}

// Exclude generated jOOQ sources from detekt analysis too.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/generated-sources/**", "**/build/**")
}

// detekt 1.23.x ships with Kotlin 2.0.x bundled and refuses to run when the
// project Kotlin compiler is newer. Force its internal dependencies onto the
// version detekt was compiled against — official workaround per
// https://detekt.dev/docs/gettingstarted/gradle#dependencies until detekt 2.x.
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.21")
        }
    }
}
