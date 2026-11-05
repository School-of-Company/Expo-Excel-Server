package team.startup.report.global.swagger

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "swagger", name = ["enabled"], havingValue = "true")
class OpenApiConfig {
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
}
