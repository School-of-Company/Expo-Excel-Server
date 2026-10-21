package team.startup.report.global.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import team.startup.report.global.exception.ErrorResponse
import tools.jackson.databind.json.JsonMapper
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * /excel 하위 경로는 v1처럼 관리자(ROLE_ADMIN)만 허용한다. 검증 방식은 Expo-Expo-Server와 같다(RS256 공개키, role 클레임).
 * Gateway는 서명만 확인하고 역할은 보지 않으며, X-User-Id 같은 헤더는 신뢰하지 않는다.
 */
@Configuration
class ExcelSecurityConfig(
    @Value("\${JWT_PUBLIC_KEY}") private val publicKeyPem: String,
    private val jsonMapper: JsonMapper,
) {
    init {
        require(publicKeyPem.isNotBlank()) {
            "JWT_PUBLIC_KEY가 비어 있습니다. Expo-User-Server JWT_PRIVATE_KEY와 짝인 RS256 공개키(SPKI PEM)를 설정하세요."
        }
    }

    @Bean
    @Order(1)
    fun excelSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // JwtDecoder를 빈으로 두지 않는다. 빈이면 Boot의 로컬 기본 사용자 설정이 꺼진다.
        val converter =
            JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter { jwt ->
                    listOf(SimpleGrantedAuthority(requireNotNull(jwt.getClaimAsString(ROLE_CLAIM))))
                }
            }
        http
            .securityMatcher("/excel/**")
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it
                    .authenticationEntryPoint { _, res, _ -> res.writeError(401, "인증이 필요합니다.") }
                    .accessDeniedHandler { _, res, _ -> res.writeError(403, "접근 권한이 없습니다.") }
            }.authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.POST, "/excel/expos/{expoId}/downloads")
                    .hasAuthority(ADMIN_AUTHORITY)
                    .requestMatchers(
                        HttpMethod.GET,
                        "/excel/{expo_id}",
                        "/excel/standard/{expo_id}",
                        "/excel/program/{expo_id}",
                        "/excel/trainee/{trainee_id}",
                        "/excel/entry-records/{expo_id}",
                        "/excel/public-surveys/{expo_id}",
                        "/excel/expos/{expoId}/program-attendances",
                    ).hasAuthority(ADMIN_AUTHORITY)
                    .anyRequest()
                    .denyAll()
            }.oauth2ResourceServer {
                it.jwt { jwt -> jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(converter) }
                it.authenticationEntryPoint { _, res, _ -> res.writeError(401, "인증이 필요합니다.") }
            }
        return http.build()
    }

    private fun jwtDecoder(): JwtDecoder {
        val pem =
            publicKeyPem
                .replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
        val key =
            KeyFactory
                .getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(pem))) as RSAPublicKey
        return NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefault(), OAuth2TokenValidator(::validateClaims)))
        }
    }

    private fun validateClaims(jwt: Jwt): OAuth2TokenValidatorResult {
        val issuedAt = jwt.issuedAt
        val expiresAt = jwt.expiresAt
        val now = Instant.now()
        val valid =
            jwt.subject?.toLongOrNull() != null &&
                issuedAt != null &&
                expiresAt != null &&
                Duration.between(issuedAt, expiresAt) > Duration.ZERO &&
                Duration.between(issuedAt, expiresAt) <= MAX_TOKEN_LIFETIME &&
                !issuedAt.isAfter(now.plusSeconds(30)) &&
                expiresAt.isAfter(now) &&
                jwt.getClaim<Any>(ROLE_CLAIM) is String
        return if (valid) OAuth2TokenValidatorResult.success() else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
    }

    private fun HttpServletResponse.writeError(
        status: Int,
        message: String,
    ) {
        this.status = status
        characterEncoding = "UTF-8"
        contentType = MediaType.APPLICATION_JSON_VALUE
        jsonMapper.writeValue(writer, ErrorResponse(status, message))
    }

    private companion object {
        const val ROLE_CLAIM = "role"
        const val ADMIN_AUTHORITY = "ROLE_ADMIN"
        val MAX_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
