package team.startup.report.domain.excel.service.impl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.AttendanceClient
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.UserClient
import team.startup.report.global.exception.ExpectedException
import team.startup.report.support.values
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

private const val ATTENDANCE_TOKEN = "attendance-token-for-test"
private const val USER_TOKEN = "user-token-for-test"

private const val ALL_PAGE_0 = "GET /internal/expos/e1/entry-records?page=0&size=2000"
private const val ALL_PAGE_1 = "GET /internal/expos/e1/entry-records?page=1&size=2000"
private const val STANDARD_NAMES = "POST /internal/standard-participants/names"
private const val TRAINEE_NAMES = "POST /internal/trainees/names"

private val HEADER = listOf("일시", "구분", "대상 종류", "이름", "종이 QR 구분", "종이 QR 코드", "스캐너")

/** 참여 서비스 계약(Attendance #150)을 스텁으로 검증한다. 실제 서버는 호출하지 않는다. */
class EntryRecordExcelServiceTests :
    StringSpec({
        lateinit var attendance: StubProvider
        lateinit var user: StubProvider
        lateinit var service: EntryRecordExcelService

        beforeTest {
            attendance = StubProvider(ATTENDANCE_TOKEN)
            user = StubProvider(USER_TOKEN)
            service = EntryRecordExcelService(AttendanceClient(attendance.client()), UserClient(user.client()))
        }
        afterTest { listOf(attendance, user).forEach(StubProvider::close) }

        fun record(
            at: String = "2026-10-19T10:05:12",
            event: String = "ENTER",
            type: String = "STANDARD",
            id: Long? = 7001,
            category: String? = null,
            code: String? = null,
            scanner: String? = "gate-1",
        ) = mapOf(
            "occurredAt" to at,
            "eventType" to event,
            "subjectType" to type,
            "subjectId" to id,
            "category" to category,
            "paperCode" to code,
            "scanner" to scanner,
        )

        fun page(
            page: Int,
            total: Int,
            records: List<Map<String, Any?>>,
            walkIn: List<Map<String, Any?>> = emptyList(),
        ) = mapOf("expoId" to "e1", "page" to page, "size" to 2000, "totalElements" to total, "records" to records, "walkIn" to walkIn)

        // 요청 본문의 ID 중 known에 있는 것만 있으면 이름을 주고, 모르는 ID가 하나라도 있으면 공급자처럼 404다.
        fun namesRoute(
            route: String,
            key: String,
            idField: String,
            known: Map<Long, String>,
        ) {
            user.routes[route] = { request ->
                val ids =
                    StubProvider.JSON
                        .readTree(request.body)
                        .path(key)
                        .values()
                        .map(JsonNode::asLong)
                if (ids.all { it in known }) {
                    StubProvider.Reply(
                        200,
                        StubProvider.JSON.writeValueAsString(ids.map { mapOf(idField to it, "name" to known.getValue(it)) }),
                    )
                } else {
                    StubProvider.Reply(404, """{"status":404}""")
                }
            }
        }

        fun sheets(file: ExcelFile): Pair<List<List<String?>>, List<List<String?>>> {
            val bytes = ByteArrayOutputStream().also { out -> file.workbook.use { it.write(out) } }.toByteArray()
            XSSFWorkbook(ByteArrayInputStream(bytes)).use {
                it.getSheetName(0) shouldBe "입장 기록"
                it.getSheetName(1) shouldBe "간편입장 집계"
                return it.getSheetAt(0).values() to it.getSheetAt(1).values()
            }
        }

        "여러 페이지를 끝까지 읽고 이름·종이 QR·스캐너·간편입장을 시트에 채운다" {
            val first = List(2000) { record(id = 7001, scanner = "gate-1") }
            val last =
                listOf(
                    record(at = "2026-10-19T12:00:00", event = "EXIT", type = "TRAINEE", id = 9001, scanner = null),
                    record(at = "2026-10-19T12:30:00", event = "RE_ENTER", id = 7001),
                    record(
                        at = "2026-10-19T13:00:00",
                        type = "PAPER_QR",
                        id = null,
                        category = "ADULT",
                        code = "ADT-0001-K7",
                        scanner = "gate-2",
                    ),
                )
            attendance.on(
                ALL_PAGE_0,
                body = page(0, 2003, first, listOf(mapOf("date" to "2026-10-19", "category" to "TEACHER", "count" to 3))),
            )
            attendance.on(ALL_PAGE_1, body = page(1, 2003, last))
            namesRoute(STANDARD_NAMES, "participantIds", "participantId", mapOf(7001L to "홍길동"))
            namesRoute(TRAINEE_NAMES, "traineeIds", "traineeId", mapOf(9001L to "김연수"))

            val (records, walkIn) = sheets(service.execute("e1", null, null))

            records.size shouldBe 1 + 2003
            records[0] shouldBe HEADER
            records[1] shouldBe listOf("2026-10-19 10:05:12", "입장", "일반 참가자", "홍길동", null, null, "gate-1")
            records[2001] shouldBe listOf("2026-10-19 12:00:00", "퇴장", "연수자", "김연수", null, null, null)
            records[2002] shouldBe listOf("2026-10-19 12:30:00", "재입장", "일반 참가자", "홍길동", null, null, "gate-1")
            records[2003] shouldBe listOf("2026-10-19 13:00:00", "입장", "종이 QR", null, "성인", "ADT-0001-K7", "gate-2")
            walkIn shouldBe listOf(listOf("날짜", "참가 유형", "인원"), listOf("2026-10-19", "교사", "3.0"))

            // 이름은 중복 없이 한 번에 묻고 내부 토큰을 공급자별로 보낸다
            attendance.requests.map { it.token }.toSet() shouldBe setOf(ATTENDANCE_TOKEN)
            user.requests.map { it.token }.toSet() shouldBe setOf(USER_TOKEN)
            StubProvider.JSON
                .readTree(user.requests.single { it.uri == "/internal/standard-participants/names" }.body)
                .path("participantIds")
                .values()
                .map(JsonNode::asLong) shouldBe listOf(7001L)
        }

        "기간을 그대로 넘기고 한글 파일명을 UTF-8로 인코딩한다" {
            attendance.on(
                "GET /internal/expos/e1/entry-records?from=2026-10-19&to=2026-10-20&page=0&size=2000",
                body = page(0, 0, emptyList()),
            )
            val file = service.execute("e1", LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-20"))
            Regex("입장기록_\\d{8}_\\d{6}\\.xlsx").matches(ContentDisposition.parse(file.contentDisposition).filename!!) shouldBe true
            file.contentDisposition shouldContain "filename*=UTF-8''"
        }

        "빈 결과는 머리글만 있는 두 시트이고 이름을 조회하지 않는다" {
            attendance.on(ALL_PAGE_0, body = page(0, 0, emptyList()))
            val (records, walkIn) = sheets(service.execute("e1", null, null))
            records shouldBe listOf(HEADER)
            walkIn shouldBe listOf(listOf("날짜", "참가 유형", "인원"))
            user.requests shouldBe emptyList()
        }

        "유저 서비스가 모르는 대상은 이름을 빈 칸으로 두고 나머지 이름은 채운다" {
            attendance.on(
                ALL_PAGE_0,
                body = page(0, 3, listOf(record(id = 1), record(id = 2), record(id = 3))),
            )
            namesRoute(STANDARD_NAMES, "participantIds", "participantId", mapOf(1L to "가", 3L to "다"))

            val (records, _) = sheets(service.execute("e1", null, null))

            records.drop(1).map { it[3] } shouldBe listOf("가", null, "다")
        }

        "알 수 없는 구분·참가 유형 값은 원문 그대로 보여 준다" {
            attendance.on(
                ALL_PAGE_0,
                body =
                    page(
                        0,
                        2,
                        listOf(
                            record(event = "FUTURE_EVENT", type = "ROBOT", id = null, category = "ALIEN"),
                            record(type = "PAPER_QR", id = null, category = "CHILD", code = "CHD-0001-AB"),
                        ),
                        listOf(mapOf("date" to "2026-10-19", "category" to "ALIEN", "count" to 1)),
                    ),
            )
            val (records, walkIn) = sheets(service.execute("e1", null, null))
            records[1].subList(1, 5) shouldBe listOf("FUTURE_EVENT", "ROBOT", null, "ALIEN")
            records[2][4] shouldBe "학생"
            walkIn[1][1] shouldBe "ALIEN"
        }

        "참가 유형 표시 이름 매핑" {
            mapOf(
                "KINDERGARTEN_STUDENT" to "미취학 아동",
                "ELEMENTARY_STUDENT" to "초등학생",
                "MIDDLE_SCHOOL_STUDENT" to "중학생",
                "HIGH_SCHOOL_STUDENT" to "고등학생",
                "PARENT" to "학부모",
                "GENERAL" to "일반인",
                "TEACHER" to "교사",
                "PRE_SERVICE_TEACHER" to "예비교사",
                "ADULT" to "성인",
                "STUDENT" to "어린이·학생",
                "CHILD" to "학생",
            ).forEach { (code, label) -> participationLabel(code) shouldBe label }
        }

        "한 페이지라도 실패하면 부분 결과로 파일을 만들지 않고 502다" {
            attendance.on(ALL_PAGE_0, body = page(0, 2003, List(2000) { record() }))
            attendance.on(ALL_PAGE_1, 500, """{"status":500,"message":"private-detail"}""")
            namesRoute(STANDARD_NAMES, "participantIds", "participantId", mapOf(7001L to "홍길동"))

            val e = shouldThrow<ExpectedException> { service.execute("e1", null, null) }

            e.status shouldBe HttpStatus.BAD_GATEWAY
            e.message shouldNotContain "private-detail"
        }

        "이름 조회가 404 외로 실패해도 전체가 실패한다" {
            attendance.on(ALL_PAGE_0, body = page(0, 1, listOf(record())))
            user.on(STANDARD_NAMES, 500, """{"status":500}""")
            shouldThrow<ExpectedException> { service.execute("e1", null, null) }.status shouldBe HttpStatus.BAD_GATEWAY
        }

        "총 건수에 못 미친 채 빈 페이지가 오면 부분 결과이므로 실패한다" {
            attendance.on(ALL_PAGE_0, body = page(0, 4000, List(2000) { record() }))
            attendance.on(ALL_PAGE_1, body = page(1, 4000, emptyList()))
            namesRoute(STANDARD_NAMES, "participantIds", "participantId", mapOf(7001L to "홍길동"))
            shouldThrow<ExpectedException> { service.execute("e1", null, null) }.status shouldBe HttpStatus.BAD_GATEWAY
        }

        "행사가 없으면 404, 총 건수가 상한을 넘으면 413이다" {
            attendance.on(ALL_PAGE_0, 404, """{"status":404}""")
            shouldThrow<ExpectedException> { service.execute("e1", null, null) }.status shouldBe HttpStatus.NOT_FOUND

            attendance.on(ALL_PAGE_0, body = page(0, 300_001, emptyList()))
            val tooLarge = shouldThrow<ExpectedException> { service.execute("e1", null, null) }
            tooLarge.status shouldBe HttpStatus.PAYLOAD_TOO_LARGE
            tooLarge.message shouldContain "300000건"
            tooLarge.message shouldContain "기간을 줄여 주세요"
        }

        "기간은 둘 다 주거나 모두 생략해야 하고 from이 to보다 늦을 수 없다(공급자 호출 전)" {
            val day = LocalDate.parse("2026-10-19")
            shouldThrow<ExpectedException> { service.execute("e1", day, null) }.status shouldBe HttpStatus.BAD_REQUEST
            shouldThrow<ExpectedException> { service.execute("e1", null, day) }.status shouldBe HttpStatus.BAD_REQUEST
            shouldThrow<ExpectedException> { service.execute("e1", day, day.minusDays(1)) }.status shouldBe HttpStatus.BAD_REQUEST
            attendance.requests shouldBe emptyList()
        }

        "참여 서비스 연결이 설정되지 않았으면 503이다" {
            val unconfigured = EntryRecordExcelService(AttendanceClient(null), UserClient(user.client()))
            shouldThrow<ExpectedException> { unconfigured.execute("e1", null, null) }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }
    })
