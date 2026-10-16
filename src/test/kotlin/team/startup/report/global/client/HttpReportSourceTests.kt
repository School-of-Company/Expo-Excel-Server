package team.startup.report.global.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import team.startup.report.domain.excel.service.ApplicationType
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.impl.HttpReportSource
import java.time.Duration

private const val USER_TOKEN = "user-token-for-test"
private const val APPLICATION_TOKEN = "application-token-for-test"
private const val EXPO_TOKEN = "expo-token-for-test"

private const val STANDARD_PAGE = "GET /internal/expos/e1/standard-participants/details?size=500"
private const val TRAINEE_PAGE = "GET /internal/expos/e1/trainees/details?size=500"
private const val EXPO = "GET /internal/expo/e1"
private const val TRAINING_BATCH = "POST /internal/expo/e1/training-programs/batch"
private const val TRAINING_APPLICATIONS = "POST /internal/training-program-applications/trainees"

/** 공급자 요청 경로·메서드·본문·내부 토큰과 조회 조합(커서·분할·순서·대상 없음·장애) 검증 */
class HttpReportSourceTests :
    StringSpec({
        lateinit var user: StubProvider
        lateinit var application: StubProvider
        lateinit var expo: StubProvider
        lateinit var source: HttpReportSource

        beforeTest {
            user = StubProvider(USER_TOKEN)
            application = StubProvider(APPLICATION_TOKEN)
            expo = StubProvider(EXPO_TOKEN)
            source = HttpReportSource(UserClient(user.client()), ApplicationClient(application.client()), ExpoClient(expo.client()))
        }
        afterTest {
            listOf(user, application, expo).forEach(StubProvider::close)
        }

        fun allRequests() = listOf(user, application, expo).flatMap { it.requests }

        "일반 참가자: 행사가 없으면 null이고 참가자를 조회하지 않는다" {
            expo.on(EXPO, 404, """{"status":404,"message":"박람회를 찾을 수 없습니다."}""")
            source.standardParticipants("e1") shouldBe null
            user.requests shouldBe emptyList()
        }

        "일반 참가자: 커서를 끝까지 따라가고 공급자별 토큰을 보낸다" {
            expo.on(EXPO, body = mapOf("title" to "행사", "startedDay" to "2026-10-01", "finishedDay" to "2026-10-02"))
            user.on(STANDARD_PAGE, body = mapOf("items" to listOf(standard(1, survey = true), standard(2)), "nextCursor" to 2))
            user.on("$STANDARD_PAGE&cursor=2", body = mapOf("items" to listOf(standard(3)), "nextCursor" to null))

            val participants = source.standardParticipants("e1")!!

            participants.map { it.name } shouldContainExactly listOf("참가자1", "참가자2", "참가자3")
            participants[0].phoneNumber shouldBe "010-0000-0001"
            participants[0].personalInformationStatus shouldBe true
            participants[0].applicationType shouldBe ApplicationType.FIELD
            participants[1].surveyAnswer shouldBe emptyMap()
            // 답변 변환(#13) 연결: 신청 폼은 제목 그대로, 스냅샷 없는 설문은 복원하지 않는다
            participants[0].information shouldBe mapOf("학교" to "광주")
            participants[0].surveyAnswer shouldBe emptyMap()
            user.requests.map { "${it.method} ${it.uri}" } shouldContainExactly listOf(STANDARD_PAGE, "$STANDARD_PAGE&cursor=2")
            user.requests.map { it.token }.distinct() shouldContainExactly listOf(USER_TOKEN)
            expo.requests.map { it.token }.distinct() shouldContainExactly listOf(EXPO_TOKEN)
        }

        "일반 참가자: 행사에 참가자가 없으면 빈 목록" {
            expo.on(EXPO, body = mapOf("title" to "행사"))
            user.on(STANDARD_PAGE, body = mapOf("items" to emptyList<Any>(), "nextCursor" to null))
            source.standardParticipants("e1") shouldBe emptyList()
        }

        "연수자: 신청 500명·프로그램 100개 단위로 나누고 강연은 applicationId 순서로 잇는다" {
            expo.on(EXPO, body = mapOf("title" to "행사"))
            val trainees = (1L..501L).map { trainee(it) }
            user.on(TRAINEE_PAGE, body = mapOf("items" to trainees.reversed(), "nextCursor" to null))
            application.routes[TRAINING_APPLICATIONS] = { request ->
                val ids = longs(request.body, "traineeIds")
                // 응답 순서를 섞는다: 연수자 1은 프로그램 102 → 1 → 2 순서로 신청했다
                val applications =
                    ids.flatMap { id ->
                        if (id == 1L) {
                            listOf(application(30, 1, 1), application(10, 1, 102), application(20, 1, 2), application(40, 1, 2))
                        } else {
                            listOf(application(1000 + id, id, (id % 101) + 1))
                        }
                    }
                StubProvider.Reply(200, StubProvider.JSON.writeValueAsString(applications.reversed()))
            }
            expo.routes[TRAINING_BATCH] = { request ->
                val ids = longs(request.body, "programIds")
                StubProvider.Reply(200, StubProvider.JSON.writeValueAsString(ids.sortedDescending().map(::program)))
            }

            val result = source.trainees("e1")!!

            result.map { it.name }.take(2) shouldContainExactly listOf("연수자1", "연수자2")
            result.size shouldBe 501
            result[0].programs.map { it.id } shouldContainExactly listOf(102L, 2L, 1L, 2L)
            result[0].programs.map { it.category } shouldContainExactly
                listOf(TrainingCategory.ESSENTIAL, TrainingCategory.ESSENTIAL, TrainingCategory.CHOICE, TrainingCategory.ESSENTIAL)
            result[0].trainingId shouldBe "T1"
            application.requests.map { StubProvider.JSON.readTree(it.body)["traineeIds"].size() } shouldContainExactly listOf(500, 1)
            expo.requests.filter { it.method == "POST" }.map {
                StubProvider.JSON
                    .readTree(
                        it.body,
                    )["programIds"]
                    .size()
            } shouldContainExactly
                listOf(100, 2)
            application.requests.map { it.token }.distinct() shouldContainExactly listOf(APPLICATION_TOKEN)
        }

        "연수자: 연수자가 없으면 신청·프로그램을 조회하지 않는다" {
            expo.on(EXPO, body = mapOf("title" to "행사"))
            user.on(TRAINEE_PAGE, body = mapOf("items" to emptyList<Any>(), "nextCursor" to null))
            source.trainees("e1") shouldBe emptyList()
            application.requests shouldBe emptyList()
        }

        "프로그램 참가자: 행사 소속이 아니면 null" {
            expo.on("GET /internal/expo/e1/standard-programs/7", 404, """{"status":404}""")
            source.programParticipants("e1", 7) shouldBe null
            application.requests shouldBe emptyList()
        }

        "프로그램 참가자: 신청 순서대로 참가자를 ID로 연결한다" {
            expo.on("GET /internal/expo/e1/standard-programs/7", body = mapOf("id" to 7, "title" to "프로그램"))
            application.on(
                "GET /internal/standard-program-applications/program/7",
                body =
                    listOf(
                        mapOf("applicationId" to 3, "participantId" to 30, "status" to false),
                        mapOf("applicationId" to 1, "participantId" to 10, "status" to true),
                        mapOf("applicationId" to 2, "participantId" to 20, "status" to false),
                    ),
            )
            user.on(
                "POST /internal/standard-participants/details",
                body =
                    listOf(30L, 20L, 10L).map {
                        mapOf(
                            "participantId" to it,
                            "name" to "p$it",
                            "phoneNumber" to "010-$it",
                            "personalInformationStatus" to (it == 10L),
                        )
                    },
            )

            source.programParticipants("e1", 7)!! shouldContainExactly
                listOf(
                    ProgramParticipant("p10", "010-10", true),
                    ProgramParticipant("p20", "010-20", false),
                    ProgramParticipant("p30", "010-30", false),
                )
            val brief = user.requests.single()
            StubProvider.JSON.readTree(brief.body).let {
                it["expoId"].asString() shouldBe "e1"
                longs(brief.body, "participantIds") shouldContainExactly listOf(10L, 20L, 30L)
            }
            brief.uri shouldNotContain "010"
        }

        "프로그램 참가자: 신청이 없으면 빈 목록이고 User를 부르지 않는다" {
            expo.on("GET /internal/expo/e1/standard-programs/7", body = mapOf("id" to 7, "title" to "프로그램"))
            application.on("GET /internal/standard-program-applications/program/7", body = emptyList<Any>())
            source.programParticipants("e1", 7) shouldBe emptyList()
            user.requests shouldBe emptyList()
        }

        "출석부: 연수자가 없으면 null" {
            user.on("GET /internal/trainees/5/details", 404, """{"status":404}""")
            source.traineeAttendance(5) shouldBe null
        }

        "출석부: 행사 제목과 신청 순서의 프로그램, 신청이 없으면 프로그램 조회 없이 빈 목록" {
            user.on("GET /internal/trainees/5/details", body = traineeDetail(5))
            expo.on(EXPO, body = mapOf("title" to "광주 박람회"))
            application.on(TRAINING_APPLICATIONS, body = listOf(application(9, 5, 2), application(8, 5, 1)))
            expo.on(TRAINING_BATCH, body = listOf(program(1), program(2)))

            val attendance = source.traineeAttendance(5)!!
            attendance.expoTitle shouldBe "광주 박람회"
            attendance.traineeName shouldBe "연수자5"
            attendance.information shouldBe mapOf("소속" to "광주고")
            attendance.programs.map { it.id } shouldContainExactly listOf(1L, 2L)
            attendance.programs[0].startedAt shouldBe "2026-10-01 10:00"
            longs(application.requests.single().body, "traineeIds") shouldContainExactly listOf(5L)

            application.on(TRAINING_APPLICATIONS, body = emptyList<Any>())
            expo.requests.clear()
            source.traineeAttendance(5)!!.programs shouldBe emptyList()
            expo.requests.map { it.method } shouldContainExactly listOf("GET")
        }

        "장애: 공급자 오류는 상태만 담은 예외이고 토큰·본문을 노출하지 않는다" {
            listOf(401, 403, 500, 503).forEach { status ->
                expo.on(EXPO, status, """{"status":$status,"message":"secret-body"}""")
                val e = shouldThrow<IllegalStateException> { source.standardParticipants("e1") }
                e.message shouldBe "Expo 서비스 응답 오류(HTTP $status)"
                e.cause shouldBe null
            }
        }

        "장애: 연수 프로그램 일괄 조회 404는 대상 없음이 아니라 실패다" {
            user.on("GET /internal/trainees/5/details", body = traineeDetail(5))
            expo.on(EXPO, body = mapOf("title" to "행사"))
            application.on(TRAINING_APPLICATIONS, body = listOf(application(1, 5, 1)))
            expo.on(TRAINING_BATCH, 404, """{"status":404}""")
            shouldThrow<IllegalStateException> { source.traineeAttendance(5) }.message shouldBe "Expo 서비스 응답 오류(HTTP 404)"
        }

        "장애: 시간 초과·잘못된 응답·커서 오류" {
            val slow = StubProvider(EXPO_TOKEN)
            slow.routes[EXPO] = { StubProvider.Reply(200, """{"title":"행사"}""", Duration.ofMillis(800)) }
            val timeoutSource =
                HttpReportSource(
                    UserClient(user.client()),
                    ApplicationClient(application.client()),
                    ExpoClient(slow.client(Duration.ofMillis(200))),
                )
            shouldThrow<IllegalStateException> { timeoutSource.standardParticipants("e1") }.message shouldBe "Expo 서비스 연결 실패"
            slow.close()

            expo.on(EXPO, body = mapOf("title" to "행사"))
            user.on(STANDARD_PAGE, body = """{"items":[{"participantId":"x"}]}""")
            shouldThrow<IllegalStateException> { source.standardParticipants("e1") }.message shouldBe "User 서비스 응답 형식 오류"

            user.on(STANDARD_PAGE, body = mapOf("items" to listOf(standard(3)), "nextCursor" to 3))
            user.on("$STANDARD_PAGE&cursor=3", body = mapOf("items" to listOf(standard(3)), "nextCursor" to 3))
            shouldThrow<IllegalStateException> { source.standardParticipants("e1") }.message shouldBe "User 서비스 커서 오류"
            allRequests().forEach { it.uri shouldNotContain "token" }
        }

        "설정: 주소·토큰이 비면 기동 실패, toString은 토큰을 가린다" {
            val ok = ReportClientsProperties.Provider("http://user", "secret-token")
            shouldThrow<IllegalArgumentException> {
                ReportClientsProperties(ok, ReportClientsProperties.Provider("http://app", " "), ok)
            }.message shouldBe "clients.application.internal-token이 비어 있습니다."
            shouldThrow<IllegalArgumentException> {
                ReportClientsProperties(ok, ok, ReportClientsProperties.Provider("", "t"))
            }.message shouldBe "clients.expo.url이 비어 있습니다."
            ReportClientsProperties(ok, ok, ok).toString() shouldNotContain "secret-token"
            ReportClientsProperties(ok, ok, ok).toString() shouldContain "***"
        }
    })

