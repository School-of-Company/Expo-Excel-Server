package team.startup.report.domain.excel.service.impl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpStatus
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.form.FormClient
import team.startup.report.global.exception.ExpectedException
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val TOKEN = "form-token-for-test"
private const val EXPO = "11111111-1111-1111-1111-111111111111"
private const val FIRST = "GET /internal/surveys/$EXPO/public-answers?limit=1000"
private const val SECOND = "GET /internal/surveys/$EXPO/public-answers?cursor=c1&limit=1000"
private const val SNAPSHOT = "2026-10-21T05:00:00.123456Z"

private val FIXED = listOf("응답 ID", "설문 ID", "설문 제목", "제출 일시(KST)", "직업(자가 선택)")

// 실제 공개 설문 형태(17 문장형, 18 체크박스)에 합성 선택형·동명 문항을 더한 정의. 값은 모두 합성이다.
private fun question(
    id: Int,
    title: String,
    formType: String,
    jsonData: Map<String, Any> = emptyMap(),
) = mapOf("id" to id, "order" to id - 17, "title" to title, "formType" to formType, "requiredStatus" to false, "jsonData" to jsonData)

private val QUESTIONS =
    listOf(
        question(17, "의견", "SENTENCE"),
        question(18, "재참여 의향", "CHECKBOX"),
        question(19, "만족도", "DROPDOWN", mapOf("1" to "만족", "2" to mapOf("value" to "보통", "isAlwaysSelected" to false))),
        question(20, "좋았던 점", "MULTIPLE", mapOf("1" to "강연", "2" to "체험")),
        question(21, "의견", "SENTENCE"),
    )

private fun answer(
    id: String,
    answers: Map<String, Any?>,
    occupation: String? = "TEACHER",
    createdAt: String = "2026-10-21T04:59:01.000001Z",
) = mapOf("id" to id, "createdAt" to createdAt, "occupation" to occupation, "answers" to answers)

private fun page(
    answers: List<Any>,
    total: Int = answers.size,
    next: String? = null,
    snapshotAt: String = SNAPSHOT,
    surveyId: String = "s1",
    expoId: String = EXPO,
) = mapOf(
    "survey" to mapOf("id" to surveyId, "expoId" to expoId, "title" to "만족도 조사"),
    "questionDefinition" to "CURRENT",
    "questions" to QUESTIONS,
    "snapshotAt" to snapshotAt,
    "totalCount" to total,
    "answers" to answers,
    "nextCursor" to next,
)

private fun ExcelFile.read() =
    ByteArrayOutputStream()
        .also {
            workbook.write(it)
            workbook.dispose()
        }.toByteArray()
        .let { XSSFWorkbook(ByteArrayInputStream(it)) }

