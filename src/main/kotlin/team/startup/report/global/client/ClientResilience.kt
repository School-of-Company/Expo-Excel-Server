package team.startup.report.global.client

import feign.RetryableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * 공급자 한 곳의 호출 보호. 공급자마다 회로를 따로 둬서 한 서비스가 죽어도 다른 서비스 호출은 막히지 않는다.
 *
 * - 장애로 세는 것은 타임아웃·연결 실패·5xx뿐이다. 404·409·401 등 4xx와 본문 형식·계약 오류는 회로에 영향을 주지 않는다.
 * - 회로가 열리면 공급자를 부르지 않고 바로 [IllegalStateException]으로 실패한다. 기존 공급자 장애(연결 실패·5xx)와 같은 종류라서
 *   엑셀 서비스가 지금처럼 502로 바꾼다. 페이지를 돌던 중 열려도 파일 없이 그 요청 하나만 깨끗이 실패한다.
 * - 재시도는 [read](멱등한 GET)에서만, 즉시 실패(타임아웃이 아닌 연결 실패·504 아닌 5xx)에 1회다. [write]는 재시도하지 않는다.
 *   차단기가 재시도 바깥이라 요청 1건은 재시도 횟수와 관계없이 집계 1건이다(마지막 시도의 결과). 열린 회로는 재시도하지 않는다.
 *   커서·snapshotAt 페이지 조회는 같은 요청을 다시 보내도 같은 결과라 재시도해도 된다.
 */
class ClientResilience(
    val provider: String,
    private val breaker: CircuitBreaker = circuitBreaker(provider),
) {
    val state: CircuitBreaker.State get() = breaker.state

    /** 멱등한 GET. 즉시 실패에 한해 1회 재시도한다. */
    val read = Guard(retry = Retry.of(provider, RETRY_CONFIG))

    /** POST 등 쓰기·생성 호출. 재시도하지 않는다. */
    val write = Guard(retry = null)

    inner class Guard internal constructor(
        private val retry: Retry?,
    ) {
        val provider: String get() = this@ClientResilience.provider

        fun <T> run(block: () -> T): T {
            // 차단기가 재시도 바깥이다: 재시도를 거친 최종 결과만 1건으로 집계하고, 회로가 열려 있으면 시도 자체를 하지 않는다
            val attempt: () -> T =
                if (retry == null) {
                    block
                } else {
                    { retry.executeSupplier(block) }
                }
            return try {
                breaker.executeSupplier(attempt)
            } catch (e: CallNotPermittedException) {
                error("$provider 서비스 호출 차단(회로 열림)")
            }
        }
    }

    companion object {
        private const val WINDOW_SIZE = 20
        private const val MIN_CALLS = 10
        private const val FAILURE_RATE_THRESHOLD = 50f
        private const val OPEN_SECONDS = 10L

        private val RETRY_CONFIG: RetryConfig =
            RetryConfig
                .custom<Any>()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(50))
                .retryOnException(::isImmediateFailure)
                .build()

        /** 최근 20번 중 10번 이상 호출되고 절반 이상 실패하면 10초 동안 열린다. */
        fun circuitBreaker(provider: String): CircuitBreaker =
            CircuitBreaker.of(
                provider,
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(WINDOW_SIZE)
                    .minimumNumberOfCalls(MIN_CALLS)
                    .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                    .waitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS))
                    .recordException(::isTransientFailure)
                    .build(),
            )

        // RetryableException은 연결 실패·타임아웃이다(Feign은 IOException을 이것으로 감싸고, 재시도 응답 판정은 ErrorDecoder가 대신한다)
        internal fun isTransientFailure(e: Throwable): Boolean =
            e is ResourceAccessException ||
                (e is RestClientResponseException && e.statusCode.is5xxServerError) ||
                e is RetryableException ||
                (e is ProviderStatusException && e.status in 500..599)

        internal fun isImmediateFailure(e: Throwable): Boolean =
            when (e) {
                is ResourceAccessException, is RetryableException -> {
                    generateSequence<Throwable>(
                        e,
                    ) { it.cause }.none { it is HttpTimeoutException }
                }

                is RestClientResponseException -> {
                    e.statusCode.is5xxServerError && e.statusCode.value() != 504
                }

                is ProviderStatusException -> {
                    e.status in 500..599 && e.status != 504
                }

                else -> {
                    false
                }
            }
    }
}
