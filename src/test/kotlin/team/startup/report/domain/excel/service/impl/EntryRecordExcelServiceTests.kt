package team.startup.report.domain.excel.service.impl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import team.startup.report.domain.excel.renderer.participationLabel
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.StubProvider
import team.startup.report.global.client.attendance.AttendanceClient
import team.startup.report.global.client.user.UserClient
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
private const val ENTRY_CARDS = "POST /internal/participants/entry-cards"

private val HEADER = listOf("일시", "구분", "대상 종류", "이름", "직업", "종이 QR 구분", "종이 QR 코드", "스캐너")

/** 참여 서비스 계약(Attendance #150)을 스텁으로 검증한다. 실제 서버는 호출하지 않는다. */
class EntryRecordExcelServiceTests :
    StringSpec({
        lateinit var attendance: StubProvider
        lateinit var user: StubProvider
        lateinit var service: EntryRecordExcelServiceImpl

        beforeTest {
            attendance = StubProvider(ATTENDANCE_TOKEN)
            user = StubProvider(USER_TOKEN)
            service = EntryRecordExcelServiceImpl(AttendanceClient(attendance.client()), UserClient(user.client()))
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

        // User entry-cards처럼 요청 순서대로 아는 ID만 중복 없이 돌려주고, 없는 ID는 404 없이 뺀다. 응답의 전화번호·동의 정보도 흉내 낸다.
        fun entryCards(
            standard: Map<Long, Pair<String, String?>> = emptyMap(),
            trainee: Map<Long, String> = emptyMap(),
        ) {
            user.routes[ENTRY_CARDS] = { request ->
                val body = StubProvider.JSON.readTree(request.body)

                fun ids(key: String) =
                    body
                        .path(key)
                        .values()
                        .map(JsonNode::asLong)
                        .distinct()
                StubProvider.Reply(
                    200,
                    StubProvider.JSON.writeValueAsString(
                        mapOf(
                            "standardParticipants" to
                                ids("standardParticipantIds").filter { it in standard }.map {
                                    val (name, occupation) = standard.getValue(it)
                                    mapOf(
                                        "participantId" to it,
                                        "name" to name,
                                        "phoneNumber" to "010-0000-0000",
                                        "personalInformationStatus" to true,
                                        "occupation" to occupation,
                                        "school" to null,
                                    )
                                },
                            "trainees" to
                                ids("traineeIds").filter { it in trainee }.map {
                                    mapOf(
                                        "traineeId" to it,
                                        "name" to trainee.getValue(it),
                                        "phoneNumber" to null,
                                        "personalInformationStatus" to true,
                                    )
                                },
                        ),
                    ),
                )
            }
        }

        fun requestedIds(
            request: StubProvider.Request,
            key: String,
        ) = StubProvider.JSON
            .readTree(request.body)
            .path(key)
            .values()
            .map(JsonNode::asLong)

        fun bytes(file: ExcelFile) = ByteArrayOutputStream().also { out -> file.workbook.use { it.write(out) } }.toByteArray()

        fun read(bytes: ByteArray): Pair<List<List<String?>>, List<List<String?>>> {
            XSSFWorkbook(ByteArrayInputStream(bytes)).use {
                it.getSheetName(0) shouldBe "입장 기록"
                it.getSheetName(1) shouldBe "간편입장 집계"
                return it.getSheetAt(0).values() to it.getSheetAt(1).values()
            }
        }

        fun sheets(file: ExcelFile) = read(bytes(file))

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
            entryCards(standard = mapOf(7001L to ("홍길동" to "PARENT")), trainee = mapOf(9001L to "김연수"))

            val bytes = bytes(service.execute("e1", null, null))
            val (records, walkIn) = read(bytes)

            records.size shouldBe 1 + 2003
            records[0] shouldBe HEADER
            records[1] shouldBe listOf("2026-10-19 10:05:12", "입장", "일반 참가자", "홍길동", "학부모", null, null, "gate-1")
            records[2001] shouldBe listOf("2026-10-19 12:00:00", "퇴장", "연수자", "김연수", "교원", null, null, null)
            records[2002] shouldBe listOf("2026-10-19 12:30:00", "재입장", "일반 참가자", "홍길동", "학부모", null, null, "gate-1")
            records[2003] shouldBe listOf("2026-10-19 13:00:00", "입장", "종이 QR", null, "미확인", "성인", "ADT-0001-K7", "gate-2")
            walkIn shouldBe listOf(listOf("날짜", "참가 유형", "인원"), listOf("2026-10-19", "교원", "3.0"))

            // 이름·직업은 중복 없이 한 요청으로 묻고 내부 토큰을 공급자별로 보낸다. 이름 전용 API는 쓰지 않는다
            attendance.requests.map { it.token }.toSet() shouldBe setOf(ATTENDANCE_TOKEN)
            user.requests.map { it.token }.toSet() shouldBe setOf(USER_TOKEN)
            val request = user.requests.single()
            request.uri shouldBe "/internal/participants/entry-cards"
            StubProvider.JSON
                .readTree(request.body)
                .path("expoId")
                .asString() shouldBe "e1"
            requestedIds(request, "standardParticipantIds") shouldBe listOf(7001L)
            requestedIds(request, "traineeIds") shouldBe listOf(9001L)
            // 응답의 전화번호는 파일에 들어가지 않는다
            String(bytes, Charsets.ISO_8859_1) shouldNotContain "010-0000-0000"
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

        "일반 참가자 직업은 한국어 라벨, 연수자는 교원, 없는 대상·직업 미기록·종이 QR은 미확인, 모르는 코드는 원문이다" {
            attendance.on(
                ALL_PAGE_0,
                body =
                    page(
                        0,
                        11,
                        listOf(
                            record(id = 1),
                            record(id = 2, event = "RE_ENTER"),
                            record(id = 3, event = "EXIT"),
                            record(id = 4),
                            record(id = 5),
                            record(id = 6),
                            record(id = 7),
                            record(id = 99),
                            record(type = "TRAINEE", id = 9001, event = "EXIT"),
                            record(type = "TRAINEE", id = 9999),
                            record(type = "PAPER_QR", id = null, category = "CHILD", code = "CHD-0001-AB"),
                        ),
                    ),
            )
            entryCards(
                standard =
                    mapOf(
                        1L to ("가" to "TEACHER"),
                        2L to ("나" to "PRE_SERVICE_TEACHER"),
                        3L to ("다" to "ELEMENTARY_STUDENT"),
                        4L to ("라" to "PARENT"),
                        5L to ("마" to null),
                        6L to ("바" to " "),
                        7L to ("사" to "ASTRONAUT"),
                    ),
                trainee = mapOf(9001L to "김연수"),
            )

            val (records, _) = sheets(service.execute("e1", null, null))

            records.drop(1).map { it.subList(1, 5) } shouldContainExactly
                listOf(
                    listOf("입장", "일반 참가자", "가", "교원"),
                    listOf("재입장", "일반 참가자", "나", "예비교사"),
                    listOf("퇴장", "일반 참가자", "다", "초등학생"),
                    listOf("입장", "일반 참가자", "라", "학부모"),
                    listOf("입장", "일반 참가자", "마", "미확인"),
                    listOf("입장", "일반 참가자", "바", "미확인"),
                    listOf("입장", "일반 참가자", "사", "ASTRONAUT"),
                    // 삭제됐거나 다른 행사 ID라 응답에서 빠진 대상도 행은 남는다
                    listOf("입장", "일반 참가자", null, "미확인"),
                    listOf("퇴장", "연수자", "김연수", "교원"),
                    listOf("입장", "연수자", null, "미확인"),
                    listOf("입장", "종이 QR", null, "미확인"),
                )
            // 종이 QR 구분은 직업으로 추측하지 않고 기존 열에 그대로 둔다
            records[11][5] shouldBe "학생"
            user.requests.size shouldBe 1
        }

        "수식 모양 직업 코드는 문자열 셀로만 쓴다" {
            attendance.on(ALL_PAGE_0, body = page(0, 1, listOf(record(id = 1))))
            entryCards(standard = mapOf(1L to ("=1+1" to "=HYPERLINK(\"http://x\")")))

            XSSFWorkbook(ByteArrayInputStream(bytes(service.execute("e1", null, null)))).use {
                val row = it.getSheetAt(0).getRow(1)
                listOf(3, 4).forEach { column -> row.getCell(column).cellType shouldBe CellType.STRING }
                row.getCell(4).stringCellValue shouldBe "=HYPERLINK(\"http://x\")"
            }
        }

        "목록마다 중복 없이 1,000개씩 나눠 묻고 두 목록을 같은 요청에 담는다" {
            val standardIds = (1L..2500L).toList()
            val records =
                (standardIds + standardIds.take(10)).map { record(id = it) } + (1L..1200L).map { record(type = "TRAINEE", id = it) }
            attendance.on(ALL_PAGE_0, body = page(0, records.size, records.take(2000)))
            attendance.on(ALL_PAGE_1, body = page(1, records.size, records.drop(2000)))
            entryCards(standard = standardIds.associateWith { "참가자$it" to "TEACHER" }, trainee = (1L..1200L).associateWith { "연수자$it" })

            val (rows, _) = sheets(service.execute("e1", null, null))

            user.requests.map { requestedIds(it, "standardParticipantIds").size } shouldBe listOf(1000, 1000, 500)
            user.requests.map { requestedIds(it, "traineeIds").size } shouldBe listOf(1000, 200, 0)
            user.requests.flatMap { requestedIds(it, "standardParticipantIds") } shouldBe standardIds
            rows.drop(1).count { it[4] == "미확인" } shouldBe 0
        }

        "요청하지 않은 ID나 중복 ID가 응답에 오면 응답 오류로 전체 실패다" {
            attendance.on(ALL_PAGE_0, body = page(0, 1, listOf(record(id = 1))))
            listOf(
                """{"standardParticipants":[{"participantId":2,"name":"남","occupation":"TEACHER"}],"trainees":[]}""",
                """{"standardParticipants":[{"participantId":1,"name":"가","occupation":null},{"participantId":1,"name":"가","occupation":null}],"trainees":[]}""",
                """{"standardParticipants":[],"trainees":[{"traineeId":5,"name":"남"}]}""",
                """{"trainees":[]}""",
            ).forEach { reply ->
                user.on(ENTRY_CARDS, 200, reply)
                shouldThrow<ExpectedException> { service.execute("e1", null, null) }.status shouldBe HttpStatus.BAD_GATEWAY
            }
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
            records[1].subList(1, 6) shouldBe listOf("FUTURE_EVENT", "ROBOT", null, "미확인", "ALIEN")
            records[2][5] shouldBe "학생"
            walkIn[1][1] shouldBe "ALIEN"
        }

        "참가 유형 표시 이름 매핑" {
            mapOf(
                "KINDERGARTEN_STUDENT" to "미취학",
                "ELEMENTARY_STUDENT" to "초등학생",
                "MIDDLE_SCHOOL_STUDENT" to "중학생",
                "HIGH_SCHOOL_STUDENT" to "고등학생",
                "SCHOOL_STAFF" to "교직원",
                "PARENT" to "학부모",
                "GENERAL" to "일반인",
                "TEACHER" to "교원",
                "PRE_SERVICE_TEACHER" to "예비교사",
                "ADULT" to "성인",
                "STUDENT" to "어린이·학생",
                "CHILD" to "학생",
            ).forEach { (code, label) -> participationLabel(code) shouldBe label }
        }

        "한 페이지라도 실패하면 부분 결과로 파일을 만들지 않고 502다" {
            attendance.on(ALL_PAGE_0, body = page(0, 2003, List(2000) { record() }))
            attendance.on(ALL_PAGE_1, 500, """{"status":500,"message":"private-detail"}""")
            entryCards(standard = mapOf(7001L to ("홍길동" to "TEACHER")))

            val e = shouldThrow<ExpectedException> { service.execute("e1", null, null) }

            e.status shouldBe HttpStatus.BAD_GATEWAY
            e.message shouldNotContain "private-detail"
        }

        "이름·직업 조회가 실패하면 미확인으로 채우지 않고 전체가 실패한다" {
            attendance.on(ALL_PAGE_0, body = page(0, 1, listOf(record())))
            listOf(500, 503, 404).forEach { status ->
                user.on(ENTRY_CARDS, status, """{"status":$status}""")
                shouldThrow<ExpectedException> { service.execute("e1", null, null) }.status shouldBe HttpStatus.BAD_GATEWAY
            }
        }

        "총 건수에 못 미친 채 빈 페이지가 오면 부분 결과이므로 실패한다" {
            attendance.on(ALL_PAGE_0, body = page(0, 4000, List(2000) { record() }))
            attendance.on(ALL_PAGE_1, body = page(1, 4000, emptyList()))
            entryCards(standard = mapOf(7001L to ("홍길동" to "TEACHER")))
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
            val unconfigured = EntryRecordExcelServiceImpl(AttendanceClient(null), UserClient(user.client()))
            shouldThrow<ExpectedException> { unconfigured.execute("e1", null, null) }.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        }
    })
