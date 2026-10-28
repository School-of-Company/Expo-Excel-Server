package team.startup.report.domain.excel.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import team.startup.report.domain.excel.renderer.renderStandardParticipants
import team.startup.report.domain.excel.renderer.renderTrainees
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private val mapper = JsonMapper.builder().build()

private fun json(text: String): JsonNode = mapper.readTree(text)

// Form PR #67 QuestionSnapshot 모양. 배열 순서와 order를 일부러 다르게 둔다.
private val SURVEY_QUESTIONS =
    json(
        """
        [
          {"id": 12, "title": "관심 분야", "order": 2, "formType": "MULTIPLE", "jsonData": {"1": "AI", "2": {"value": "로봇", "isAlwaysSelected": false}}, "otherJson": null},
          {"id": 10, "title": "만족도", "order": 0, "formType": "DROPDOWN", "jsonData": {"1": "만족", "2": "불만족"}, "otherJson": null},
          {"id": 11, "title": "재참가 의향", "order": 1, "formType": "CHECKBOX", "jsonData": {}, "otherJson": null},
          {"id": 13, "title": "의견", "order": 3, "formType": "SENTENCE", "jsonData": {}, "otherJson": null}
        ]
        """,
    )

// Form 신청 폼 스냅샷. 직업은 DROPDOWN(dynamicFormType OCCUPATION, 키 = Occupation), 지역은 REGION(jsonData 없음)이다.
private val REGISTRATION_QUESTIONS =
    json(
        """
        [
          {"id": 1, "title": "이름", "order": 0, "formType": "SENTENCE", "dynamicFormType": "NAME"},
          {"id": 2, "title": "전화번호", "order": 1, "formType": "SENTENCE", "dynamicFormType": "PHONE_NUMBER"},
          {"id": 3, "title": "직업", "order": 2, "formType": "DROPDOWN", "dynamicFormType": "OCCUPATION",
           "jsonData": {"GENERAL": "일반 성인", "TEACHER": "교사", "ELEMENTARY_STUDENT": "초등학생"}},
          {"id": 4, "title": "지역", "order": 3, "formType": "REGION", "dynamicFormType": "DEFAULT"},
          {"id": 5, "title": "소속 학교", "order": 4, "formType": "SENTENCE", "dynamicFormType": "SCHOOL"},
          {"id": 6, "title": "관심 분야", "order": 5, "formType": "SENTENCE", "dynamicFormType": "DEFAULT"},
          {"id": 7, "title": "동행자", "order": 6, "formType": "COMPANION", "dynamicFormType": "DEFAULT"}
        ]
        """,
    )

private const val REPRESENTATIVE_ANSWERS =
    """{"이름": "대표", "전화번호": "010", "직업": "GENERAL", "지역": "GWANGJU", "관심 분야": "전시",
    "동행자": [{"name": "가람", "occupation": "ELEMENTARY_STUDENT", "region": "JEONNAM"}, {"name": "나래"}]}"""

