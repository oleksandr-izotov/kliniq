package com.kliniq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan("com.kliniq")
class KliniqApplication

// Spread is the canonical Kotlin idiom for forwarding main() args to runApplication.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<KliniqApplication>(*args)
}
