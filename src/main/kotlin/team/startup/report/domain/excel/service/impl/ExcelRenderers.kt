package team.startup.report.domain.excel.service.impl

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import team.startup.report.domain.excel.service.ProgramParticipant
import team.startup.report.domain.excel.service.StandardParticipant
import team.startup.report.domain.excel.service.Trainee
import team.startup.report.domain.excel.service.TraineeAttendance
import team.startup.report.domain.excel.service.TrainingCategory
import team.startup.report.domain.excel.service.TrainingProgram
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// v1 Expo-Server 858101864235c9492fb2bd37ccf1df5346958d66 domain/excel/service/impl 의 시트 구성을 그대로 옮긴다.
// 시트명·열 제목·열 순서·값 표현·서식은 외부 계약이므로 v1 결함까지 포함해 바꾸지 않는다.

private const val EXCEL_CELL_MAX_LEN = 32767
private val DARK_HEADER_RGB = rgb(34, 37, 41)
private val WHITE_RGB = rgb(255, 255, 255)
private val LIGHT_GREY_RGB = rgb(242, 242, 242)

/** v1 StandardParticipantInfoToExcelServiceImpl */
fun renderStandardParticipants(participants: List<StandardParticipant>): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val sheet = workbook.createSheet("박람회 참가자 정보").apply { defaultColumnWidth = 20 }
    val headerStyle = darkHeaderStyle(workbook)
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    val answers = participants.map { it.information.oneLine() to it.surveyAnswer.oneLine() }
    // v1: 신청 폼 열은 첫 참가자의 키만, 설문 열은 전체 참가자 키의 합집합
    val infoKeys = answers.first().first.keys
    val surveyKeys = answers.flatMapTo(LinkedHashSet()) { it.second.keys }

    sheet.writeRow(0, listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식") + infoKeys + surveyKeys, headerStyle)
    participants.forEachIndexed { index, participant ->
        val (information, surveyAnswer) = answers[index]
        sheet.writeRow(
            index + 1,
            listOf(
                participant.name,
                participant.phoneNumber,
                consent(participant.personalInformationStatus),
                participant.applicationType.koreanName,
            ) + infoKeys.map { information[it] ?: "" } +
                surveyKeys.map { surveyAnswer[it] ?: "" },
            bodyStyle,
        )
    }
    return workbook
}

/** v1 TraineeInfoToExcelServiceImpl */
fun renderTrainees(trainees: List<Trainee>): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val sheet = workbook.createSheet("사전 교원연수자 정보").apply { defaultColumnWidth = 20 }
    val headerStyle = darkHeaderStyle(workbook)
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    val dynamicKeys = trainees.flatMapTo(LinkedHashSet()) { it.information.keys }
    val headers = listOf("이름", "연수원아이디", "전화번호", "신청방식") + dynamicKeys + listOf("공통 강연", "선택 강연")
    sheet.writeRow(0, headers.map(::safe), headerStyle)

    trainees.forEachIndexed { index, trainee ->
        val (common, selective) = trainee.programs.partition { it.category == TrainingCategory.ESSENTIAL }
        sheet.writeRow(
            index + 1,
            listOf(trainee.name, trainee.trainingId, trainee.phoneNumber, trainee.applicationType.koreanName)
                .plus(
                    dynamicKeys.map { key ->
                        val schoolKey = if ("학교명" in dynamicKeys) "학교명" else "소속"
                        trainee.information[key] ?: trainee.school.takeIf {
                            key == schoolKey && trainee.information["학교명"] == null && trainee.information["소속"] == null
                        }
                    },
                ).plus(listOf(common, selective).map { programs -> programs.map { it.title }.distinct().joinToString(", ") })
                .map(::safe),
            bodyStyle,
        )
    }
    return workbook
}

