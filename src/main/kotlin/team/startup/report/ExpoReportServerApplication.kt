package team.startup.report

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ExpoReportServerApplication

fun main(args: Array<String>) {
    runApplication<ExpoReportServerApplication>(*args)
}
