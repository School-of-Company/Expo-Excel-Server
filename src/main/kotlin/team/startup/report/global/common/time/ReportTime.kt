package team.startup.report.global.common.time

import java.time.ZoneId
import java.time.format.DateTimeFormatter

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
