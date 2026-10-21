package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.client.FormClient
import team.startup.report.global.client.PublicSurveyAnswer
import team.startup.report.global.client.PublicSurveyAnswerPage
import team.startup.report.global.exception.ExpectedException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GET /excel/public-surveys/{expo_id}. Form 공개 설문 응답을 마지막 커서까지 읽어 응답 1건 = 1행으로 쓴다.
 * 페이지마다 설문·상한 시각·총 건수가 첫 페이지와 같아야 하고, 다 읽은 행 수가 총 건수와 같아야 한다. 하나라도 어긋나거나
 * 한 페이지라도 실패하면 부분 결과로 파일을 만들지 않는다. 답변·커서·토큰은 로그에 남기지 않는다.
 */
@Service
class PublicSurveyExcelService(
    private val form: FormClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(expoId: String): ExcelFile =
        try {
            val (first, answers) = readAll(expoId)
            val now = OffsetDateTime.now(SEOUL)
            val filename = UriUtils.encode("만족도조사응답_${now.format(TIMESTAMP)}.xlsx", Charsets.UTF_8)
            ExcelFile("attachment; filename*=UTF-8''$filename", renderPublicSurveyAnswers(expoId, first, answers, now))
        } catch (e: ExpectedException) {
            throw e
        } catch (e: Exception) {
            // 메시지는 공급자 이름과 상태 또는 아래 검사 문구만 담는다(원인·URL·본문 없음)
            log.error("공개 설문 응답 엑셀 생성 실패: {} {}", e.javaClass.simpleName, e.message)
            throw ExpectedException(HttpStatus.BAD_GATEWAY, "설문 응답 원천 조회 또는 파일 생성에 실패했습니다.")
        }

    private fun readAll(expoId: String): Pair<PublicSurveyAnswerPage, List<PublicSurveyAnswer>> {
        val first =
            form.publicSurveyAnswerPage(expoId, null)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "행사의 공개 설문을 찾을 수 없습니다.")
        check(first.survey.expoId.equals(expoId, ignoreCase = true) && first.questionDefinition == "CURRENT") { CONTRACT }
        check(
            first.questions
                .map { it.id }
                .toSet()
                .size == first.questions.size,
        ) { CONTRACT }
        if (first.totalCount > MAX_ANSWERS) {
            throw ExpectedException(HttpStatus.PAYLOAD_TOO_LARGE, "설문 응답은 최대 ${MAX_ANSWERS}건까지 다운로드할 수 있습니다.")
        }
        val answers = mutableListOf<PublicSurveyAnswer>()
        val ids = HashSet<String>()
        val cursors = HashSet<String>()
        var page = first
        while (true) {
            check(page.survey == first.survey && page.snapshotAt == first.snapshotAt && page.totalCount == first.totalCount) { CONTRACT }
            check(page.answers.size <= FormClient.PAGE_SIZE && page.answers.all { it.answers.isObject && ids.add(it.id) }) { CONTRACT }
            answers += page.answers
            check(answers.size <= first.totalCount) { CONTRACT }
            val cursor = page.nextCursor ?: break
            // 빈 페이지에 커서가 오거나 커서가 되풀이되면 끝나지 않으므로 실패다
            check(page.answers.isNotEmpty() && cursors.add(cursor)) { CONTRACT }
            // 읽는 도중 설문이 지워지면 404다. 부분 결과이므로 실패다
            page = form.publicSurveyAnswerPage(expoId, cursor) ?: error("Form 서비스 응답 오류(HTTP 404)")
        }
        check(answers.size.toLong() == first.totalCount) { "Form 서비스 응답 행 수 불일치" }
        return first to answers
    }

    private companion object {
        const val CONTRACT = "Form 서비스 응답 형식 오류"

        // 페이지 1000건 × 100번 호출. 공개 설문 실측 응답은 수십 건이라 여유가 크다(실측 후 조정)
        const val MAX_ANSWERS = 100_000L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
