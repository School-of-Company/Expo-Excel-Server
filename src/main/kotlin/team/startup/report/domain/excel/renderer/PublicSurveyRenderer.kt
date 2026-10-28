package team.startup.report.domain.excel.renderer

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import team.startup.report.domain.excel.service.PARTICIPANT_TYPE_LABELS
import team.startup.report.global.client.form.PublicSurveyAnswer
import team.startup.report.global.client.form.PublicSurveyAnswerPage
import team.startup.report.global.client.form.PublicSurveyQuestion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 익명 공개 설문 응답(Form #215)을 응답 1건 = 1행으로 쓴다. 참가자 명단과 합치지 않고 전화번호·경품 정보는 원천에도 없다.
// 셀 표기: 키 없음 = 빈 칸(미응답), 저장값 null = "(null)", CHECKBOX = "true"/"false", 선택형 = 보기 문구(MULTIPLE은 ", "로 잇는다).
// 정의에 없는 보기 키·형식이 다른 값은 추정하지 않고 "[미복원] 저장값"으로, 정의에 없는 문항 ID의 답변은 "미복원 답변" 열에 JSON으로 둔다.

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val KST_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val CELL_MAX_LEN = 32767
private val CONTROL = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")
private const val UNRESOLVED = "[미복원] "
private val FIXED_HEADERS = listOf("응답 ID", "설문 ID", "설문 제목", "제출 일시(KST)", "직업(자가 선택)")

/** 시트 "응답"(제출 순), "문항"(열 정의), "정보"(조회 조건·표기 규칙). 응답이 없어도 머리글은 있다. */
fun renderPublicSurveyAnswers(
    expoId: String,
    first: PublicSurveyAnswerPage,
    answers: List<PublicSurveyAnswer>,
    generatedAt: OffsetDateTime,
): SXSSFWorkbook {
    val workbook = newWorkbook(500)
    val headerStyle = darkHeaderStyle(workbook)
    val wrapStyle =
        workbook.createCellStyle().apply {
            thinBorders()
            wrapText = true
        }
    val questions = first.questions
    val headers = questionHeaders(questions)
    val knownIds = questions.mapTo(HashSet()) { it.id.toString() }
    // 정의에 없는 문항 ID의 답변. 한 건이라도 있을 때만 열을 만든다.
    val orphans = answers.map { a -> a.answers.propertyNames().filterNot(knownIds::contains) }
    val orphanColumn = orphans.any { it.isNotEmpty() }

    val sheet = workbook.createSheet("응답").apply { defaultColumnWidth = 20 }
    sheet.writeRow(0, FIXED_HEADERS + headers + listOfNotNull("미복원 답변".takeIf { orphanColumn }), headerStyle)
    answers.forEachIndexed { index, answer ->
        val occupation = answer.occupation?.let { PARTICIPANT_TYPE_LABELS[it] ?: it }
        val fixed = listOf(answer.id, first.survey.id, first.survey.title, kst(answer.createdAt), occupation)
        val byQuestion = questions.map { q -> answer.answers.get(q.id.toString())?.let { display(it, q) } }
        val orphan =
            (answer.answers as ObjectNode)
                .deepCopy()
                .retain(orphans[index])
                .takeIf { !it.isEmpty }
                ?.toString()
        sheet.writeRow(
            index + 1,
            (fixed + byQuestion + if (orphanColumn) listOf(orphan) else emptyList()).map { it?.let(::text) },
            wrapStyle,
        )
    }

    val questionSheet = workbook.createSheet("문항").apply { defaultColumnWidth = 24 }
    questionSheet.writeRow(0, listOf("문항 ID", "열 제목", "문항 제목", "유형", "보기(키 = 문구)"), headerStyle)
    questions.forEachIndexed { index, q ->
        val choices =
            q.jsonData?.takeIf { it.isObject }?.let { data ->
                data.propertyNames().joinToString("\n") { "$it = ${choiceText(data.get(it)) ?: data.get(it)}" }
            }
        questionSheet.writeRow(
            index + 1,
            listOf(q.id.toString(), headers[index], q.title, q.formType, choices).map { it?.let(::text) },
            wrapStyle,
        )
    }

    val info =
        workbook.createSheet("정보").apply {
            setColumnWidth(0, 20 * 256)
            setColumnWidth(1, 90 * 256)
        }
    listOf(
        "생성 일시(KST)" to generatedAt.atZoneSameInstant(SEOUL).format(KST_OUTPUT),
        "행사 ID" to expoId,
        "설문 ID" to first.survey.id,
        "설문 제목" to first.survey.title,
        "조회 조건" to "행사 공통 공개 설문의 전체 응답(직업과 무관한 설문 1개). 조회 상한 ${kst(first.snapshotAt)} 이하 제출분",
        "응답 행 수" to answers.size.toString(),
        "원천 응답 수" to first.totalCount.toString(),
        "문항 정의" to "조회 시점의 현재 문항 정의(${first.questionDefinition}). 응답이 있는 설문은 문항 변경이 막혀 있어 응답 당시 정의와 같습니다.",
        "표기 규칙" to
            "빈 칸 = 미응답(키 없음), (null) = null로 저장됨, true/false = 체크박스 값, " +
            "[미복원] = 현재 정의에 없는 보기 키 또는 형식이 다른 저장값, 미복원 답변 열 = 현재 정의에 없는 문항 ID의 답변(JSON)",
        "개인정보" to "익명 응답입니다. 참가자 명단과 연결하지 않으며 전화번호·경품 정보는 포함하지 않습니다.",
    ).forEachIndexed { index, (key, value) ->
        info.writeRow(index, listOf(key), headerStyle)
        info.getRow(index).createCell(1).apply {
            setCellValue(text(value))
            cellStyle = wrapStyle
        }
    }
    return workbook
}

/** 문항 제목. 같은 제목이 둘 이상이면 모두 "제목 (문항 ID n)"으로 구분한다. */
internal fun questionHeaders(questions: List<PublicSurveyQuestion>): List<String> {
    val counts = questions.groupingBy { it.title }.eachCount()
    return questions.map { if (counts.getValue(it.title) > 1) "${it.title} (문항 ID ${it.id})" else it.title }
}

internal fun display(
    value: JsonNode,
    question: PublicSurveyQuestion,
): String {
    if (value.isNull) return "(null)"
    return when (question.formType) {
        "CHECKBOX" -> if (value.isBoolean) value.asBoolean().toString() else UNRESOLVED + value
        "DROPDOWN" -> label(value, question.jsonData)
        "MULTIPLE" -> if (value.isArray) value.values().joinToString(", ") { label(it, question.jsonData) } else UNRESOLVED + value
        else -> if (value.isString) value.stringValue() else UNRESOLVED + value
    }
}

private fun label(
    choice: JsonNode,
    jsonData: JsonNode?,
): String =
    choice.takeIf { it.isString }?.let { jsonData?.get(it.stringValue()) }?.let(::choiceText)
        ?: (UNRESOLVED + if (choice.isString) choice.stringValue() else choice.toString())

/** jsonData 값은 "문구" 또는 {"value": "문구", "isAlwaysSelected": true} */
private fun choiceText(node: JsonNode?): String? =
    when {
        node == null -> null
        node.isString -> node.stringValue()
        node.isObject -> node.get("value")?.takeIf { it.isString }?.stringValue()
        else -> null
    }

// 공급자 형식이 달라도 값을 잃지 않도록 못 읽으면 원문을 쓴다
private fun kst(value: String): String =
    try {
        OffsetDateTime.parse(value).atZoneSameInstant(SEOUL).format(KST_OUTPUT)
    } catch (_: Exception) {
        value
    }

/** 줄바꿈(LF)은 살리고 CRLF·CR은 LF로, 나머지 제어 문자는 지운다. 엑셀 셀 한도에서 자른다. */
private fun text(value: String): String =
    value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(CONTROL, "")
        .take(CELL_MAX_LEN)
