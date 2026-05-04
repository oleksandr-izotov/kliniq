package com.kliniq.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AppBeans {
    /**
     * Single source of "now" so tests can substitute a fixed clock. Use
     * UTC: timestamps in the DB are TIMESTAMPTZ and the API talks UTC
     * regardless of the user's locale.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
