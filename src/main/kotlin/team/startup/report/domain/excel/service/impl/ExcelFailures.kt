package team.startup.report.domain.excel.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import team.startup.report.domain.excel.service.ExcelFile
import team.startup.report.global.exception.ExpectedException

// v1 엑셀 서비스는 엔드포인트마다 예외를 감싸 500 {"status","message"}로 응답했다. 원천 연동 실패도 같은 규칙을 따르고, 대상 없음만 404다.
private val log = LoggerFactory.getLogger("team.startup.report.domain.excel")

internal fun wrapExcelFailure(
    message: (Exception) -> String,
    block: () -> ExcelFile,
): ExcelFile =
    try {
        block()
    } catch (e: ExpectedException) {
        throw e
    } catch (e: Exception) {
        log.error("엑셀 파일 생성 실패", e)
        throw excelFailure(message(e))
    }

internal fun excelFailure(message: String) = ExpectedException(HttpStatus.INTERNAL_SERVER_ERROR, message)

internal fun excelNotFound(message: String) = ExpectedException(HttpStatus.NOT_FOUND, message)
