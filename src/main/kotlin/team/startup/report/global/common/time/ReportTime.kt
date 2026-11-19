package team.startup.report.global.common.time

import java.time.ZoneId
import java.time.format.DateTimeFormatter

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
val REPORT_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
