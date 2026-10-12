package team.startup.report.global.swagger

import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "swagger", name = ["enabled"], havingValue = "true")
class OpenApiConfig(
    private val properties: SwaggerProperties,
) {
    @Bean
    fun reportOpenApi(): OpenAPI =
        OpenAPI()
            .servers(listOf(Server().url("/")))
            .info(
                Info()
                    .title("Expo Report Server API")
                    .version("1")
                    .description(
                        "기존 XLSX 4종과 UUID 신청 선택·필터 전체 다운로드 API. 관리자 RS256 JWT가 필요하며 개인 출석부는 실제 출석 결과가 아닌 작성용 양식입니다. 오류는 status/message 형식입니다.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    "bearerAuth",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("User 서비스가 발급한 ROLE_ADMIN 액세스 토큰"),
                ),
            )

    @Bean
    fun reportOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            val components = api.components ?: Components().also { api.components = it }
            if (properties.includeInternal) {
                components.addSecuritySchemes(
                    "internalToken",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-Internal-Token")
                        .description("서비스 간 호출 토큰. 실제 값을 예시에 넣지 않습니다."),
                )
                api.paths?.filterKeys(::internalPath)?.values?.forEach { path ->
                    path.readOperations().forEach { it.security = listOf(SecurityRequirement().addList("internalToken")) }
                }
            } else {
                api.paths?.keys?.removeIf(::internalPath)
                // 제거된 내부 경로에서만 쓰던 스키마도 없앤다. 공용 스키마의 중첩 참조는 남긴다.
                val schemas = components.schemas
                if (schemas != null) {
                    val mapper = Json.mapper()
                    val reachable = mutableSetOf<String>()
                    val pending = ArrayDeque(references(mapper.writeValueAsString(api.paths)))
                    while (pending.isNotEmpty()) {
                        val name = pending.removeFirst()
                        if (reachable.add(name)) schemas[name]?.let { pending += references(mapper.writeValueAsString(it)) }
                    }
                    schemas.keys.retainAll(reachable)
                }
            }
        }

    private fun internalPath(path: String) = path == "/internal" || path.startsWith("/internal/")

    private fun references(json: String) = SCHEMA_REF.findAll(json).map { it.groupValues[1] }.toList()

    private companion object {
        val SCHEMA_REF = Regex("#/components/schemas/([^\"]+)\"")
    }
}
