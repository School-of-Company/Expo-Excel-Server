package team.startup.report.global.client

import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import team.startup.report.global.exception.ExpectedException
import tools.jackson.databind.JsonNode

/**
 * Expo-Form-Server 내부 조회(Form #215). 행사 공통 공개 설문 응답을 (createdAt, id) 오름차순 커서 페이지로 받는다.
 * 주소·토큰은 선택 설정이라 [http]가 null이면(미설정) 호출 시 503이다. 응답에는 참가자 ID·이름·경품 전화번호가 없다.
 */
class FormClient(
    private val http: RestClient?,
    private val resilience: ClientResilience = ClientResilience(NAME),
) {
    /** 행사에 공개 설문이 없으면 null(공급자 404). 커서는 공급자가 준 값을 그대로 넘기므로 공급자 400은 계약 오류로 보고 실패다. */
    fun publicSurveyAnswerPage(
        expoId: String,
        cursor: String?,
    ): PublicSurveyAnswerPage? {
        val client = http ?: throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "설문 서비스 연결이 설정되지 않았습니다.")
        return client
            .get()
            .uri {
                it
                    .path("/internal/surveys/{expoId}/public-answers")
                    // 커서는 불투명 문자열이라 템플릿 변수로 넘겨 '+'·'/'·'=' 등도 그대로 전달되도록 전부 인코딩한다
                    .apply { if (cursor != null) queryParam("cursor", "{cursor}") }
                    .queryParam("limit", PAGE_SIZE)
                    .build(*listOfNotNull(expoId, cursor).toTypedArray())
            }.fetchOrNull(resilience.read)
    }

    companion object {
        const val NAME = "Form"

        // Form #215 계약의 limit 최대는 1000이다
        const val PAGE_SIZE = 1000
    }
}

data class PublicSurvey(
    val id: String,
    val expoId: String,
    val title: String,
)

/** 현재 문항 정의(questionDefinition = CURRENT). 응답이 있는 설문은 Form이 문항 변경을 막는다. */
data class PublicSurveyQuestion(
    val id: Long,
    val title: String,
    val formType: String,
    val jsonData: JsonNode? = null,
)

/** 익명 응답 한 건. [answers]는 문항 ID 문자열 키의 저장값 그대로다. */
data class PublicSurveyAnswer(
    val id: String,
    // 마이크로초 UTC ISO 문자열
    val createdAt: String,
    val occupation: String? = null,
    val answers: JsonNode,
)

/** [snapshotAt]·[totalCount]는 첫 조회에서 고정되어 모든 페이지가 같다. [nextCursor]가 null이면 끝이다. */
data class PublicSurveyAnswerPage(
    val survey: PublicSurvey,
    val questionDefinition: String,
    val questions: List<PublicSurveyQuestion>,
    val snapshotAt: String,
    val totalCount: Long,
    val answers: List<PublicSurveyAnswer>,
    val nextCursor: String? = null,
)
