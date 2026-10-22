package team.startup.report.domain.excel.service

import tools.jackson.databind.JsonNode

// User 공급자의 답변(answers)과 제출 당시 문항 스냅샷(questions)을 엑셀 렌더러 입력(문항 제목 → 표시 문자열)으로 바꾼다.
// 기준: Expo-User-Server PR #49 InformationResDto, Expo-Form-Server PR #67 QuestionSnapshot.
//
// - 등록 신청 폼 답변은 문항 제목, 설문·프로그램 신청(Application) 답변은 문항 ID가 키다. 제목은 현재 Form 정의가 아니라 제출 당시 스냅샷에서만 찾는다.
// - answers는 JSONB라 키 순서가 보존되지 않으므로 열 순서는 스냅샷 order를 따른다.
// - v1 셀은 보기 문구 문자열이었다. DROPDOWN 키는 보기 문구, MULTIPLE 키 배열은 보기 문구를 ", "로 잇고(v1 클라이언트와 같은 구분자),
//   CHECKBOX boolean은 "true"/"false", 그 밖의 값은 문자열 그대로다. null 답변은 null로 두어 렌더러가 ""로 쓴다.
// - 제목이나 답변을 만들어 내지 않는다. 복원할 수 없는 답변은 [MappedAnswers.unresolved]로 셀 뿐 열을 추가하지 않는다:
//   스냅샷이 없는 ID 키 답변, 스냅샷에 없는 문항 ID, 제목 없는 문항, 제목 키 답변의 같은 제목 뒤 문항, 스냅샷에 없는 선택지 키(저장값을 그대로 쓴다).
// - v1 파싱 결함(연수자 행에 배열이 있으면 폼 열 전체를 비움, 일반 참가자 값에 따옴표·탭·홀로 있는 CR이 있으면 행 전체를 비움)은 재현하지 않는다.

/** [values]는 열 순서가 보존된 맵이다. [unresolved]는 개인정보 없이 관측할 복원 불가 답변 건수다. */
data class MappedAnswers(
    val values: Map<String, String?>,
    val unresolved: Int,
)

/** 신청 폼 답변(제목 키). 일반 참가자·연수자·출석부 information 모두 이것을 쓴다. 스냅샷이 없으면 저장된 제목과 값을 그대로 쓴다. */
fun informationAnswers(
    answers: JsonNode,
    questions: JsonNode?,
    roster: Boolean = false,
): MappedAnswers {
    if (!answers.isObject) return MappedAnswers(emptyMap(), 1)
    // 같은 제목이면 order가 앞선 문항의 형식으로 해석한다(답변 키가 제목이라 값은 하나뿐이다)
    val byTitle = LinkedHashMap<String, Question>()
    snapshotOf(questions).orEmpty().forEach { q -> q.title?.let { byTitle.putIfAbsent(it, q) } }

    val counter = Counter(standard = roster)
    val keys = byTitle.keys.filter(answers::has) + answers.propertyNames().filterNot(byTitle::containsKey)
    return MappedAnswers(keys.associateWith { counter.display(answers.get(it), byTitle[it]) }, counter.count)
}

/** 일반 참가자 자기 컬럼(User #147). 동행자는 신청 답변 없이 이 값만 저장된다. 값은 enum 이름(직업·지역)이나 문자열(학교)이다. */
data class ParticipantProfile(
    val occupation: String?,
    val region: String?,
    val school: String?,
)

/**
 * 일반 참가자 신청 폼 답변(Report #50). [questions]는 자기 스냅샷이고, 동행자([companion])는 대표자 스냅샷에서 제목만 빌린다.
 * - 직업(dynamicFormType OCCUPATION)·지역(formType REGION)·학교(dynamicFormType SCHOOL) 문항은 자기 답변, 없으면 자기 [profile] 값이다.
 *   그 밖의 문항은 자기 답변만 쓰고 대표자 답변은 가져오지 않는다.
 * - 스냅샷 문항은 답이 없어도 제목을 남기고 null(빈칸)로 둔다. 머리글은 참가자 키 합집합을 처음 나온 순서로 만들므로,
 *   대표자가 답하지 않은 학교 문항도 스냅샷 순서 자리에 열이 생긴다.
 * - 이름·전화번호(dynamicFormType NAME·PHONE_NUMBER) 문항은 고정 열과 겹치므로 열에서 뺀다.
 */
