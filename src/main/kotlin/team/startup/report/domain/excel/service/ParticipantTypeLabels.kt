package team.startup.report.domain.excel.service

/**
 * 참가 유형(직업) 코드의 한글 라벨. 입장 기록 시트와 참가자 시트가 함께 쓰는 유일한 매핑이다.
 * 라벨은 Expo-Client 직업 선택지(occupationData.ts)와 원장 화면(ledger.ts)의 표기와 같다.
 * 코드는 Form `Occupation`과 Attendance `QrCategory`가 같은 이름을 쓴다.
 */
val PARTICIPANT_TYPE_LABELS: Map<String, String> =
    mapOf(
        "KINDERGARTEN_STUDENT" to "미취학",
        "ELEMENTARY_STUDENT" to "초등학생",
        "MIDDLE_SCHOOL_STUDENT" to "중학생",
        "HIGH_SCHOOL_STUDENT" to "고등학생",
        "SCHOOL_STAFF" to "교직원",
        "PARENT" to "학부모",
        "GENERAL" to "일반인",
        "TEACHER" to "교원",
        "PRE_SERVICE_TEACHER" to "예비교사",
    )
