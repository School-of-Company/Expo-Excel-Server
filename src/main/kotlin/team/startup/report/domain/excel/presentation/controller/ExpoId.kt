package team.startup.report.domain.excel.presentation.controller

import org.springframework.http.HttpStatus
import team.startup.report.global.exception.ExpectedException
import java.util.UUID

internal fun parseExpoId(expoId: String): UUID =
    try {
        UUID.fromString(expoId).also {
            if (!it.toString().equals(expoId, ignoreCase = true)) throw IllegalArgumentException()
        }
    } catch (_: IllegalArgumentException) {
        throw ExpectedException(HttpStatus.BAD_REQUEST, "행사 ID는 UUID여야 합니다.")
    }