fun standardInformation(
    answers: JsonNode,
    questions: JsonNode?,
    profile: ParticipantProfile,
    companion: Boolean,
): MappedAnswers {
    if (!answers.isObject) return MappedAnswers(emptyMap(), 1)
    val snapshot = snapshotOf(questions).orEmpty()
    val identities = snapshot.filter { it.dynamicFormType in IDENTITY_TYPES }.mapNotNullTo(HashSet()) { it.title }
    val byTitle = LinkedHashMap<String, Question>()
    snapshot.forEach { q -> q.title?.let { byTitle.putIfAbsent(it, q) } }

    val counter = Counter(standard = true)
    val values = LinkedHashMap<String, String?>()
    byTitle.forEach { (title, q) ->
        if (title in identities) return@forEach
        val own = q.profileValue(profile)
        when {
            answers.has(title) -> values[title] = counter.display(answers.get(title), q)
            own != null -> values[title] = if (q.isSchool) own else counter.code(own, q)
            else -> values[title] = null
        }
    }
    answers.propertyNames().filterNot { it in byTitle }.forEach { values[it] = counter.display(answers.get(it), null) }
    // 스냅샷에 해당 문항이 없으면 놓을 열을 알 수 없다. 열을 만들지 않고 복원 불가로 센다.
    if (companion) {
        counter.count +=
            listOf(profile.occupation, profile.region, profile.school)
                .zip(listOf(Question::isOccupation, Question::isRegion, Question::isSchool))
                .count { (value, kind) -> value != null && byTitle.values.none(kind) }
    }
    return MappedAnswers(values, counter.count)
}

private val IDENTITY_TYPES = setOf("NAME", "PHONE_NUMBER")

// 직업 라벨은 PARTICIPANT_TYPE_LABELS(입장 기록 시트와 공유). 직업은 폼 jsonData 문구가 우선이고 없을 때만 쓴다.
private val REGION_LABELS = mapOf("GWANGJU" to "광주", "JEONNAM" to "전남", "OTHER" to "그 외")

fun regionLabel(region: String?): String? = region?.let { REGION_LABELS[it] ?: it }

/**
 * 문항 ID 키 답변(설문, Application 프로그램 신청)을 제출 당시 제목으로 바꾼다.
 * 같은 제목의 뒤 문항은 버리지 않고 "제목 (문항 ID)" 열로 둔다. 앞 문항은 제목 그대로라 기존 열과 이어진다.
 */
fun idKeyedAnswers(
    answers: JsonNode,
    questions: JsonNode?,
): MappedAnswers {
    if (!answers.isObject) return MappedAnswers(emptyMap(), 1)
    val snapshot = snapshotOf(questions) ?: return MappedAnswers(emptyMap(), answers.size())

    val counter = Counter()
    val ids = snapshot.mapTo(HashSet()) { it.id }
    counter.count += answers.propertyNames().count { it !in ids }
    val values = LinkedHashMap<String, String?>()
    snapshot.forEach { q ->
        val answer = answers.get(q.id) ?: return@forEach
        when {
            q.title == null -> counter.count++
            q.title in values -> values["${q.title} (문항 ${q.id})"] = counter.display(answer, q)
            else -> values[q.title] = counter.display(answer, q)
        }
    }
    return MappedAnswers(values, counter.count)
}

