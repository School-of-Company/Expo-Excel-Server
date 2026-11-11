package team.startup.report.domain.excel.renderer

import team.startup.report.global.client.application.ApplicationStatus
import tools.jackson.databind.JsonNode

internal const val EXCEL_CELL_MAX_LEN = 32767
internal val CELL_CONTROL_CHARACTERS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

internal val ADMISSION_ROUTE_LABELS = mapOf("PRE_REGISTRATION" to "사전", "ONSITE" to "현장")
internal val APPLICATION_STATUS_LABELS =
    mapOf(ApplicationStatus.CONFIRMED to "확정", ApplicationStatus.WAITING to "대기", ApplicationStatus.CANCELLED to "취소")

/** jsonData 값은 "문구" 또는 {"value": "문구", "isAlwaysSelected": true} */
internal fun choiceText(node: JsonNode?): String? =
    when {
        node == null -> null
        node.isString -> node.stringValue()
        node.isObject -> node.get("value")?.takeIf { it.isString }?.stringValue()
        else -> null
    }
