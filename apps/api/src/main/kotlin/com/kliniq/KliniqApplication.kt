package com.kliniq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KliniqApplication

fun main(args: Array<String>) {
    runApplication<KliniqApplication>(*args)
}
