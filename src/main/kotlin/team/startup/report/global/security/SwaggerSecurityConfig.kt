package team.startup.report.global.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import team.startup.report.global.exception.ErrorResponse
import team.startup.report.global.swagger.SwaggerProperties
import tools.jackson.databind.json.JsonMapper

@Configuration
class SwaggerSecurityConfig(
    private val properties: SwaggerProperties,
    private val jsonMapper: JsonMapper,
) {
    @Bean
    @Order(2)
    fun swaggerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(*PATHS)
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 403
                    response.characterEncoding = "UTF-8"
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    jsonMapper.writeValue(response.writer, ErrorResponse(403, "접근 권한이 없습니다."))
                }
            }.authorizeHttpRequests {
                if (properties.enabled) it.requestMatchers(HttpMethod.GET, *PATHS).permitAll()
                it.anyRequest().denyAll()
            }
        return http.build()
    }

    private companion object {
        val PATHS = arrayOf("/stat/v3/api-docs", "/stat/v3/api-docs/**", "/stat/swagger-ui.html", "/stat/swagger-ui/**")
    }
}
