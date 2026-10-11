# 선택 신청·필터 전체 XLSX 다운로드

`POST /excel/expos/{expoId}/downloads`는 관리자 RS256 JWT(`ROLE_ADMIN`)로 호출한다. 기존 4종 GET 경로·요청·응답은 유지한다. 성공은 기존 양식의 XLSX 바이트와 `Content-Disposition`, `Cache-Control: no-store`다.

## 선택 신청

```json
{
  "template": "PROGRAM",
  "scope": "SELECTED",
  "applicationIds": ["22222222-2222-2222-2222-222222222222"],
  "programId": 7
}
```

`applicationIds`는 새 UUID 접수 원장 ID이며 기존 Long 신청 ID가 아니다. 중복 없이 1–1000개다. 누락·타 행사 ID는 공급자가 전체 요청을 거절한다. 선택 범위에 `filter`를 함께 전달하지 않는다.

## 필터 전체

```json
{
  "template": "PROGRAM",
  "scope": "FILTER",
  "programId": 7,
  "filter": {
    "programType": "STANDARD",
    "programId": 7,
    "status": "CONFIRMED",
    "sort": "CREATED_DESC"
  }
}
```

필터는 Application 관리자 조회와 같은 `programType`, `programId`, `groupId`, `applicationId`, `status`, `search`, `from`, `until`, `sort`를 사용한다. `search`는 회차 제목 검색이고 `from`은 포함, `until`은 제외하는 신청 생성 시각(ISO Instant)이다. 역할·개인 이름 검색은 공급자 계약에 없으므로 지원하지 않는다. `page`·`size`와 알 수 없는 본문 필드는 거절한다.

필터를 생략하거나 `status`를 생략하면 `CONFIRMED`다. `status: null`은 모든 보존 상태다. 전체 조회는 Application의 15분 고정 스냅샷(최대 10,000신청, 페이지 500개)을 끝까지 읽는다. 화면 페이지를 전체 대상처럼 사용하지 않는다. 스냅샷·건수 변경, 중복 ID·커서, 만료·누락은 파일 생성 실패다. User의 등록 정보는 다운로드 시점에 조회되며 Application 스냅샷과 동일 시점의 전체 서비스 스냅샷은 아니다.

## 양식과 대상

| template | 대상·상태 | 추가 필드 |
| --- | --- | --- |
| STANDARD | 선택/필터 신청의 참가자, participantId 중복 제거. CONFIRMED/WAITING/CANCELLED 모두 명시적으로 출력 가능 | 없음 |
| TRAINEE | 연수 신청의 연결 연수자, traineeId 중복 제거. CONFIRMED/WAITING/CANCELLED 모두 명시적으로 출력 가능 | 없음 |
| PROGRAM | STANDARD 단일 회차, 확정 신청만. participantId 중복 제거 | programId 필수 |
| ATTENDANCE | 같은 행사 본인 연수 신청, 확정 신청만. 실제 출석 결과가 아닌 작성용 출석부 | traineeId 필수 |

TRAINEE/ATTENDANCE의 전체 범위는 `programType: TRAINING`을 적용한다. ATTENDANCE의 필터 결과에서는 지정한 연수자만 사용한다. 선택 범위에는 다른 연수자를 섞을 수 없다. PROGRAM 전체 범위는 같은 `programId`와 `programType: STANDARD` 필터가 필수다. 빈 대상·잘못된 범위·혼합 회차·연수자 없는 연수 신청·출석용 양식의 대기/취소 신청은 400으로 거절한다.

신규 연수 회차의 제목·일정·구분은 신청 당시 `definition`을 사용한다. 등록 답변과 프로그램 답변은 각각 제출 당시 `questions`로 복원한다. 등록 답변을 우선하며 프로그램 답변은 이미 존재하는 등록 열의 null 값, 출석부의 학교명/소속만 보완한다. 이름·개인 전화번호·연수원 ID를 프로그램 답변으로 덮어쓰지 않는다. 여러 회차의 답변이 충돌하면 회차를 좁히도록 400을 반환한다. 프로그램 명단의 학번·서명·비고에는 승인된 연결 규칙이 없어 기존 빈칸을 유지한다. 임의로 새 프로그램 답변 열을 추가하지 않는다.

행사 없음은 404다. Application의 대상 검증 오류(400), 누락/타 행사 선택(404), 스냅샷 만료(410), 전체 상한 초과(413)는 상태를 유지한다. 그 밖의 원천 장애·응답 대상 불일치·응답 형식 오류는 502다. 오류 본문은 기존 `status/message` 형식이며 공급자 본문·토큰은 노출하지 않는다. 행사별 관리자 소유권 검증은 기존 Report와 동일하게 별도 제공되지 않고 글로벌 `ROLE_ADMIN`을 검증한다.

Client #346은 이 경로로 SELECTED/FILTER 요청을 보내고 XLSX를 다운로드하면 된다. 공급자는 Application의 `/internal/expos/{expoId}/program-session-applications/report-targets` 및 `/selection`이며 등록 원천은 User의 기존 상세 조회를 사용한다. 공급자의 실제 배포와 Client 연결, 실제 Excel 앱 확인은 별도 확인 대상이다.