private fun information(answers: Map<String, Any?>) = mapOf("answers" to answers, "questions" to null)

private fun standard(
    id: Long,
    survey: Boolean = false,
) = mapOf(
    "participantId" to id,
    "name" to "참가자$id",
    "phoneNumber" to "010-0000-000$id",
    "personalInformationStatus" to true,
    "applicationType" to "FIELD",
    "information" to information(mapOf("학교" to "광주")),
    "surveyAnswer" to if (survey) information(mapOf("1" to "답")) else null,
    "region" to null,
    "school" to null,
)

private fun trainee(id: Long) =
    mapOf(
        "traineeId" to id,
        "name" to "연수자$id",
        "trainingId" to "T$id",
        "phoneNumber" to "010-1",
        "personalInformationStatus" to true,
        "applicationType" to "PRE",
        "information" to information(emptyMap()),
    )

private fun traineeDetail(id: Long) =
    mapOf(
        "traineeId" to id,
        "expoId" to "e1",
        "name" to "연수자$id",
        "trainingId" to "T$id",
        "information" to information(mapOf("소속" to "광주고")),
    )

private fun application(
    applicationId: Long,
    traineeId: Long,
    programId: Long,
) = mapOf("applicationId" to applicationId, "traineeId" to traineeId, "trainingProgramId" to programId)

private fun program(id: Long) =
    mapOf(
        "id" to id,
        "title" to "강연$id",
        "startedAt" to "2026-10-01 10:00",
        "endedAt" to "2026-10-01 11:00",
        "category" to if (id == 1L) "CHOICE" else "ESSENTIAL",
    )

private fun longs(
    body: String,
    field: String,
): List<Long> =
    StubProvider.JSON
        .readTree(body)[field]
        .values()
        .map { it.asLong() }
