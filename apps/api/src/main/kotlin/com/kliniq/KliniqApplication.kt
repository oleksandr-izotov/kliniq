package com.kliniq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan("com.kliniq")
@EnableScheduling // SseService.heartbeat keeps idle EventSource connections past proxy idle-kills
class KliniqApplication

// Spread is the canonical Kotlin idiom for forwarding main() args to runApplication.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<KliniqApplication>(*args)
}
