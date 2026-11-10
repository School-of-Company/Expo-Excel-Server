package team.startup.report.domain.excel.service.impl

import team.startup.report.global.client.user.StandardParticipantDetail
import team.startup.report.global.client.user.TraineeDetailItem
import team.startup.report.global.client.user.UserClient
import team.startup.report.global.client.user.readAll

internal const val USER_CURSOR_ERROR = "${UserClient.NAME} 서비스 커서 오류"

internal fun UserClient.allStandardParticipants(
    expoId: String,
    cursorError: String = "Check failed.",
): List<StandardParticipantDetail> = readAll(cursorError = { cursorError }) { standardParticipantPage(expoId, it) }

internal fun UserClient.allTrainees(
    expoId: String,
    cursorError: String = "Check failed.",
): List<TraineeDetailItem> = readAll(cursorError = { cursorError }) { traineePage(expoId, it) }

internal fun <T> List<T>.checkIds(
    ids: Set<Long>,
    id: (T) -> Long,
) {
    check(map(id).toSet() == ids && size == ids.size)
}
