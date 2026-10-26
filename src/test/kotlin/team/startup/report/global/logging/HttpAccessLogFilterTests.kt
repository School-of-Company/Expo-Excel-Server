package team.startup.report.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.HandlerMapping

class HttpAccessLogFilterTests :
    StringSpec({
        val logger = LoggerFactory.getLogger(HttpAccessLogFilter.LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()

        beforeTest {
            appender.list.clear()
            appender.start()
            logger.addAppender(appender)
        }
        afterTest { logger.detachAppender(appender) }

        fun run(
            uri: String,
            status: Int = 200,
            query: String? = null,
            pattern: String? = null,
            chain: (MockHttpServletRequest) -> Unit = {},
        ): MockHttpServletRequest {
            val request = MockHttpServletRequest("GET", uri).apply { queryString = query }
            val response = MockHttpServletResponse()
            HttpAccessLogFilter().doFilter(
                request,
                response,
                FilterChain { req, res ->
                    pattern?.let { req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, it) }
                    (res as MockHttpServletResponse).status = status
                    chain(req as MockHttpServletRequest)
                },
            )
            return request
        }

        "요청 1건에 라우트 템플릿과 상태를 담은 로그를 한 줄 남긴다" {
            run("/excel/standard/abc", pattern = "/excel/standard/{expo_id}")

            appender.list shouldHaveSize 1
            appender.list[0].level shouldBe Level.INFO
            appender.list[0].formattedMessage shouldMatch Regex("""GET /excel/standard/\{expo_id} status: 200 \(\d+ms\)""")
            ModuleNameConverter.moduleOf(appender.list[0].loggerName) shouldBe "HttpAccess"
        }

        "4xx는 WARN, 5xx는 ERROR로 남긴다" {
            run("/excel/standard/abc", status = 403)
            run("/excel/standard/abc", status = 502)

            appender.list.map { it.level } shouldBe listOf(Level.WARN, Level.ERROR)
            appender.list[0].formattedMessage shouldMatch Regex("""GET /excel/standard/abc status: 403 \(\d+ms\)""")
        }

        "필터 밖으로 던져진 예외는 500 ERROR로 한 번만 남긴다" {
            shouldThrow<IllegalStateException> { run("/boom") { throw IllegalStateException("boom") } }

            appender.list shouldHaveSize 1
            appender.list[0].level shouldBe Level.ERROR
            appender.list[0].formattedMessage shouldMatch Regex("""GET /boom status: 500 \(\d+ms\)""")
        }

        "클라이언트가 끊으면 499 ERROR로 한 번만 남긴다" {
            run("/excel/standard/abc", status = 500) {
                it.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, ClientAbortException("Broken pipe"))
            }

            appender.list shouldHaveSize 1
            appender.list[0].level shouldBe Level.ERROR
            appender.list[0].formattedMessage shouldMatch Regex("""GET /excel/standard/abc status: 499 \(\d+ms\)""")
        }

        "쿼리 문자열은 남기지 않고 긴 경로는 200자로 자른다" {
            run("/excel/entry-records/abc", query = "from=2026-01-01&token=secret")
            run("/" + "a".repeat(300))

            appender.list[0].formattedMessage shouldNotContain "?"
            appender.list[0].formattedMessage shouldNotContain "secret"
            appender.list[1]
                .formattedMessage
                .split(' ')[1]
                .length shouldBe 200
        }

        "actuator와 Swagger 경로는 남기지 않는다" {
            run("/actuator/health")
            run("/actuator/prometheus")
            run("/stat/v3/api-docs")
            run("/stat/swagger-ui/index.html")

            appender.list.shouldBeEmpty()
        }

        "logging.http-access.enabled=false면 필터를 등록하지 않는다" {
            val runner = ApplicationContextRunner().withUserConfiguration(HttpAccessLogConfig::class.java)

            runner.run { it.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 1 }
            runner
                .withPropertyValues("logging.http-access.enabled=false")
                .run { it.getBeansOfType(FilterRegistrationBean::class.java).size shouldBe 0 }
        }
    })
