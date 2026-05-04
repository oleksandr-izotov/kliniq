// foojay-resolver-convention auto-downloads the JDK declared in
// gradle/gradle-daemon-jvm.properties (and in any toolchain { ... } block)
// from the foojay disco.com registry. Without it Gradle errors out when the
// requested JDK isn't installed locally.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "kliniq-api"
