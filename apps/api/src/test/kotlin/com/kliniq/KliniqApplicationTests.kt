package com.kliniq

import com.kliniq.support.TestcontainersConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class KliniqApplicationTests {
    @Test
    fun contextLoads() {
        // Verifies the Spring application context starts without errors against
        // real Postgres + Redis (provided by Testcontainers via @ServiceConnection).
    }
}
