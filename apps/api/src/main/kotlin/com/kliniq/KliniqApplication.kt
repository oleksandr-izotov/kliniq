package com.kliniq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KliniqApplication

// Spread is the canonical Kotlin idiom for forwarding main() args to runApplication.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<KliniqApplication>(*args)
}
