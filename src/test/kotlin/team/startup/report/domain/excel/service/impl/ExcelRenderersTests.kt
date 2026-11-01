package team.startup.report.domain.excel.service.impl

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import team.startup.report.domain.excel.renderer.renderProgramParticipants
import team.startup.report.domain.excel.renderer.renderStandardParticipants
import team.startup.report.domain.excel.renderer.renderTraineeAttendance
import team.startup.report.domain.excel.renderer.renderTrainees
import team.startup.report.domain.excel.service.ApplicationType
import team.startup.report.domain.excel.service.ProgramApplicationColumns
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.support.assertDarkHeader
import team.startup.report.support.assertThinBorders
import team.startup.report.support.cell
import team.startup.report.support.rgbFill
import team.startup.report.support.values
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

// v1 Expo-Server 858101864235c9492fb2bd37ccf1df5346958d66 domain/excel 의 출력 계약. ZIP 바이트가 아니라 셀·서식 의미로 비교한다.
class ExcelRenderersTests :
    StringSpec({
        "일반 참가자: v1 시트명·열·값·서식" {
            val sheet =
                read(
                    renderStandardParticipants(
                        listOf(
                            StandardParticipant(
                                "홍길동",
                                "01011112222",
                                true,
                                ApplicationType.PRE,
                                linkedMapOf("학교명" to "가초", "학년" to 3),
                                linkedMapOf("만족도" to "5"),
                            ),
                            StandardParticipant(
                                "김철수",
                                null,
                                null,
                                ApplicationType.FIELD,
                                linkedMapOf("학교명" to "나초", "추가" to "x"),
                                emptyMap(),
                            ),
                            StandardParticipant(
                                "이영희",
                                "0103",
                                false,
                                ApplicationType.PRE,
                                emptyMap(),
                                linkedMapOf("만족도" to null, "의견" to listOf("a", "b")),
                            ),
                        ),
                    ),
                ).getSheet("박람회 참가자 정보")

            // 신청 폼·설문 열 모두 합집합(뒤 참가자 전용 '추가' 포함). 동의 null은 미기록, false만 미동의
            sheet.values() shouldBe
                listOf(
                    listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식", "학교명", "학년", "추가", "만족도", "의견"),
                    listOf("홍길동", "01011112222", "동의", "사전 등록", "가초", "3", "", "5", ""),
                    listOf("김철수", null, "미기록", "현장 등록", "나초", "", "x", "", ""),
                    listOf("이영희", "0103", "미동의", "사전 등록", "", "", "", "", "[a, b]"),
                )
            sheet.defaultColumnWidth shouldBe 20
            sheet.cell(0, 0).assertDarkHeader()
            sheet.cell(2, 1).cellType shouldBe CellType.BLANK
            sheet.cell(1, 8).cellStyle.assertThinBorders()
            sheet.cell(1, 8).cellStyle.fillPattern shouldBe FillPatternType.NO_FILL
        }

        "일반 참가자: v1 sanitizeJson처럼 동적 열 제목·값의 줄바꿈(CRLF·LF)은 공백 하나, 고정 열은 그대로" {
            val sheet =
                read(
                    renderStandardParticipants(
                        listOf(
                            StandardParticipant(
                                "홍\n길동",
                                "010",
                                true,
                                ApplicationType.PRE,
                                linkedMapOf("주소\n상세" to "광주\r\n북구", "a\nb" to "앞", "a b" to "뒤"),
                                linkedMapOf("의견" to "좋아요\n다음에도", "점수" to 5),
                            ),
                        ),
                    ),
                ).getSheet("박람회 참가자 정보")

            // 정규화 후 겹친 제목은 한 열이고, MappedAnswers처럼 앞(order가 앞선) 값을 쓴다
            sheet.values() shouldBe
                listOf(
                    listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식", "주소 상세", "a b", "의견", "점수"),
                    listOf("홍\n길동", "010", "동의", "사전 등록", "광주 북구", "앞", "좋아요 다음에도", "5"),
                )
        }

        "일반 참가자: 홀로 있는 CR·탭은 v1에서 행이 비던 결함이라 재현하지 않고 그대로 둔다" {
            val sheet =
                read(
                    renderStandardParticipants(
                        listOf(
                            StandardParticipant(
                                "홍길동",
                                "010",
                                true,
                                ApplicationType.PRE,
                                linkedMapOf("메모" to "x\ry", "비고" to "x\ty"),
                                emptyMap(),
                            ),
                        ),
                    ),
                ).getSheet("박람회 참가자 정보")

            sheet.values()[1].takeLast(2) shouldBe listOf("x\ry", "x\ty")
        }

        "일반 참가자: 0명은 머리글만 출력한다" {
            read(renderStandardParticipants(emptyList())).getSheet("박람회 참가자 정보").values() shouldBe
                listOf(listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식"))
        }

        "연수자: v1 열·safe 정리·공통/선택 강연" {
            val sheet =
                read(
                    renderTrainees(
                        listOf(
                            Trainee(
                                "박교사",
                                "T-1",
                                "010",
                                ApplicationType.PRE,
                                linkedMapOf("학교" to "가초", "과목" to "수학"),
                                listOf(
                                    program(1, "기조 강연", TrainingCategory.ESSENTIAL),
                                    program(2, "AI", TrainingCategory.CHOICE),
                                    program(3, "AI", TrainingCategory.CHOICE),
                                    program(4, "특강", null),
                                ),
                            ),
                            Trainee("최\n교사", null, "011", ApplicationType.FIELD, linkedMapOf("경력" to "5년\r\n", "과목" to null), emptyList()),
                        ),
                    ),
                ).getSheet("사전 교원연수자 정보")

            sheet.values() shouldBe
                listOf(
                    listOf("이름", "연수원아이디", "전화번호", "알림 연락처", "신청방식", "학교", "과목", "경력", "공통 강연", "선택 강연"),
                    listOf("박교사", "T-1", "010", "", "사전 등록", "가초", "수학", "", "기조 강연", "AI, 특강"),
                    listOf("최교사", "", "011", "", "현장 등록", "", "", "5년", "", ""),
                )
            sheet.defaultColumnWidth shouldBe 20
            sheet.cell(0, 8).assertDarkHeader()
            sheet.cell(2, 1).cellType shouldBe CellType.STRING
        }

        "프로그램 참가자: 순위는 숫자, 서식은 인덱스 색" {
            val sheet =
                read(
                    renderProgramParticipants(
                        listOf(ProgramParticipant("가", "010", true), ProgramParticipant("나", null, false)),
                    ),
                ).getSheet("프로그램 참가자 정보")

            sheet.values() shouldBe
                listOf(
                    listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "서명", "비고"),
                    listOf("1.0", "가", "010", "동의", "", ""),
                    listOf("2.0", "나", null, "미동의", "", ""),
                )
            sheet.cell(1, 0).cellType shouldBe CellType.NUMERIC
            val header = sheet.cell(0, 0).cellStyle
            header.fillForegroundColor shouldBe IndexedColors.BLACK.index
            header.fillPattern shouldBe FillPatternType.SOLID_FOREGROUND
            header.font.bold shouldBe true
            header.font.color shouldBe IndexedColors.WHITE.index
            header.assertThinBorders()
        }

        "프로그램 참가자 0명: 머리글만" {
            read(renderProgramParticipants(emptyList())).getSheet("프로그램 참가자 정보").values().size shouldBe 1
        }

        "프로그램 상세: 동적 학번과 서명 비고는 보존하고 모든 행을 머리글에 맞춘다" {
            val sheet =
                read(
                    renderProgramParticipants(
                        listOf(
                            ProgramParticipant(
                                "가",
                                null,
                                true,
                                ProgramApplicationColumns(
                                    "학교",
                                    "학생",
                                    "현장",
                                    "2026-10-22",
                                    linkedMapOf(
                                        "학번" to "S-1",
                                        "확인" to "false",
                                    ),
                                ),
                            ),
                            ProgramParticipant("나", null, null),
                        ),
                    ),
                ).getSheetAt(0)
            sheet.values() shouldBe
                listOf(
                    listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "소속", "직업", "신청 경로", "신청 시각", "학번", "확인", "서명", "비고"),
                    listOf("1.0", "가", null, "동의", "학교", "학생", "현장", "2026-10-22", "S-1", "false", "", ""),
                    listOf("2.0", "나", null, "미기록", null, null, null, null, null, null, "", ""),
                )
            sheet.forEach { row ->
                row.lastCellNum.toInt() shouldBe 12
                (0 until 12).forEach { sheet.cell(row.rowNum, it).cellStyle.assertThinBorders() }
            }
            sheet.defaultColumnWidth shouldBe 20
            sheet.workbook.getPrintArea(0) shouldBe null
        }

        "출석부: v1 레이아웃·병합·인쇄 서식" {
            val sheet =
                read(
                    renderTraineeAttendance(
                        TraineeAttendance(
                            expoTitle = "2026 AI 박람회",
                            traineeName = "홍길동",
                            trainingId = "TR-1",
                            location = "테스트전시장",
                            information = linkedMapOf("학교명" to "광주초", "이름" to "홍길동(폼)"),
                            programs =
                                listOf(
                                    program(1, "기조 강연", TrainingCategory.ESSENTIAL, "2026-05-01 10:00", "2026-05-01 11:00"),
                                    program(2, "AI 교육", TrainingCategory.CHOICE, "2026-05-01 13:00", "2026-05-01 14:30"),
                                    program(3, "코딩", TrainingCategory.CHOICE, "2026-05-02T09:00", "2026-05-02T10:00"),
                                    program(4, "메이커", TrainingCategory.CHOICE, "2026-05-02 11:00", "2026-05-02 12:00"),
                                    program(5, "특별 강연", TrainingCategory.CHOICE, "2026-05-02 15:00", "2026-05-02 16:00"),
                                ),
                        ),
                        listOf(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2)),
                    ),
                ).getSheet("출석부")

            val header = listOf("연번", "소속", "성명")
            val bottom = header + listOf("공통", "선택1", "선택2", "선택3", "선택4", "이수여부", "비고\n(연수원 아이디)")
            val tail = listOf("이수여부\n(이수/미이수)", "비고\n(연수원 아이디)")
            val blankHeader = listOf<String?>(null, null, null, null)
            val programs = listOf(null, null, null, "기조 강연\n(10:00~11:00)", "AI 교육\n(13:00~14:30)", "코딩\n(09:00~10:00)", "", "", null, null)
            val data = listOf("1", "광주초", "홍길동(폼)", null, null, null, null, null, null, "TR-1")
            sheet.values() shouldBe
                listOf(
                    listOf("출  석  부"),
                    listOf("과정명", null, "2026 AI 박람회 직무연수 (2,4기)"),
                    listOf("연수일시", null, "2026.05.01.(금)/05.02.(토) 해당일 입력"),
                    listOf("장소", null, "테스트전시장"),
                    listOf("담당자", null, ""),
                    header + "05.01.(금)" + blankHeader + tail,
                    bottom,
                    programs,
                    data,
                    header + "05.02.(토)" + blankHeader + tail,
                    bottom,
                    programs,
                    data,
                )
            sheet.rowNums() shouldContainExactly listOf(0, 2, 3, 4, 5, 7, 8, 9, 10, 12, 13, 14, 15)
            sheet.rowHeights() shouldContainExactly listOf(40f, 30f, 30f, 30f, 30f, 40f, 34f, 60f, 34f, 40f, 34f, 60f, 34f)
            (0..9).map { sheet.getColumnWidth(it) } shouldContainExactly listOf(1500, 5500, 3500, 6500, 6500, 6500, 6500, 6500, 3800, 3800)
            sheet.defaultColumnWidth shouldBe 14
            sheet.mergedRegions.map { it.formatAsString() } shouldContainExactly
                listOf("A1:J1") +
                (3..6).flatMap { listOf("A$it:B$it", "C$it:J$it") } +
                listOf(
                    8,
                    13,
                ).flatMap {
                    listOf(
                        "A$it:A${it + 2}",
                        "B$it:B${it + 2}",
                        "C$it:C${it + 2}",
                        "D$it:H$it",
                        "I$it:I${it + 2}",
                        "J$it:J${it + 2}",
                    )
                }

            val title = sheet.cell(0, 0).cellStyle
            title.font.bold shouldBe true
            title.font.fontHeightInPoints shouldBe 18
            title.fillForegroundColor shouldBe IndexedColors.GREY_25_PERCENT.index
            title.alignment shouldBe HorizontalAlignment.CENTER

            val label = sheet.cell(2, 0).cellStyle
            label.font.bold shouldBe true
            label.borderTop shouldBe BorderStyle.DOTTED
            label.rgbFill() shouldBe "F2F2F2"
            val value = sheet.cell(2, 2).cellStyle
            value.font.color shouldBe IndexedColors.BLUE.index
            value.alignment shouldBe HorizontalAlignment.LEFT
            value.borderRight shouldBe BorderStyle.DOTTED

            val headerStyle = sheet.cell(7, 4).cellStyle
            headerStyle.wrapText shouldBe true
            headerStyle.rgbFill() shouldBe "F2F2F2"
            headerStyle.font.fontHeightInPoints shouldBe 11
            headerStyle.assertThinBorders()
            sheet.cell(9, 3).cellStyle.let {
                it.wrapText shouldBe true
                it.rgbFill() shouldBe "F2F2F2"
                it.alignment shouldBe HorizontalAlignment.CENTER
            }
            sheet.cell(10, 9).cellStyle.let {
                it.fillPattern shouldBe FillPatternType.NO_FILL
                it.assertThinBorders()
            }
        }

        "출석부: 장소 값이 없으면 고정값 없이 빈 칸" {
            read(
                renderTraineeAttendance(
                    TraineeAttendance(
                        expoTitle = "박람회",
                        traineeName = "홍길동",
                        trainingId = null,
                        information = emptyMap(),
                        programs = listOf(program(1, "기조 강연", TrainingCategory.ESSENTIAL)),
                    ),
                    listOf(LocalDate.of(2026, 5, 1)),
                ),
            ).use { workbook ->
                val sheet = workbook.getSheet("출석부")
                sheet.cell(4, 2).stringCellValue shouldBe ""
                sheet.cell(5, 2).stringCellValue shouldBe ""
            }
        }

        "출석부: 연수 번호가 null이면 폼의 연수원아이디를 기록한다" {
            read(
                renderTraineeAttendance(
                    TraineeAttendance(
                        expoTitle = "박람회",
                        traineeName = "홍길동",
                        trainingId = null,
                        information = mapOf("연수원아이디" to "FORM-1"),
                        programs = listOf(program(1, "기조 강연", TrainingCategory.ESSENTIAL)),
                    ),
                    listOf(LocalDate.of(2026, 5, 1)),
                ),
            ).use { workbook ->
                workbook.getSheet("출석부").cell(10, 9).stringCellValue shouldBe "FORM-1"
            }
        }

        "출석부: 기조강연 없음·릴레이 기수, 단일 날짜, 문자열이 아닌 폼 값" {
            val sheet =
                read(
                    renderTraineeAttendance(
                        TraineeAttendance(
                            expoTitle = "박람회",
                            traineeName = "홍길동",
                            trainingId = null,
                            information = linkedMapOf("소속" to "나중", "이름" to 3, "연수원아이디" to "무시됨"),
                            programs =
                                listOf("A", "B", "C", "D", "E", "교사 릴레이").mapIndexed {
                                    i,
                                    t,
                                    ->
                                    program(i.toLong(), t, TrainingCategory.CHOICE)
                                },
                        ),
                        listOf(LocalDate.of(2026, 5, 1)),
                    ),
                ).getSheet("출석부")

            sheet.cell(2, 2).stringCellValue shouldBe "박람회 직무연수 (10기)"
            sheet.cell(3, 2).stringCellValue shouldBe "2026.05.01.(금)"
            sheet.values()[7].subList(3, 8) shouldBe
                listOf("기조강연\n(시간)", "A\n(10:00~11:00)", "B\n(10:00~11:00)", "C\n(10:00~11:00)", "D\n(10:00~11:00)")
            // v1: "이름" 캐스팅 실패 전까지 반영 → 소속은 반영, 이름·연수원아이디는 원래 값
            sheet.values()[8] shouldBe listOf("1", "나중", "홍길동", null, null, null, null, null, null, null)
            sheet.lastRowNum shouldBe 10
        }
    })

private fun program(
    id: Long,
    title: String?,
    category: TrainingCategory?,
    startedAt: String = "2026-05-01 10:00",
    endedAt: String = "2026-05-01 11:00",
) = TrainingProgram(id, title, category, startedAt, endedAt)

private fun read(workbook: SXSSFWorkbook): XSSFWorkbook {
    val bytes =
        ByteArrayOutputStream().use { output ->
            workbook.use {
                it.write(output)
                it.dispose()
            }
            output.toByteArray()
        }
    return XSSFWorkbook(ByteArrayInputStream(bytes))
}

private fun Sheet.rowNums() = map { it.rowNum }

private fun Sheet.rowHeights() = map { it.heightInPoints }
