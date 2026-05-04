package com.kliniq.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Spins up Postgres and Redis containers and lets Spring's [ServiceConnection]
 * mechanism wire the datasource and Redis beans automatically. Import this
 * config from any `@SpringBootTest` that needs a real backing store —
 * Spring's TestcontainersLifecycleApplicationContextInitializer reuses the
 * containers across tests in the same JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("kliniq")
            .withUsername("kliniq")
            .withPassword("kliniq_dev_only")

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(REDIS_PORT)

    companion object {
        private const val REDIS_PORT = 6379
    }
}
