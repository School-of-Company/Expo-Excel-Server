package team.startup.report.global.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.util.DisconnectedClientHelper

/**
 * 요청 1건당 접근 로그 1줄(`GET /excel/standard/{expo_id} status: 200 (12ms)`). Expo 서비스 공통 형식이다.
 * 경로는 매칭된 라우트 템플릿을 쓰고, 없으면(보안 거절·404) 쿼리를 뺀 요청 URI를 200자까지 쓴다.
 * 쿼리·헤더·쿠키·본문은 남기지 않는다. 시간은 체인이 돌아올 때까지다(엑셀 다운로드는 길게 나온다).
 */
class HttpAccessLogFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return SKIPPED_PATHS.any { path == it || path.startsWith("$it/") }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val status = statusOf(request, response, failure)
            val message = "${request.method} ${pathOf(request)} status: $status (${elapsedMs}ms)"
            when {
                status >= 500 || status == CLIENT_CLOSED_REQUEST -> log.error(message)
                status >= 400 -> log.warn(message)
                else -> log.info(message)
            }
        }
    }

    private fun statusOf(
        request: HttpServletRequest,
        response: HttpServletResponse,
        failure: Throwable?,
    ): Int {
        // 예외가 GlobalExceptionHandler에서 처리됐으면 DispatcherServlet이 요청 속성에 남긴다
        val handled = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE) as? Throwable
        val exception = failure ?: handled
        return when {
            exception != null && DisconnectedClientHelper.isClientDisconnectedException(exception) -> CLIENT_CLOSED_REQUEST

            // 필터 밖으로 던져진 예외는 컨테이너가 500으로 바꾼다(아직 응답 상태에 반영되지 않았다)
            failure != null -> 500

            else -> response.status
        }
    }

    private fun pathOf(request: HttpServletRequest): String {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        // "/**"는 정적 리소스 기본 매핑(사실상 404)이라 원래 경로가 더 쓸모 있다
        if (pattern != null && pattern != "/**") return pattern
        return request.requestURI.take(MAX_PATH_LENGTH)
    }

    companion object {
        // ModuleNameConverter가 점 없는 로거 이름을 그대로 module로 쓴다 -> module=HttpAccess
        const val LOGGER_NAME = "HttpAccess"
        private val log: Logger = LoggerFactory.getLogger(LOGGER_NAME)
        private const val CLIENT_CLOSED_REQUEST = 499
        private const val MAX_PATH_LENGTH = 200
        private val SKIPPED_PATHS =
            listOf(
                "/actuator",
                "/stat/v3/api-docs",
                "/stat/swagger-ui",
                "/stat/swagger-ui.html",
                "/v3/api-docs",
                "/swagger-ui",
                "/swagger-ui.html",
            )
    }
}

@Configuration
@ConditionalOnBooleanProperty(name = ["logging.http-access.enabled"], matchIfMissing = true)
class HttpAccessLogConfig {
    // Spring Security 체인(-100)보다 앞이라 401/403 거절도 남는다. 관측 필터(HIGHEST_PRECEDENCE + 1) 안쪽이라 MDC traceId가 있으면 붙는다
    @Bean
    fun httpAccessLogFilter(): FilterRegistrationBean<HttpAccessLogFilter> =
        FilterRegistrationBean(HttpAccessLogFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }
}
