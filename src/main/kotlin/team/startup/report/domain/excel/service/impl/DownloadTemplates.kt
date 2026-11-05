package team.startup.report.domain.excel.service.impl

import team.startup.report.domain.excel.presentation.dto.request.DownloadRequest
import team.startup.report.domain.excel.presentation.dto.request.ExcelTemplate
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.domain.excel.service.TrainingProgram
import team.startup.report.domain.excel.service.idKeyedAnswers
import team.startup.report.global.client.SessionType
import team.startup.report.global.client.application.ApplicationRow
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.expo.TrainingCategory
import team.startup.report.global.client.user.UserClient

internal class DownloadTemplates(
    val user: UserClient,
    val expo: ExpoClient,
) {
    fun render(
        expoId: String,
        request: DownloadRequest,
        rows: List<ApplicationRow>,
    ): ExcelFile =
        when (request.template) {
            ExcelTemplate.STANDARD -> standard(expoId, rows)
            ExcelTemplate.PROGRAM -> program(expoId, request, rows)
            ExcelTemplate.TRAINEE -> trainee(expoId, rows)
            ExcelTemplate.ATTENDANCE -> attendance(expoId, request, rows)
            ExcelTemplate.APPLICATION_LEDGER -> applicationLedger(expoId, request, rows)
            ExcelTemplate.REGISTRATION_LEDGER -> error("등록 원장은 신청 대상 경로로 만들지 않는다")
        }
}

// 프로그램 답변은 기존 열의 빈 값만 보완한다. 다른 회차에서 값이 충돌하면 임의로 고르지 않는다.
internal fun existingAnswers(
    registration: Map<String, String?>,
    rows: List<ApplicationRow>,
    keys: Set<String> = registration.keys,
): Map<String, String?> {
    val answers = rows.map { idKeyedAnswers(it.answers, it.questions).values }
    val result = registration.toMutableMap()
    for (key in keys - setOf("이름", "전화번호", "연수원아이디")) {
        if (result[key] != null) continue
        val values = answers.mapNotNull { it[key] }.distinct()
        valid(values.size <= 1, "서로 다른 회차의 답변이 충돌합니다. 회차 범위를 좁혀 주세요.")
        values.singleOrNull()?.let { result[key] = it }
    }
    return result
}

internal fun programs(rows: List<ApplicationRow>): List<TrainingProgram> =
    rows.distinctBy { it.programId }.map { row ->
        val definition = row.definition
        check(definition.path("id").asLong() == row.programId)
        check(definition.path("programType").asString() == SessionType.TRAINING.name)
        TrainingProgram(
            row.programId,
            definition.path("title").asString(null),
            definition.path("category").asString(null)?.let(TrainingCategory::valueOf),
            definition.path("startedAt").asString(),
            definition.path("endedAt").asString(),
        ).also {
            check(it.endDateTime > it.startDateTime)
        }
    }