/** v1 ProgramParticipantInfoToExcelServiceImpl */
fun renderProgramParticipants(participants: List<ProgramParticipant>): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val sheet = workbook.createSheet("프로그램 참가자 정보").apply { defaultColumnWidth = 20 }
    val headerFont =
        workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
    val headerStyle =
        workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.BLACK.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            thinBorders()
            setFont(headerFont)
        }
    val bodyStyle = workbook.createCellStyle().apply { thinBorders() }

    sheet.writeRow(0, listOf("순위", "이름", "전화번호", "개인정보 동의 여부", "학번", "서명", "비고"), headerStyle)
    participants.forEachIndexed { index, participant ->
        val row = sheet.createRow(index + 1)
        row.createCell(0).apply {
            setCellValue((index + 1).toDouble())
            cellStyle = bodyStyle
        }
        listOf(participant.name, participant.phoneNumber, consent(participant.personalInformationStatus), "", "", "")
            .forEachIndexed { offset, value ->
                row.createCell(offset + 1).apply {
                    setCellValue(value)
                    cellStyle = bodyStyle
                }
            }
    }
    return workbook
}

private val KEYNOTE_WORDS = listOf("기조", "기조 강연")
private val SPECIAL_WORDS = listOf("특별")
private val RELAY_WORDS = listOf("교사", "릴레이")
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd.(E)", Locale.KOREAN)
private val DAY_FORMATTER = DateTimeFormatter.ofPattern("MM.dd.(E)", Locale.KOREAN)
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

/**
 * v1 TraineeAttendanceToExcelServiceImpl. 출석부는 신청 프로그램 일정으로 만드는 작성용 서식이다.
 * v1은 신청 행의 attendanceDate를 썼지만 v2는 그 값이 없으므로 [dates]는 신청 프로그램 시작일로 만든다.
 */
