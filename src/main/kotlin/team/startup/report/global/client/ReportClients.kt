package team.startup.report.global.client

import feign.Client
import feign.FeignException
import feign.Request
import feign.Response
import feign.RetryableException
import feign.Retryer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.openfeign.FeignClientBuilder
import org.springframework.cloud.openfeign.FeignClientProperties
import org.springframework.cloud.openfeign.support.SpringMvcContract
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.util.UriUtils
import team.startup.report.global.client.application.ApplicationClient
import team.startup.report.global.client.attendance.AttendanceClient
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.form.FormClient
import team.startup.report.global.client.user.UserClient
import java.net.URI
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
    private val context: ApplicationContext,
) {
    // 회로는 공급자마다 하나다(각 클라이언트가 자기 ClientResilience를 가진다)
    @Bean
    fun userClient() = UserClient(feign(UserClient.NAME, properties.user))

    @Bean
    fun applicationClient() = ApplicationClient(feign(ApplicationClient.NAME, properties.application))

    @Bean
    fun expoClient() = ExpoClient(feign(ExpoClient.NAME, properties.expo))

    @Bean
    fun attendanceClient() = AttendanceClient(properties.attendance.takeIf { it.configured }?.let { feign(AttendanceClient.NAME, it) })

    @Bean
    fun formClient() = FormClient(properties.form.takeIf { it.configured }?.let { feign(FormClient.NAME, it) })

    private inline fun <reified T : Any> feign(
        name: String,
        provider: ReportClientsProperties.Provider,
    ): T = reportFeignClient(context, T::class.java, name, provider, properties.connectTimeout, properties.readTimeout)
}

private const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"

// 기본 계약은 인코딩된 '/'(%2F)를 '/'로 되돌린다. 경로 변수·쿼리 값의 '/'를 인코딩된 채로 보낸다.
// 날짜 인자는 ISO(yyyy-MM-dd)로 보낸다(기본 형식 변환은 지역 형식이다)
private fun contract() =
    SpringMvcContract(
        emptyList(),
        DefaultFormattingConversionService(
            false,
        ).also { DateTimeFormatterRegistrar().apply { setUseIsoFormat(true) }.registerFormatters(it) },
        FeignClientProperties().apply { isDecodeSlash = false },
    )

/**
 * 공급자가 준 불투명 커서를 쿼리에 넣기 전에 한 번 전부 인코딩한다(기존 RestClient의 URI 변수 인코딩과 같다).
 * Feign은 쿼리의 '/'를 인코딩하지 않고, '%XX'가 있으면 이미 인코딩된 값으로 보고 그대로 둔다.
 */
internal fun encodedCursor(cursor: String): String = UriUtils.encode(cursor, Charsets.UTF_8)

/**
 * 공급자별 Feign 클라이언트. `@FeignClient` 스캔 대신 여기서 만들어 선택 공급자는 설정이 없으면 만들지 않는다(null).
 * 토큰 인터셉터·타임아웃·재시도 끔·상태 코드 디코더는 이 공급자에만 적용된다. 회로와 재시도는 [ClientResilience]만 맡는다.
 */
fun <T : Any> reportFeignClient(
    context: ApplicationContext,
    type: Class<T>,
    name: String,
    provider: ReportClientsProperties.Provider,
    connectTimeout: Duration,
    readTimeout: Duration,
): T =
    FeignClientBuilder(context)
        .forType(type, name.lowercase())
        .url(provider.url)
        .customize { builder ->
            builder
                .contract(contract())
                .client(JdkClient(connectTimeout, readTimeout))
                .retryer(Retryer.NEVER_RETRY)
                // 기본 FeignException 메시지에는 URL·응답 본문이 들어간다
                .errorDecoder { _, response -> ProviderStatusException(response.status()) }
                .requestInterceptor {
                    // 토큰에 '{'가 있어도 템플릿으로 해석하지 않는다
                    it.headerLiteral(INTERNAL_TOKEN_HEADER, provider.internalToken)
                    it.header("Accept", MediaType.APPLICATION_JSON_VALUE)
                }
        }.build()

/**
 * 기존 RestClient와 같은 Spring JDK 요청 팩토리로 보낸다. 타임아웃이 HttpTimeoutException으로 와서 [ClientResilience] 판정 기준과 맞고,
 * 리다이렉트는 따라가지 않는다. feign-java11 클라이언트는 읽기 타임아웃을 응답 헤더까지만 적용해 본문이 멈추면 끝없이 기다린다.
 */
private class JdkClient(
    connectTimeout: Duration,
    readTimeout: Duration,
) : Client {
    private val factory =
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build()).apply { setReadTimeout(readTimeout) }

    override fun execute(
        request: Request,
        options: Request.Options,
    ): Response {
        val outgoing = factory.createRequest(URI.create(request.url()), HttpMethod.valueOf(request.httpMethod().name))
        request.headers().forEach { (name, values) -> outgoing.headers.addAll(name, values.toList()) }
        request.body()?.let { outgoing.body.write(it) }
        val response = outgoing.execute()
        return try {
            Response
                .builder()
                .request(request)
                .status(response.statusCode.value())
                .reason(response.statusText)
                .headers(buildMap { response.headers.forEach { name, values -> put(name, values) } })
                // 본문 스트림을 닫으면 타임아웃 감시도 끝난다(Feign이 디코딩 후 닫는다)
                .body(
                    response.body,
                    response.headers.contentLength
                        .takeIf { it in 0..Int.MAX_VALUE }
                        ?.toInt(),
                ).build()
        } catch (e: Exception) {
            // Feign에 넘기기 전에 실패하면 닫을 주체가 없어 연결이 남는다
            response.close()
            throw e
        }
    }
}

/** 공급자가 2xx가 아닌 상태로 응답했다. 메시지에는 상태만 담는다. */
class ProviderStatusException(
    val status: Int,
) : RuntimeException("HTTP $status")

/**
 * 공급자 호출 결과를 읽는다. [notFoundAsNull]이면 404는 "대상 없음"(null)이고, 그 밖의 실패는 모두 예외다. 본문이 비어도 형식 오류다.
 * 실제 HTTP 호출만 회로·재시도로 감싼다. 404와 본문 형식 오류는 회로가 장애로 세지 않는다.
 * 예외 메시지는 엑셀 실패 응답에 나갈 수 있으므로 공급자 이름과 상태만 담는다. 원인(URL·응답 본문)은 붙이지 않는다.
 */
internal fun <T : Any> ClientResilience.Guard.fetch(
    notFoundAsNull: Boolean = false,
    call: () -> T?,
): T? =
    try {
        run(call) ?: error("$provider 서비스 응답 형식 오류")
    } catch (e: ProviderStatusException) {
        if (notFoundAsNull && e.status == 404) null else error("$provider 서비스 응답 오류(HTTP ${e.status})")
    } catch (e: RetryableException) {
        error("$provider 서비스 연결 실패")
    } catch (e: FeignException) {
        error("$provider 서비스 응답 형식 오류")
    }

internal fun <T : Any> ClientResilience.Guard.fetchOrNull(call: () -> T?): T? = fetch(notFoundAsNull = true, call = call)

internal fun <T : Any> ClientResilience.Guard.fetchRequired(call: () -> T?): T = fetch(call = call)!!
