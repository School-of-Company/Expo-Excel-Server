package team.startup.report.global.client

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration

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
                exchange.responseBody.use { it.write(bytes) }
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

    fun client(readTimeout: Duration = Duration.ofSeconds(5)) =
        reportRestClient(
            ReportClientsProperties.Provider("http://127.0.0.1:${server.address.port}", token),
            Duration.ofSeconds(1),
            readTimeout,
            JSON,
        )

    fun url() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    companion object {
        val JSON: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