fun renderTraineeAttendance(
    attendance: TraineeAttendance,
    dates: List<LocalDate>,
): SXSSFWorkbook {
    val trainingDateText =
        if (dates.size == 1) {
            dates[0].format(DATE_FORMATTER)
        } else {
            dates[0].format(DATE_FORMATTER) + dates.drop(1).joinToString("") { "/" + it.format(DAY_FORMATTER) } + " 해당일 입력"
        }

    val programs = attendance.programs.distinct()
    val keynotes = programs.filter { it.title.containsAny(KEYNOTE_WORDS) }
    val hasSpecial = programs.any { it.title.containsAny(SPECIAL_WORDS) }
    val hasRelay = programs.any { it.title.containsAny(RELAY_WORDS) }
    val electiveAll = programs.filterNot { it.title.containsAny(KEYNOTE_WORDS + SPECIAL_WORDS + RELAY_WORDS) }
    val hasKeynote = keynotes.isNotEmpty()
    val elective = electiveAll.take(if (hasKeynote) 2 else 4)

    val giNumbers =
        buildList {
            if (hasKeynote) add(elective.size.coerceIn(1, 2))
            if (hasSpecial) add(2 + elective.size.coerceIn(1, 4))
            if (hasRelay) add(6 + elective.size.coerceIn(1, 4))
        }
    val giText = if (giNumbers.isEmpty()) "" else giNumbers.joinToString(",", prefix = " (", postfix = "기)")
    val courseName = "${attendance.expoTitle} 직무연수$giText"

    var schoolName = "학교명"
    var traineeName = attendance.traineeName
    var trainingId = attendance.trainingId
    // ponytail: v1은 문자열이 아닌 값을 만나면 ClassCastException을 삼켜 그 앞까지 반영한 값을 쓴다
    runCatching {
        val info = attendance.information
        schoolName = info["학교명"] as String? ?: info["소속"] as String? ?: attendance.school ?: "학교명"
        traineeName = info["이름"] as String? ?: traineeName
        trainingId = info["연수원아이디"] as String? ?: trainingId
    }

    val workbook = newWorkbook(200)
    val sheet = workbook.createSheet("출석부")
    sheet.defaultColumnWidth = 14
    listOf(1500, 5500, 3500, 6500, 6500, 6500, 6500, 6500, 3800, 3800)
        .forEachIndexed { column, width -> sheet.setColumnWidth(column, width) }

    val defaultFont = (workbook.createFont() as XSSFFont).apply { fontHeightInPoints = 11 }
    val headerStyle =
        (workbook.createCellStyle() as XSSFCellStyle).apply {
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setFont(defaultFont)
            thinBorders()
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(LIGHT_GREY_RGB)
            wrapText = true
        }
    val bodyStyle =
        workbook.createCellStyle().apply {
            thinBorders()
            verticalAlignment = VerticalAlignment.CENTER
            alignment = HorizontalAlignment.CENTER
            setFont(defaultFont)
        }
    val programCellStyle =
        (workbook.createCellStyle() as XSSFCellStyle).apply {
            cloneStyleFrom(bodyStyle)
            wrapText = true
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(LIGHT_GREY_RGB)
        }
    val infoLabelStyle =
        (workbook.createCellStyle() as XSSFCellStyle).apply {
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borders(BorderStyle.DOTTED)
            setFont(
                workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 11
                },
            )
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(LIGHT_GREY_RGB)
        }
    val infoValueFont =
        workbook.createFont().apply {
            fontHeightInPoints = 11
            color = IndexedColors.BLUE.index
        }
    val infoValueStyle =
        workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
            borders(BorderStyle.DOTTED)
            setFont(infoValueFont)
        }

    var rowIdx = 0
    val titleRow = sheet.createRow(rowIdx++).apply { heightInPoints = 40f }
    titleRow.createCell(0).apply {
        setCellValue("출  석  부")
        cellStyle =
            workbook.createCellStyle().apply {
                setFont(
                    workbook.createFont().apply {
                        bold = true
                        fontHeightInPoints = 18
                    },
                )
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
    }
    sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 9))
    rowIdx++

    // ponytail: 장소·담당자는 v1 하드코딩 값 그대로. 행사별 값으로 바꾸는 것은 외부 계약 변경이라 별도 제안.
    listOf(
        "과정명" to courseName,
        "연수일시" to trainingDateText,
        "장소" to "김대중컨벤션센터",
        "담당자" to "남인혜 장학사(380-4587)",
    ).forEach { (label, value) ->
        val row = sheet.createRow(rowIdx++).apply { heightInPoints = 30f }
        row.createCell(0).apply {
            setCellValue(label)
            cellStyle = infoLabelStyle
        }
        row.createCell(1).cellStyle = infoLabelStyle
        row.createCell(2).apply {
            setCellValue(value)
            cellStyle = infoValueStyle
        }
        sheet.addMergedRegion(CellRangeAddress(row.rowNum, row.rowNum, 0, 1))
        sheet.addMergedRegion(CellRangeAddress(row.rowNum, row.rowNum, 2, 9))
    }
    rowIdx++

    // v1 결함 유지: 날짜별 블록마다 같은 강연 목록을 반복한다(날짜별로 나누지 않음).
    val keynote = keynotes.firstOrNull()
    val keynoteText = if (keynote == null) "기조강연\n(시간)" else "${keynote.title}\n${timeRange(keynote)}"
    dates.forEachIndexed { dateIdx, date ->
        val topRow = sheet.createRow(rowIdx++).apply { heightInPoints = 40f }
        val bottomRow = sheet.createRow(rowIdx++).apply { heightInPoints = 34f }
        repeat(10) { topRow.createCell(it).cellStyle = headerStyle }
        mapOf(0 to "연번", 1 to "소속", 2 to "성명", 3 to date.format(DAY_FORMATTER), 8 to "이수여부\n(이수/미이수)", 9 to "비고\n(연수원 아이디)")
            .forEach { (column, value) -> topRow.getCell(column).setCellValue(value) }
        listOf("연번", "소속", "성명", "공통", "선택1", "선택2", "선택3", "선택4", "이수여부", "비고\n(연수원 아이디)")
            .forEachIndexed { column, value ->
                bottomRow.createCell(column).apply {
                    setCellValue(value)
                    cellStyle = headerStyle
                }
            }

        val programRow = sheet.createRow(rowIdx++).apply { heightInPoints = 60f }
        (0..2).forEach { programRow.createCell(it).cellStyle = bodyStyle }
        programRow.createCell(3).apply {
            setCellValue(keynoteText)
            cellStyle = programCellStyle
        }
        (0 until 4).forEach { i ->
            programRow.createCell(4 + i).apply {
                setCellValue(elective.getOrNull(i)?.let { "${it.title}\n${timeRange(it)}" } ?: "")
                cellStyle = programCellStyle
            }
        }
        (8..9).forEach { programRow.createCell(it).cellStyle = bodyStyle }

        val top = topRow.rowNum
        val bottom = programRow.rowNum
        listOf(
            CellRangeAddress(top, bottom, 0, 0),
            CellRangeAddress(top, bottom, 1, 1),
            CellRangeAddress(top, bottom, 2, 2),
            CellRangeAddress(top, top, 3, 7),
            CellRangeAddress(top, bottom, 8, 8),
            CellRangeAddress(top, bottom, 9, 9),
        ).forEach(sheet::addMergedRegion)

        val dataRow = sheet.createRow(rowIdx++).apply { heightInPoints = 34f }
        listOf("1", schoolName, traineeName, null, null, null, null, null, null, trainingId)
            .forEachIndexed { column, value ->
                dataRow.createCell(column).apply {
                    value?.let(::setCellValue)
                    cellStyle = bodyStyle
                }
            }

        if (dateIdx < dates.size - 1) rowIdx++
    }
    return workbook
}

