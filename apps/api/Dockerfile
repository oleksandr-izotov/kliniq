# syntax=docker/dockerfile:1.6
#
# Spring Boot production image. Single-stage runtime — the JAR is built
# OUTSIDE this Dockerfile by Gradle (which needs a real Postgres for jOOQ
# codegen and Flyway migrations during compile). The build chain is:
#
#   ./gradlew :bootJar          (locally or in CI, with a Postgres reachable)
#   docker build -t kliniq-api -f apps/api/Dockerfile apps/api/
#
# This split keeps the runtime image small (a JRE + the JAR) and dodges
# the standard JVM-monorepo trap of trying to spin up Postgres inside a
# Docker layer.
#
# In Coolify / GitHub Actions the build sequence runs in a CI job that
# has a Postgres service container; the image is then pushed to GHCR
# and Coolify pulls + runs it.
#
# TLS terminates at Coolify's Caddy proxy; the JVM listens on plain HTTP.

# Jammy (Ubuntu 22.04) over Alpine: glibc, not musl. Our Argon2 password
# hashing uses argon2-jvm which loads native libs through JNA, and JNA's
# bundled native binaries expect glibc on Linux. On Alpine + musl, the
# JVM SIGSEGVs at startup the moment Spring's PasswordEncoder bean
# initializes. The Jammy image is ~80 MB heavier than alpine but is
# the path of least surprise for a glibc-linked native dep.
FROM eclipse-temurin:21-jre-jammy

# wget is shipped in jammy. Non-root user — JVM never writes outside /tmp.
RUN groupadd -r kliniq && useradd -r -g kliniq kliniq

WORKDIR /app

# The JAR is the only artifact we ship. It must be built first
# (./gradlew :bootJar in apps/api). The wildcard tolerates the version
# suffix in the JAR name without pinning Gradle's output filename.
COPY --chown=kliniq:kliniq build/libs/*.jar /app/kliniq-api.jar

USER kliniq

EXPOSE 8080

# SPRING_PROFILES_ACTIVE=prod is the contract; set in Coolify env vars
# but defaulted here so a forgotten override doesn't accidentally boot
# the local profile (which expects mkcert certs on disk).
ENV SPRING_PROFILES_ACTIVE=prod

# Heap sizing: cap at 75% of cgroup-detected RAM. CPX21 has 4 GB; with
# Postgres + Redis + SvelteKit also on the box, ~1.5-2 GB heap is the
# right shape. The JVM honours cgroup limits when running as PID 1
# under containerd / Docker since Java 11.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/kliniq-api.jar"]
