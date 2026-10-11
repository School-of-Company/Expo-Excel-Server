package team.startup.report.global.swagger

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "swagger")
data class SwaggerProperties(
    val enabled: Boolean = false,
    val includeInternal: Boolean = false,
)

@Configuration
@EnableConfigurationProperties(SwaggerProperties::class)
class SwaggerConfiguration
