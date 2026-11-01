package team.startup.report.global.client

import com.sun.net.httpserver.HttpServer
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.cloud.openfeign.FeignAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.function.Supplier

/** 공급자 HTTP 테스트 서버. 받은 요청을 기록하고 "METHOD 경로(쿼리 포함)"에 맞는 응답을 준다. 없으면 500. */
class StubProvider(
    private val token: String,
) : AutoCloseable {
    data class Request(
        val method: String,
        val uri: String,
        val token: String?,
        val body: String,
        val authorization: String? = null,
    )

    class Reply(
        val status: Int,
        val body: String,
        val delay: Duration = Duration.ZERO,
        // 헤더와 본문 첫 바이트를 보낸 뒤 나머지 본문을 이만큼 늦게 보낸다
        val bodyStall: Duration = Duration.ZERO,
    )

    val requests = mutableListOf<Request>()
    val routes = mutableMapOf<String, (Request) -> Reply>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request =
                    Request(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        exchange.requestHeaders.getFirst("X-Internal-Token"),
                        exchange.requestBody.readBytes().decodeToString(),
                        exchange.requestHeaders.getFirst("Authorization"),
                    )
                synchronized(requests) { requests += request }
                val reply = routes["${request.method} ${request.uri}"]?.invoke(request) ?: Reply(500, """{"status":500}""")
                Thread.sleep(reply.delay.toMillis())
                val bytes = reply.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use {
                    if (reply.bodyStall.isZero || bytes.isEmpty()) return@use it.write(bytes)
                    it.write(bytes, 0, 1)
                    it.flush()
                    Thread.sleep(reply.bodyStall.toMillis())
                    it.write(bytes, 1, bytes.size - 1)
                }
            }
            start()
        }

    fun on(
        route: String,
        status: Int = 200,
        body: Any,
    ) {
        routes[route] = { Reply(status, body as? String ?: JSON.writeValueAsString(body)) }
    }

    /** 클라이언트 생성자가 받는 Feign 인터페이스 타입으로 이 서버를 가리키는 클라이언트를 만든다. 예: `UserClient(user.client())` */
    inline fun <reified T : Any> client(readTimeout: Duration = Duration.ofSeconds(5)): T = feign(T::class.java, readTimeout)

    /** 운영과 같은 [reportFeignClient] 경로(Boot 메시지 컨버터·FeignClientBuilder)로 만든다. */
    fun <T : Any> feign(
        type: Class<T>,
        readTimeout: Duration = Duration.ofSeconds(5),
    ): T = reportFeignClient(FEIGN_CONTEXT, type, "stub", provider(), Duration.ofSeconds(1), readTimeout)

    private fun provider() = ReportClientsProperties.Provider(url(), token)

    fun url() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    companion object {
        val JSON: JsonMapper = JsonMapper.builder().findAndAddModules().build()

        // 운영 컨텍스트 대신 Feign 자동설정과 Boot 클라이언트 메시지 컨버터(JSON 매퍼 포함)만 올린다. 테스트 JVM에서 한 번만 만든다.
        private val FEIGN_CONTEXT by lazy {
            AnnotationConfigApplicationContext().apply {
                registerBean(JsonMapper::class.java, Supplier { JSON })
                register(FeignStubConfig::class.java)
                refresh()
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(HttpMessageConvertersAutoConfiguration::class, FeignAutoConfiguration::class)
private class FeignStubConfig