/** Form 공개 설문 응답 계약(Form #215)을 스텁으로 검증하고, 만든 XLSX를 저장한 뒤 다시 열어 셀을 비교한다. */
class PublicSurveyExcelServiceTests :
    StringSpec({
        lateinit var form: StubProvider
        lateinit var service: PublicSurveyExcelServiceImpl

        beforeTest {
            form = StubProvider(TOKEN)
            service = PublicSurveyExcelServiceImpl(FormClient(form.client()))
        }
        afterTest { form.close() }

        "두 페이지를 끝까지 읽어 응답마다 한 행을 쓰고 보기 문구·동명 문항·줄바꿈·false를 구분한다" {
            form.on(
                FIRST,
                body =
                    page(
                        listOf(
                            answer(
                                "a1",
                                mapOf("17" to "첫 줄\r\n둘째 줄", "18" to true, "19" to "1", "20" to listOf("1", "2"), "21" to "두 번째 의견"),
                            ),
                            // 같은 시각의 다른 응답도 각각 한 행이다
                            answer("a2", mapOf("17" to "좋음", "18" to false, "19" to "2"), occupation = "GENERAL"),
                        ),
                        total = 3,
                        next = "c1",
                    ),
            )
            val third = answer("a3", mapOf("17" to "", "18" to null, "19" to "9", "20" to listOf("2", "7")), occupation = "UNKNOWN_JOB")
            form.on(SECOND, body = page(listOf(third), total = 3))

            service.execute(EXPO).read().use { book ->
                val rows = book.getSheet("응답").values()
                rows[0] shouldBe FIXED + listOf("의견 (문항 ID 17)", "재참여 의향", "만족도", "좋았던 점", "의견 (문항 ID 21)")
                rows.size shouldBe 4
                val meta = listOf("s1", "만족도 조사", "2026-10-21 13:59:01")
                rows[1] shouldBe listOf("a1") + meta + listOf("교원", "첫 줄\n둘째 줄", "true", "만족", "강연, 체험", "두 번째 의견")
                // 키가 없는 문항은 빈 칸(미응답)이고 false는 값이다
                rows[2] shouldBe listOf("a2") + meta + listOf("일반인", "좋음", "false", "보통", null, null)
                rows[3] shouldBe listOf("a3") + meta + listOf("UNKNOWN_JOB", "", "(null)", "[미복원] 9", "체험, [미복원] 7", null)
                book
                    .getSheet("응답")
                    .getRow(1)
                    .getCell(5)
                    .cellStyle.wrapText shouldBe true

                book.getSheet("문항").values()[3] shouldBe listOf("19", "만족도", "만족도", "DROPDOWN", "1 = 만족\n2 = 보통")
                val info = book.getSheet("정보").values().associate { it[0] to it[1] }
                info["응답 행 수"] shouldBe "3"
                info["원천 응답 수"] shouldBe "3"
                info["행사 ID"] shouldBe EXPO
                info["조회 조건"]!! shouldContain "2026-10-21 14:00:00"
                info["문항 정의"]!! shouldContain "CURRENT"
            }
            form.requests.map { it.uri } shouldBe
                listOf("/internal/surveys/$EXPO/public-answers?limit=1000", "/internal/surveys/$EXPO/public-answers?cursor=c1&limit=1000")
            form.requests.all { it.token == TOKEN } shouldBe true
        }

        "불투명 커서는 예약 문자까지 인코딩해 그대로 넘긴다" {
            form.on(FIRST, body = page(listOf(answer("a1", mapOf())), total = 2, next = "a+b/c=&d"))
            val encoded = "GET /internal/surveys/$EXPO/public-answers?cursor=a%2Bb%2Fc%3D%26d&limit=1000"
            form.on(encoded, body = page(listOf(answer("a2", mapOf())), total = 2))

            service.execute(EXPO).read().use { it.getSheet("응답").physicalNumberOfRows shouldBe 3 }
        }

        "정의에 없는 문항 ID 답변은 추정하지 않고 미복원 답변 열에 원문으로 둔다" {
            form.on(FIRST, body = page(listOf(answer("a1", mapOf("17" to "x", "99" to "옛 답변")), answer("a2", mapOf("17" to "y")))))

            service.execute(EXPO).read().use { book ->
                val rows = book.getSheet("응답").values()
                rows[0].last() shouldBe "미복원 답변"
                rows[1].last() shouldBe """{"99":"옛 답변"}"""
                rows[2].size shouldBe rows[0].size
                rows[2].last() shouldBe null
            }
        }

        "응답 0건은 머리글만 있는 파일이다" {
            form.on(FIRST, body = page(emptyList()))

            service.execute(EXPO).read().use { book ->
                book.getSheet("응답").values() shouldBe listOf(FIXED + listOf("의견 (문항 ID 17)", "재참여 의향", "만족도", "좋았던 점", "의견 (문항 ID 21)"))
                book.getSheet("정보").values().associate { it[0] to it[1] }["응답 행 수"] shouldBe "0"
            }
        }

        "공개 설문이 없으면 404다" {
            form.on(FIRST, 404, """{"code":"SURVEY_NOT_FOUND"}""")
            shouldThrow<ExpectedException> { service.execute(EXPO) }.status shouldBe HttpStatus.NOT_FOUND
        }

        "상한을 넘으면 첫 페이지 뒤 더 읽지 않고 413이다" {
            form.on(FIRST, body = page(emptyList(), total = 100_001, next = "c1"))
            shouldThrow<ExpectedException> { service.execute(EXPO) }.status shouldBe HttpStatus.PAYLOAD_TOO_LARGE
            form.requests.size shouldBe 1
        }

        "연결이 설정되지 않으면 503이다" {
            val unconfigured = PublicSurveyExcelServiceImpl(FormClient(null))
            shouldThrow<ExpectedException> { unconfigured.execute(EXPO) }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }

        "원천 실패·건수 불일치·커서 오류는 부분 결과 없이 502다" {
            val one = listOf(answer("a1", mapOf("17" to "x")))
            val two = listOf(answer("a2", mapOf()))
            val more = FIRST to (200 to page(one, total = 2, next = "c1"))
            val cases =
                listOf(
                    // 첫 페이지 장애
                    mapOf(FIRST to (503 to """{"status":503}""")),
                    // 두 번째 페이지 장애·커서 거절·도중 설문 삭제
                    mapOf(more, SECOND to (503 to "{}")),
                    mapOf(more, SECOND to (400 to """{"code":"SURVEY_CURSOR_INVALID"}""")),
                    mapOf(more, SECOND to (404 to "{}")),
                    // 총 건수보다 적게 끝남·많이 옴
                    mapOf(FIRST to (200 to page(one, total = 2))),
                    mapOf(FIRST to (200 to page(one + answer("a2", mapOf()), total = 1))),
                    // 페이지 사이 상한 시각·설문·총 건수가 바뀜
                    mapOf(more, SECOND to (200 to page(two, total = 2, snapshotAt = "2026-10-21T06:00:00Z"))),
                    mapOf(more, SECOND to (200 to page(two, total = 2, surveyId = "s2"))),
                    mapOf(more, SECOND to (200 to page(two, total = 3))),
                    // 같은 응답 ID 중복·같은 커서 반복·빈 페이지의 커서
                    mapOf(more, SECOND to (200 to page(one, total = 2))),
                    mapOf(FIRST to (200 to page(one, total = 3, next = "c1")), SECOND to (200 to page(two, total = 3, next = "c1"))),
                    mapOf(FIRST to (200 to page(emptyList(), total = 1, next = "c1"))),
                    // 다른 행사 설문·답변이 객체가 아님
                    mapOf(FIRST to (200 to page(one, expoId = "22222222-2222-2222-2222-222222222222"))),
                    mapOf(FIRST to (200 to page(listOf(mapOf("id" to "a1", "createdAt" to SNAPSHOT, "answers" to listOf("x")))))),
                )
            cases.forEach { routes ->
                form.routes.clear()
                routes.forEach { (route, reply) -> form.on(route, reply.first, reply.second) }
                val error = shouldThrow<ExpectedException> { service.execute(EXPO) }
                error.status shouldBe HttpStatus.BAD_GATEWAY
                error.message shouldBe "설문 응답 원천 조회 또는 파일 생성에 실패했습니다."
            }
        }
    })