class MappedAnswersTests :
    StringSpec({
        "설문: 제출 당시 스냅샷으로 ID를 제목에, 선택지 키를 보기 문구로 바꾸고 order 순서를 지킨다" {
            // JSONB는 키 순서를 보존하지 않는다
            val mapped =
                idKeyedAnswers(
                    json("""{"13": "좋았어요, \"정말\"", "12": ["2", "1"], "11": true, "10": "1"}"""),
                    SURVEY_QUESTIONS,
                )

            mapped.values.keys.toList() shouldContainExactly listOf("만족도", "재참가 의향", "관심 분야", "의견")
            mapped.values shouldBe mapOf("만족도" to "만족", "재참가 의향" to "true", "관심 분야" to "로봇, AI", "의견" to "좋았어요, \"정말\"")
            mapped.unresolved shouldBe 0
        }

        "설문: null 답변은 열을 남기고 값만 비우며, CHECKBOX false는 \"false\"" {
            val mapped = idKeyedAnswers(json("""{"10": null, "11": false}"""), SURVEY_QUESTIONS)

            mapped.values shouldBe mapOf("만족도" to null, "재참가 의향" to "false")
            mapped.unresolved shouldBe 0
        }

        "설문: 스냅샷에 없는 문항 ID는 열을 만들지 않고, 모르는 선택지 키는 저장값 그대로 두며 둘 다 센다" {
            val mapped = idKeyedAnswers(json("""{"10": "9", "12": ["1", "7"], "99": "x"}"""), SURVEY_QUESTIONS)

            mapped.values shouldBe mapOf("만족도" to "9", "관심 분야" to "AI, 7")
            mapped.unresolved shouldBe 3
        }

        "ID 키: 같은 제목의 뒤 문항은 버리지 않고 문항 ID를 붙인 열로 두며, 제목 없는 문항만 센다" {
            val questions =
                json(
                    """
                    [
                      {"id": 2, "title": "의견", "order": 1, "formType": "SENTENCE", "jsonData": {}},
                      {"id": 1, "title": "의견", "order": 0, "formType": "SENTENCE", "jsonData": {}},
                      {"id": 3, "order": 2, "formType": "SENTENCE", "jsonData": {}}
                    ]
                    """,
                )
            val mapped = idKeyedAnswers(json("""{"1": "첫째", "2": "둘째", "3": "제목 없음"}"""), questions)

            mapped.values shouldBe mapOf("의견" to "첫째", "의견 (문항 2)" to "둘째")
            mapped.unresolved shouldBe 1
        }

        "ID 키: Application 프로그램 스냅샷(order 없음)은 배열 순서로 제목·보기 문구를 복원하고 제목 키 답변으로 해석하지 않는다" {
            val questions =
                json(
                    """
                    [
                      {"id": 319, "title": "초등학교 학년", "formType": "DROPDOWN", "dynamicFormType": "DEFAULT", "requiredStatus": true,
                       "jsonData": {"3": "3학년", "4": "4학년"}, "otherJson": null},
                      {"id": 320, "title": "참가 학교", "formType": "SENTENCE", "jsonData": {}, "otherJson": null},
                      {"id": 321, "title": "확인", "formType": "CHECKBOX", "jsonData": {}, "otherJson": null}
                    ]
                    """,
                )
            val answers = json("""{"321": true, "320": "QA초등학교", "319": "4"}""")

            idKeyedAnswers(answers, questions) shouldBe
                MappedAnswers(mapOf("초등학교 학년" to "4학년", "참가 학교" to "QA초등학교", "확인" to "true"), 0)
            // 등록(제목 키) 해석기를 쓰면 문항 ID가 열 제목이 된다(dev 실파일에서 관측된 결함)
            informationAnswers(answers, questions).values.keys shouldBe setOf("321", "320", "319")
        }

        "설문: 스냅샷이 없으면(questions=null) 제목을 만들지 않고 답변 수를 센다" {
            idKeyedAnswers(json("""{"10": "1", "11": true}"""), null) shouldBe MappedAnswers(emptyMap(), 2)
            idKeyedAnswers(json("""{"10": "1"}"""), json("{}")) shouldBe MappedAnswers(emptyMap(), 1)
        }

        "신청 폼: 제목 키를 스냅샷 order로 정렬하고 스냅샷에 없는 제목은 뒤에 그대로 둔다" {
            val questions =
                json(
                    """
                    [
                      {"id": 1, "title": "이름", "order": 0, "formType": "SENTENCE", "jsonData": {}, "dynamicFormType": "NAME"},
                      {"id": 2, "title": "직업", "order": 1, "formType": "DROPDOWN", "jsonData": {"TEACHER": "교사", "STUDENT": "학생"}, "dynamicFormType": "OCCUPATION"},
                      {"id": 3, "title": "관심", "order": 2, "formType": "MULTIPLE", "jsonData": {"1": "AI", "2": "로봇"}, "dynamicFormType": "DEFAULT"},
                      {"id": 4, "title": "소속", "order": 3, "formType": "DROPDOWN", "jsonData": {"1": "가초"}, "dynamicFormType": "DEFAULT"}
                    ]
                    """,
                )
            val mapped =
                informationAnswers(
                    json("""{"추가": "x", "소속": "가초", "관심": ["2", "9"], "직업": "TEACHER", "이름": "홍길동"}"""),
                    questions,
                )

            mapped.values.keys.toList() shouldContainExactly listOf("이름", "직업", "관심", "소속", "추가")
            // 이미 보기 문구로 저장된 값(소속)은 그대로, 모르는 키(9)만 센다
            mapped.values shouldBe mapOf("이름" to "홍길동", "직업" to "교사", "관심" to "로봇, 9", "소속" to "가초", "추가" to "x")
            mapped.unresolved shouldBe 1
        }

        "일반 참가자: 폼 보기 문구가 없는 직업 코드는 공유 라벨을 쓰고 SCHOOL_STAFF도 한글로 나온다" {
            fun occupation(code: String) =
                standardInformation(
                    json("{}"),
                    REGISTRATION_QUESTIONS,
                    ParticipantProfile(code, "GWANGJU", null),
                    companion = true,
                ).values["직업"]

            occupation("SCHOOL_STAFF") shouldBe "교직원"
            occupation("KINDERGARTEN_STUDENT") shouldBe "미취학"
            // 폼 보기 문구가 있으면 그것이 우선이다
            occupation("GENERAL") shouldBe "일반 성인"
        }

        "신청 폼: 스냅샷이 없으면 저장된 제목과 값을 문자열로 그대로 쓴다" {
            val mapped = informationAnswers(json("""{"학교명": "가초", "학년": 3, "동의": true, "과목": ["국어", "수학"], "비고": null}"""), null)

            mapped.values shouldBe mapOf("학교명" to "가초", "학년" to "3", "동의" to "true", "과목" to "국어, 수학", "비고" to null)
            mapped.unresolved shouldBe 0
        }

        "일반 참가자 값 맵과 연수자 문자열 맵이 기존 렌더러 열 계약으로 그대로 들어간다" {
            val info = informationAnswers(json("""{"학교명": "가초", "과목": ["국어", "수학"]}"""), null).values
            val survey = idKeyedAnswers(json("""{"12": ["1"], "10": "2"}"""), SURVEY_QUESTIONS).values

            val standard =
                rows(renderStandardParticipants(listOf(StandardParticipant("홍길동", "010", true, ApplicationType.PRE, info, survey))))
            standard[0] shouldContainExactly listOf("이름", "전화번호", "개인정보 동의 여부", "신청 방식", "학교명", "과목", "만족도", "관심 분야")
            standard[1] shouldContainExactly listOf("홍길동", "010", "동의", "사전 등록", "가초", "국어, 수학", "불만족", "AI")

            val trainee =
                rows(renderTrainees(listOf(Trainee("김교사", "T1", "010", ApplicationType.FIELD, info, emptyList()))))
            trainee[0] shouldContainExactly listOf("이름", "연수원아이디", "전화번호", "알림 연락처", "신청방식", "학교명", "과목", "공통 강연", "선택 강연")
            trainee[1] shouldContainExactly listOf("김교사", "T1", "010", "", "현장 등록", "가초", "국어, 수학", "", "")
        }

        "일반 참가자: 동행자는 대표자 스냅샷의 제목만 빌리고 값은 자기 직업·지역·학교뿐이다" {
            val representative =
                standardInformation(
                    json(REPRESENTATIVE_ANSWERS),
                    REGISTRATION_QUESTIONS,
                    ParticipantProfile("GENERAL", "GWANGJU", null),
                    companion = false,
                )
            val teacher =
                standardInformation(json("{}"), REGISTRATION_QUESTIONS, ParticipantProfile("TEACHER", "JEONNAM", "가온초"), companion = true)
            val student =
                standardInformation(
                    json("{}"),
                    REGISTRATION_QUESTIONS,
                    ParticipantProfile("HIGH_SCHOOL_STUDENT", "OTHER", null),
                    companion = true,
                )

            // 이름·전화번호 문항은 고정 열과 겹쳐 빠지고, 대표자가 답하지 않은 학교 문항도 열이 남는다
            representative.values shouldBe
                mapOf("직업" to "일반 성인", "지역" to "광주", "소속 학교" to null, "관심 분야" to "전시", "동행자" to "가람, 나래")
            teacher.values shouldBe mapOf("직업" to "교사", "지역" to "전남", "소속 학교" to "가온초", "관심 분야" to null, "동행자" to null)
            // 폼 보기 문구가 없는 직업은 고정 문구를 쓴다
            student.values shouldBe mapOf("직업" to "고등학생", "지역" to "그 외", "소속 학교" to null, "관심 분야" to null, "동행자" to null)
            listOf(representative, teacher, student).sumOf { it.unresolved } shouldBe 0
        }

        "일반 참가자: 자기 답변이 컬럼보다 우선하고, 모르는 코드는 저장값 그대로 두고 센다" {
            val mapped =
                standardInformation(
                    json("""{"직업": "TEACHER", "지역": "SEOUL"}"""),
                    REGISTRATION_QUESTIONS,
                    ParticipantProfile("GENERAL", "GWANGJU", null),
                    companion = false,
                )

            mapped.values["직업"] shouldBe "교사"
            mapped.values["지역"] shouldBe "SEOUL"
            mapped.unresolved shouldBe 1
        }

        "일반 참가자: 대표자 스냅샷이 없는 동행자는 열을 만들지 않고 복원 불가로 센다" {
            val mapped = standardInformation(json("{}"), null, ParticipantProfile("TEACHER", "JEONNAM", "가온초"), companion = true)

            mapped.values shouldBe emptyMap()
            mapped.unresolved shouldBe 3
        }

        "출석부·원장 제출본의 등록 답변은 원본 표시를 보존하고 명단만 라벨을 쓴다" {
            val mapped = informationAnswers(json(REPRESENTATIVE_ANSWERS), REGISTRATION_QUESTIONS)

            mapped.values["지역"] shouldBe "GWANGJU"
            mapped.values["이름"] shouldBe "대표"
            mapped.values["동행자"] shouldBe """{"name":"가람","occupation":"ELEMENTARY_STUDENT","region":"JEONNAM"}, {"name":"나래"}"""
            val roster = informationAnswers(json(REPRESENTATIVE_ANSWERS), REGISTRATION_QUESTIONS, roster = true)
            roster.values["지역"] shouldBe "광주"
            roster.values["동행자"] shouldBe "가람, 나래"
        }

        "현재 본인 지역은 알려진 코드만 표시하고 미기록이나 알 수 없는 지역을 추정하지 않는다" {
            listOf("GWANGJU", "JEONNAM", "OTHER", "FUTURE", null).map(::regionLabel) shouldBe
                listOf("광주", "전남", "그 외", "FUTURE", null)
        }
    })

private fun rows(workbook: SXSSFWorkbook): List<List<String>> {
    val bytes = ByteArrayOutputStream().also { workbook.use { wb -> wb.write(it) } }.toByteArray()
    return XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
        wb.getSheetAt(0).map { row -> row.map { it.stringCellValue } }
    }
}
