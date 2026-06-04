package dev.ehr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EhrCoreApplication

fun main(args: Array<String>) {
	runApplication<EhrCoreApplication>(*args)
}
