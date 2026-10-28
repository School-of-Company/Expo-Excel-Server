package team.startup.report.global.client

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import team.startup.report.global.client.application.ApplicationClient
import team.startup.report.global.client.attendance.AttendanceClient
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.form.FormClient
import team.startup.report.global.client.user.UserClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration

/**
 * 원천 공급자(User·Application·Expo) 내부 API 연결 설정. 주소와 내부 토큰은 기본값이 없고 비어 있으면 기동에 실패한다.
 * Attendance는 입장 기록 엑셀, Form은 공개 설문 응답 엑셀 전용 선택 설정이다(Attendance `INTERNAL_TOKEN`, Form `INTERNAL_TOKEN`).
 * 토큰은 각 공급자가 `X-Internal-Token`으로 검증하는 값과 같아야 한다(User `INTERNAL_TOKEN`, Application `APPLICATION_INTERNAL_TOKEN`,
 * Expo `EXPO_INTERNAL_TOKEN`).
 */
@ConfigurationProperties(prefix = "clients")
data class ReportClientsProperties(
    val user: Provider,
    val application: Provider,
    val expo: Provider,
    // 입장 기록 엑셀 전용 선택 설정. 주소·토큰이 모두 비어 있으면 그 다운로드만 503이고 나머지 엑셀과 기동에는 영향이 없다.
    val attendance: Provider = Provider("", ""),
    // 공개 설문 응답 엑셀 전용 선택 설정. 규칙은 attendance와 같다.
    val form: Provider = Provider("", ""),
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        mapOf("user" to user, "application" to application, "expo" to expo).forEach { (name, provider) ->
            require(provider.url.isNotBlank()) { "clients.$name.url이 비어 있습니다." }
            require(provider.internalToken.isNotBlank()) { "clients.$name.internal-token이 비어 있습니다." }
        }
        mapOf("attendance" to attendance, "form" to form).forEach { (name, provider) ->
            require(provider.url.isBlank() == provider.internalToken.isBlank()) {
                "clients.$name.url과 internal-token은 둘 다 설정하거나 둘 다 비워야 합니다."
            }
        }
    }

    data class Provider(
        val url: String,
        val internalToken: String,
    ) {
        // data class의 기본 toString은 토큰을 그대로 출력한다
        override fun toString() = "Provider(url=$url, internalToken=***)"

        val configured get() = url.isNotBlank() && internalToken.isNotBlank()
    }
}

@Configuration
@EnableConfigurationProperties(ReportClientsProperties::class)
class ReportClientsConfig(
    private val properties: ReportClientsProperties,
    private val jsonMapper: JsonMapper,
) {
    // 회로는 공급자마다 하나다(각 클라이언트가 자기 ClientResilience를 가진다)
    @Bean
    fun userClient() = UserClient(restClient(properties.user))

    @Bean
    fun applicationClient() = ApplicationClient(restClient(properties.application))

    @Bean
    fun expoClient() = ExpoClient(restClient(properties.expo))

    @Bean
    fun attendanceClient() = AttendanceClient(properties.attendance.takeIf { it.configured }?.let(::restClient))

    @Bean
    fun formClient() = FormClient(properties.form.takeIf { it.configured }?.let(::restClient))

    private fun restClient(provider: ReportClientsProperties.Provider) =
        reportRestClient(provider, properties.connectTimeout, properties.readTimeout, jsonMapper)
}

private const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"

/** 공급자별 클라이언트. 토큰은 그 공급자의 요청에만 실린다. */
fun reportRestClient(
    provider: ReportClientsProperties.Provider,
    connectTimeout: Duration,
    readTimeout: Duration,
    jsonMapper: JsonMapper,
): RestClient =
    RestClient
        .builder()
        .baseUrl(provider.url)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build())
                .apply { setReadTimeout(readTimeout) },
        ).configureMessageConverters { it.withJsonConverter(JacksonJsonHttpMessageConverter(jsonMapper)) }
        .defaultHeader(INTERNAL_TOKEN_HEADER, provider.internalToken)
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .build()

/**
 * 공급자 호출 결과를 읽는다. [notFoundAsNull]이면 404는 "대상 없음"(null)이고, 그 밖의 실패는 모두 예외다.
 * 실제 HTTP 호출만 [guard]로 감싼다(회로·재시도). 404 변환과 본문 형식 오류는 회로 밖에서 일어나 장애로 세지 않는다.
 * 예외 메시지는 엑셀 실패 응답에 나갈 수 있으므로 공급자 이름과 상태만 담는다. 원인(URL·응답 본문)은 붙이지 않는다.
 */
internal fun <T : Any> RestClient.RequestHeadersSpec<*>.fetch(
    guard: ClientResilience.Guard,
    type: ParameterizedTypeReference<T>,
    notFoundAsNull: Boolean = false,
): T? {
    val provider = guard.provider
    return try {
        guard.run { retrieve().body(type) } ?: error("$provider 서비스 응답 형식 오류")
    } catch (e: RestClientResponseException) {
        if (notFoundAsNull && e.statusCode.value() == 404) null else error("$provider 서비스 응답 오류(HTTP ${e.statusCode.value()})")
    } catch (e: ResourceAccessException) {
        error("$provider 서비스 연결 실패")
    } catch (e: RestClientException) {
        error("$provider 서비스 응답 형식 오류")
    }
}

internal inline fun <reified T : Any> RestClient.RequestHeadersSpec<*>.fetchOrNull(guard: ClientResilience.Guard): T? =
    fetch(guard, object : ParameterizedTypeReference<T>() {}, notFoundAsNull = true)

internal inline fun <reified T : Any> RestClient.RequestHeadersSpec<*>.fetchRequired(guard: ClientResilience.Guard): T =
    fetch(guard, object : ParameterizedTypeReference<T>() {})!!