internal fun newWorkbook(rowAccessWindowSize: Int) = SXSSFWorkbook(rowAccessWindowSize).apply { setCompressTempFiles(true) }

private fun rgb(
    red: Int,
    green: Int,
    blue: Int,
) = XSSFColor(byteArrayOf(red.toByte(), green.toByte(), blue.toByte()), null)

internal fun darkHeaderStyle(workbook: SXSSFWorkbook): XSSFCellStyle {
    val font =
        (workbook.createFont() as XSSFFont).apply {
            bold = true
            setColor(WHITE_RGB)
        }
    return (workbook.createCellStyle() as XSSFCellStyle).apply {
        setFillForegroundColor(DARK_HEADER_RGB)
        fillPattern = FillPatternType.SOLID_FOREGROUND
        thinBorders()
        setFont(font)
    }
}

internal fun CellStyle.thinBorders() = borders(BorderStyle.THIN)

private fun CellStyle.borders(style: BorderStyle) {
    borderTop = style
    borderBottom = style
    borderLeft = style
    borderRight = style
}

/** null이면 POI가 빈 셀(BLANK)로 만든다. v1 setCellValue(null)과 같다. */
internal fun Sheet.writeRow(
    rowIndex: Int,
    values: List<String?>,
    style: CellStyle,
) {
    val row = createRow(rowIndex)
    values.forEachIndexed { column, value ->
        row.createCell(column).apply {
            setCellValue(value)
            cellStyle = style
        }
    }
}

private fun consent(status: Boolean?) = if (status == true) "동의" else "미동의"

private val LINE_BREAK = Regex("\r?\n")

/**
 * v1 sanitizeJson: 원본 JSON의 줄바꿈(CRLF·LF)을 공백 하나로 바꾼 뒤 파싱했다. 값은 v1 String.valueOf처럼 문자열로 만든다.
 * 바꾼 뒤 제목이 겹치면 MappedAnswers의 같은 제목 규칙처럼 앞(order가 앞선) 값을 쓴다. 홀로 있는 \r은 v1에서 행 전체가 비던 결함이라 그대로 둔다.
 */
private fun Map<String, Any?>.oneLine(): Map<String, String?> =
    LinkedHashMap<String, String?>().also { result ->
        forEach { (key, value) -> result.putIfAbsent(key.replace(LINE_BREAK, " "), value?.toString()?.replace(LINE_BREAK, " ")) }
    }

private fun String?.containsAny(words: List<String>) = this != null && words.any { contains(it) }

private fun timeRange(program: TrainingProgram) =
    "(${program.startDateTime.format(TIME_FORMATTER)}~${program.endDateTime.format(TIME_FORMATTER)})"

/** v1 TraineeInfoToExcelServiceImpl.safe */
internal fun safe(value: String?): String =
    (value ?: "")
        .replace("\r", "")
        .replace("\n", "")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        .take(EXCEL_CELL_MAX_LEN)
