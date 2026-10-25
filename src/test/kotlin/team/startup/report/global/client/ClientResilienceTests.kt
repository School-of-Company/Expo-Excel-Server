package team.startup.report.global.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

private const val EXPO = "GET /internal/expo/e1"
private const val NAMES = "POST /internal/standard-participants/names"
private const val USER = "GET /internal/trainees/1/details"
private const val FORM_PAGE = "GET /internal/surveys/e1/public-answers?limit=1000"

private val EXPO_BODY = mapOf("title" to "행사", "startedDay" to "2026-10-01", "finishedDay" to "2026-10-02")

/** 공급자별 회로 차단·제한적 재시도. 열린 회로는 공급자를 부르지 않고 기존 공급자 장애와 같은 예외로 바로 실패한다. */
class ClientResilienceTests :
    StringSpec({
        lateinit var user: StubProvider
        lateinit var expo: StubProvider
        lateinit var form: StubProvider
        lateinit var userResilience: ClientResilience
        lateinit var expoResilience: ClientResilience

        beforeTest {
            user = StubProvider("t")
            expo = StubProvider("t")
            form = StubProvider("t")
            userResilience = ClientResilience(UserClient.NAME)
            expoResilience = ClientResilience(ExpoClient.NAME)
        }
        afterTest { listOf(user, expo, form).forEach(StubProvider::close) }

        fun userClient(readTimeout: Duration = Duration.ofSeconds(5)) = UserClient(user.client(readTimeout), userResilience)

        fun expoClient() = ExpoClient(expo.client(), expoResilience)

        fun openByFailures(call: () -> Unit) {
            repeat(10) { runCatching(call) }
        }

        "5xx가 반복되면 회로가 열려 공급자를 부르지 않고 바로 실패한다" {
            user.on(USER, 500, "{}")
            val client = userClient()

            openByFailures { client.trainee(1) }
            userResilience.state shouldBe CircuitBreaker.State.OPEN

            val before = user.requests.size
            val e = shouldThrow<IllegalStateException> { client.trainee(1) }
            e.message shouldContain "User"
            e.message shouldNotContain "/internal"
            user.requests.size shouldBe before
        }

        "한 공급자의 회로가 열려도 다른 공급자는 계속 호출된다" {
            user.on(USER, 500, "{}")
            expo.on(EXPO, body = EXPO_BODY)
            val users = userClient()
            openByFailures { users.trainee(1) }
            userResilience.state shouldBe CircuitBreaker.State.OPEN

            expoClient().expo("e1")!!.title shouldBe "행사"
            expoResilience.state shouldBe CircuitBreaker.State.CLOSED
        }

        "404와 409·401 같은 4xx는 아무리 반복해도 회로를 열지 않는다" {
            user.on(USER, 404, "{}")
            user.on(NAMES, 409, "{}")
            expo.on(EXPO, 401, "{}")
            val users = userClient()
            val expos = expoClient()

            repeat(30) {
                users.trainee(1) shouldBe null
                runCatching { users.standardParticipantNames("e1", listOf(1)) }
                runCatching { expos.expo("e1") }
            }

            userResilience.state shouldBe CircuitBreaker.State.CLOSED
            expoResilience.state shouldBe CircuitBreaker.State.CLOSED
            user.requests.size shouldBe 60
        }

        "본문 형식 오류는 회로를 열지 않는다" {
            user.on(USER, 200, "not-json")
            val client = userClient()

            repeat(30) { shouldThrow<IllegalStateException> { client.trainee(1) } }

            userResilience.state shouldBe CircuitBreaker.State.CLOSED
            user.requests.size shouldBe 30
        }

        "GET은 즉시 실패(5xx)에 한 번 재시도하고 성공하면 그 결과를 쓴다" {
            var calls = 0
            form.routes[FORM_PAGE] = {
                if (++calls == 1) {
                    StubProvider.Reply(503, "{}")
                } else {
                    StubProvider.Reply(
                        200,
                        """{"survey":{"id":"s","expoId":"e1","title":"t"},"questionDefinition":"CURRENT","questions":[],"snapshotAt":"x","totalCount":0,"answers":[]}""",
                    )
                }
            }
            val client = FormClient(form.client(), ClientResilience(FormClient.NAME))

            client.publicSurveyAnswerPage("e1", null)!!.survey.id shouldBe "s"
            form.requests.size shouldBe 2
        }

        "재시도는 1회뿐이다" {
            user.on(USER, 500, "{}")

            shouldThrow<IllegalStateException> { userClient().trainee(1) }

            user.requests.size shouldBe 2
        }

        "POST는 5xx여도 재시도하지 않는다" {
            user.on(NAMES, 500, "{}")

            shouldThrow<IllegalStateException> { userClient().standardParticipantNames("e1", listOf(1)) }

            user.requests.size shouldBe 1
        }

        "타임아웃은 재시도하지 않는다" {
            user.routes[USER] = { StubProvider.Reply(200, "{}", delay = Duration.ofMillis(600)) }

            shouldThrow<IllegalStateException> { userClient(readTimeout = Duration.ofMillis(100)).trainee(1) }

            user.requests.size shouldBe 1
        }

        "연결 실패는 재시도하고 장애로 센다" {
            val client = userClient()
            user.close()

            shouldThrow<IllegalStateException> { client.trainee(1) }
            openByFailures { client.trainee(1) }

            userResilience.state shouldBe CircuitBreaker.State.OPEN
        }

        "재시도해도 사용자 요청 1건은 집계 1건이라 10번째 요청에서 열린다" {
            val client = userClient()
            user.close()

            repeat(9) {
                shouldThrow<IllegalStateException> { client.trainee(1) }
                userResilience.state shouldBe CircuitBreaker.State.CLOSED
            }
            shouldThrow<IllegalStateException> { client.trainee(1) }
            userResilience.state shouldBe CircuitBreaker.State.OPEN
        }
    })