private class Question(
    val id: String,
    val title: String?,
    val formType: String?,
    val jsonData: JsonNode?,
    val dynamicFormType: String? = null,
) {
    val isOccupation get() = dynamicFormType == "OCCUPATION"
    val isRegion get() = formType == "REGION"
    val isSchool get() = dynamicFormType == "SCHOOL"

    fun profileValue(profile: ParticipantProfile): String? =
        when {
            isOccupation -> profile.occupation
            isRegion -> profile.region
            isSchool -> profile.school
            else -> null
        }
}

/** order 순 문항. 스냅샷이 없거나 배열이 아니면 null. */
private fun snapshotOf(questions: JsonNode?): List<Question>? {
    if (questions == null || !questions.isArray) return null
    return questions
        .values()
        .withIndex()
        .filter { it.value.isObject }
        .sortedBy { (index, q) -> q.get("order")?.takeIf { it.isNumber }?.asInt() ?: index }
        .map { (_, q) ->
            Question(
                id = q.get("id")?.asString().orEmpty(),
                title = q.get("title")?.takeIf { it.isString }?.stringValue(),
                formType = q.get("formType")?.takeIf { it.isString }?.stringValue(),
                jsonData = q.get("jsonData")?.takeIf { it.isObject },
                dynamicFormType = q.get("dynamicFormType")?.takeIf { it.isString }?.stringValue(),
            )
        }
}

/** [standard]면 명단 표시 규칙(직업·지역 문구, 동행자 이름)을 쓴다. 출석부·원장은 제출본 표시를 보존한다. */
private class Counter(
    private val standard: Boolean = false,
) {
    var count = 0

    fun display(
        value: JsonNode?,
        question: Question?,
    ): String? {
        if (value == null || value.isNull) return null
        if (standard && question?.formType == "COMPANION" && value.isArray) return companionNames(value)
        if (standard && (question?.isRegion == true || question?.isOccupation == true) && value.isString) {
            return code(value.stringValue(), question)
        }
        val choices = question?.jsonData?.takeIf { question.formType == "DROPDOWN" || question.formType == "MULTIPLE" }
        if (choices == null) return plain(value)

        var unknown = false
        val labels =
            (if (value.isArray) value.values() else listOf(value)).map { choice ->
                labelOf(choices, choice) ?: plain(choice).also { unknown = true }
            }
        if (unknown) count++
        return labels.joinToString(", ")
    }

    /** 직업·지역 코드의 표시 문구. 직업은 폼 보기 문구가 우선이다. 모르는 코드는 저장값 그대로 쓰고 센다. */
    fun code(
        value: String,
        question: Question,
    ): String {
        val label =
            if (question.isRegion) {
                REGION_LABELS[value]
            } else {
                question.jsonData?.get(value)?.let(::choiceText) ?: PARTICIPANT_TYPE_LABELS[value]
            }
        return label ?: value.also { count++ }
    }

    // 대표자 행의 동행자 문항은 이름만 잇는다. 동행자의 직업·지역·학교는 각자 행에 있다.
    private fun companionNames(value: JsonNode): String =
        value.values().joinToString(", ") { companion ->
            companion.get("name")?.takeIf { it.isString }?.stringValue() ?: "".also { count++ }
        }

    /** 키면 보기 문구, 이미 보기 문구면 그대로, 둘 다 아니면 null */
    private fun labelOf(
        choices: JsonNode,
        choice: JsonNode,
    ): String? {
        if (!choice.isString) return null
        val key = choice.stringValue()
        choices.get(key)?.let(::choiceText)?.let { return it }
        return key.takeIf { choices.values().any { choiceText(it) == key } }
    }

    /** jsonData 값은 "문구" 또는 {"value": "문구", "isAlwaysSelected": true} */
    private fun choiceText(node: JsonNode): String? =
        when {
            node.isString -> node.stringValue()
            node.isObject -> node.get("value")?.takeIf { it.isString }?.stringValue()
            else -> null
        }

    private fun plain(value: JsonNode): String =
        when {
            value.isString -> value.stringValue()
            value.isArray -> value.values().joinToString(", ") { if (it.isNull) "" else plain(it) }
            value.isValueNode -> value.asString()
            else -> value.toString()
        }
}
