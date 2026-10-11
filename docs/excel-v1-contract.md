# v1 엑셀 API 이관 계약

기준: Expo-Server develop `858101864235c9492fb2bd37ccf1df5346958d66` `domain/excel` (Spring Boot 3.2.10, Tomcat 10.1.30)
클라이언트: Expo-Client `getTraineeProgramExcelFile.ts`, `getStandardExcelFile.ts`, `getStandardProgramExcelFile.ts`, `getTraineeExcelFile.ts` — 응답을 blob으로 받아 항상 `export.xlsx`로 저장하고, 실패는 상태코드만 보고 토스트를 띄운다.

이 문서의 경로·요청·응답은 **바꾸지 않는다**. 결함 수정이나 호환되지 않는 변경은 아래 "제안" 절에만 적고, 합의 전에는 적용하지 않는다.
셀·서식 계약은 `ExcelRenderersTests`, HTTP 계약은 `ExcelHttpContractTests`가 고정한다(ZIP 바이트가 아니라 셀 값·형식·서식·병합으로 비교).

## HTTP 계약

공통: `GET`, 요청 본문 없음, 관리자(`ROLE_ADMIN`)만. 성공은 `200`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, 본문은 XLSX. 실패는 `{"status": <int>, "message": <string>}`.

| 경로 | 성공 `Content-Disposition` | 시트 | 실패 (모두 500) |
|---|---|---|---|
| `/excel/{expo_id}` | `attachment; filename="교원연수자_정보_{yyyyMMdd_HHmmss}.xlsx"` (Asia/Seoul) | 사전 교원연수자 정보 | 행사 없음·연수자 0명·그 밖의 오류: `예상치 못한 오류 발생` |
| `/excel/standard/{expo_id}` | `attachment; filename*=UTF-8''Participant_Information.xlsx` | 박람회 참가자 정보 | 행사 없음: `엑셀 파일 생성 중 오류 발생: null`, 0명: `엑셀 파일 생성 중 오류 발생: 참가자 정보가 존재하지 않습니다.`, 그 밖: `엑셀 파일 생성 중 오류 발생: {원인}` |
| `/excel/program/{expo_id}?programId={Long}` | `attachment; filename="Program_Participant_Information.xlsx"` | 프로그램 참가자 정보 | 프로그램 없음·행사 소속 아님·그 밖: `엑셀 파일 생성 중 오류 발생`. **0명은 머리글만 있는 파일로 200** |
| `/excel/trainee/{trainee_id}` | `attachment; filename="Trainee_Attendance_{연수자 이름}.xlsx"` | 출석부 | 없음: `해당 연수자를 찾을 수 없습니다.`, 신청 프로그램 없음: `해당 연수자가 신청한 프로그램이 없습니다.` |

- `programId` 누락은 400, `programId`·`trainee_id`가 숫자가 아니면 500(v1 `RuntimeException` 처리기, 메시지는 Spring 변환 오류 문구).
- 한글이 든 `Content-Disposition`(위 1·4행)은 Tomcat이 헤더를 **통째로 버린다**. Tomcat 10.1.30(v1)과 11.0.24(v2)에서 같은 동작을 직접 확인했다. 같은 헤더 문자열을 설정하므로 wire 결과도 v1과 같다.
- 인증 실패는 401, 권한 없음·허용하지 않은 메서드나 경로는 403(v1과 같은 상태코드).

## 시트 규칙 (요약, 상세는 테스트)

- **일반 참가자**: `이름, 전화번호, 개인정보 동의 여부(동의/미동의), 신청 방식(사전 등록/현장 등록)` + 신청 폼 문항(**첫 참가자의 키만**) + 설문 문항(전체 참가자 키의 합집합, 등장 순서). 값은 `toString()`, 없거나 null이면 `""`. 머리글은 RGB(34,37,41) 바탕에 굵은 흰 글씨, 모든 셀 가는 테두리, 기본 열 너비 20.
- **연수자**: `이름, 연수원아이디, 전화번호, 신청방식` + 폼 문항(전체 합집합) + `공통 강연`(ESSENTIAL), `선택 강연`(그 외·null). 강연은 신청 순서대로 제목을 중복 없이 `, `로 잇는다. 모든 셀은 `safe()` 처리(개행·제어문자 제거, 32767자 자름, null은 `""`).
- **프로그램 참가자**: `순위(숫자 1..n), 이름, 전화번호, 개인정보 동의 여부, 학번, 서명, 비고`(마지막 세 열은 빈 문자열). 머리글은 인덱스 색 BLACK 바탕, WHITE 글씨.
- **출석부**: 제목 `출  석  부`(A1:J1 병합), 과정명 `{박람회 제목} 직무연수 ({기수}기)`, 연수일시, 장소 `김대중컨벤션센터`, 담당자 `남인혜 장학사(380-4587)`. 날짜마다 머리글 두 줄, 강연 줄, 기록 줄의 4행 블록을 두고 블록 사이를 한 줄 띄운다. 열 너비, 행 높이, 병합, 점선·실선 테두리, F2F2F2 채우기, 파란 값 글씨는 v1과 같다. 소속·성명·연수원 아이디는 신청 폼 `학교명`→`소속`, `이름`, `연수원아이디` 순서로 덮어쓰고, 문자열이 아닌 값을 만나면 거기서 멈춘다.

## v2에서 달라지는 점 (계약은 같고 데이터 출처만 바뀜)

- **출석부 날짜**: v1은 신청 행의 `attendanceDate`를 썼다. v2는 신청 테이블에 이 값이 없으므로 **신청한 프로그램의 시작일**(Expo `startedAt`)로 만든다. v1 다건·신규 연수 신청도 `startedAt`의 날짜를 저장했으므로 같은 결과다. v1 단건·QR 현장 신청 경로는 날짜가 비거나 신청일이었는데, 그 과거 데이터는 이관 범위 밖이다. 출석부는 작성용 서식이고 Attention의 실제 입퇴실 기록에 의존하지 않는다.
- **프로그램 일시 형식**: v1은 `yyyy-MM-ddTHH:mm`이다. v2 Expo는 실제로 ISO(`2026-09-24T10:00`)로 저장하고, 내부 일괄 조회(Expo PR #50)는 `yyyy-MM-dd HH:mm`으로 준다. 둘 다 받는다.
- **정렬**: v1 쿼리에는 ORDER BY가 없었다(실제로는 PK 순서). v2 공급자에는 ID(신청 ID) 오름차순을 요청했다.
- **401/403 본문**: v1은 `sendError`로 컨테이너 기본 응답을 냈고, v2는 `{"status","message"}`이다(상태코드는 같다). Gateway를 거치면 토큰이 없거나 서명이 틀린 요청은 Gateway가 먼저 401로 응답한다.
- **새 실패**: 원천 서비스 연동 실패도 위 표의 엔드포인트별 감싸기 규칙에 따라 500으로 응답한다. 출석부의 그 밖 오류는 v1이라면 원인 메시지를 그대로 노출했겠지만, v2는 `출석부 엑셀 생성 중 오류 발생`으로 고정한다(내부 메시지 노출 방지).

## 제안 (미적용, 합의 필요)

1. 한글 파일명 헤더가 버려지는 문제는 `filename*=UTF-8''…`로 고칠 수 있다(클라이언트는 영향 없음).
2. 출석부 장소·담당자가 하드코딩되어 있다. 행사 정보에서 가져오도록 바꿀 수 있다.
3. 출석부의 모든 날짜 블록에 같은 강연이 반복된다. 날짜별 강연으로 나눌 수 있다.
4. 일반 참가자의 신청 폼 열이 첫 참가자 키만 쓰므로, 문항이 다른 참가자의 답이 빠진다.
5. 실패가 모두 500이고 `...: null` 같은 메시지가 나간다. 404 등으로 나눌 수 있다(이전 초안의 "빈 데이터 200"이나 오류 코드 재설계는 철회했다).
6. **v2 답변 값 표현 결정 필요**: v2 Form은 DROPDOWN을 `jsonData` 키로, MULTIPLE을 키 배열로, CHECKBOX를 boolean으로 저장한다. v1은 제목 키와 표시 문자열이었다. 또 v1 연수자 파싱(`Map<String,String>`)은 배열이 하나라도 있으면 그 행의 폼 열 전체를 비웠고, 일반 참가자 `sanitizeJson`은 `"`나 `\`가 든 값이 있으면 그 행 전체를 비웠다. v2 어댑터가 스냅샷으로 보기 문구를 복원할지, v1 파싱 결함까지 재현할지 정해야 한다. 정해지기 전에는 어댑터를 만들지 않는다.
7. 스냅샷이 없어 문항 제목을 복원할 수 없는 행은 열 제목을 임의로 만들지 않고 건수를 보고하는 방향으로 협의 중이다(Expo-User-Server#43).

## 공급자 계약 상태

`ReportSource`는 현재 `PendingReportSource`(항상 v1 실패 응답)뿐이다. 가짜 파일을 성공으로 주지 않는다. 아래 계약이 확정·배포되면 HTTP 어댑터로 바꾼다. 경로·DTO는 **제안일 뿐 구현된 것이 아니다**.

| 필요한 것 | 요청 이슈 | 상태 |
|---|---|---|
| 행사별 일반 참가자·연수자 상세(커서, ID 오름차순, 최대 500), 연수자 단건, 참가자 ID 일괄 조회 | [Expo-User-Server#44](https://github.com/School-of-Company/Expo-User-Server/issues/44) | 제안, PR 없음. **4개 엑셀 모두 이 데이터가 필요하다** |
| 신청·설문 답변의 제출 당시 문항 스냅샷 보존 | [Expo-User-Server#43](https://github.com/School-of-Company/Expo-User-Server/issues/43) | 제안 |
| 설문 이벤트 v2에 문항 스냅샷 포함(소비자 먼저 배포, eventId 멱등) | [Expo-Form-Server#65](https://github.com/School-of-Company/Expo-Form-Server/issues/65) | 제안 |
| 연수자 ID → 연수 신청 일괄 조회, 일반 프로그램 신청 목록 순서 고정, #18 폼 스냅샷 전달 | [Expo-Application-Server#21](https://github.com/School-of-Company/Expo-Application-Server/issues/21) | 조회·정렬은 [PR #23](https://github.com/School-of-Company/Expo-Application-Server/pull/23) 열림(미머지·미배포, 요청 최대 500), 스냅샷 전달은 #18에서 |
| 박람회 제목, 행사 범위 연수 프로그램 일괄 조회, 일반 프로그램 소속 확인 | [Expo-Expo-Server#46](https://github.com/School-of-Company/Expo-Expo-Server/issues/46) | [PR #50](https://github.com/School-of-Company/Expo-Expo-Server/pull/50) 열림(미머지·미배포). 일괄 조회는 최대 100개, ID 오름차순, 섞인 ID는 404 |
| 실제 v2 참가자 데이터를 만드는 등록 경로 | [Expo-Application-Server#18](https://github.com/School-of-Company/Expo-Application-Server/issues/18), [Expo-User-Server#38](https://github.com/School-of-Company/Expo-User-Server/issues/38) | 열림(선행) |

이미 있고 재사용할 계약: Application `GET /internal/standard-program-applications/program/{programId}`(순서 고정 요청 중). Form `GET /internal/forms/{expoId}`, `GET /internal/surveys/{expoId}`는 현재 정의만 주므로 과거 답변의 제목 복원에는 쓰지 않는다.

## 인증·배포

- `JWT_PUBLIC_KEY`(RS256 SPKI PEM)로 서명을 검증한다. `role` 클레임이 `ROLE_ADMIN`이어야 하고, `sub`는 숫자, 수명은 15분 이하(Expo-Expo-Server와 같은 규칙)다. `X-User-Id`, `X-User-Role` 헤더는 무시한다.
- Gateway 설정(Config-Server `gateway-*.yml`)에는 `/excel`·`/stat` → `expo-report-server`가 이미 있다. 운영 반영 여부는 확인하지 못했다. `/stat`은 이번 범위가 아니다.
- `/excel` 밖의 경로는 `SecurityConfig`의 체인이 맡는다(모니터링 GET만 익명 허용, 나머지는 인증 필요).

## 머지·배포 조건

`.github/workflows/deploy.yml`은 develop 푸시를 dev로, main 푸시를 prod로 자동 배포한다. Config-Server `gateway-prod.yml`은 이미 `/excel`을 `expo-report-server`로 보낸다. 그래서 지금 상태로 머지하면 다음 문제가 생긴다.

- `PendingReportSource` 때문에 4개 엑셀이 항상 500을 낸다.
- 배포 환경에 `JWT_PUBLIC_KEY`가 없으면 기동부터 실패한다. 빈 값은 원인 메시지와 함께 즉시 실패시킨다. `report-*.yml`에는 이 키가 아직 없다.

develop·main 머지 전에 다음을 갖춘다.

1. 위 공급자 계약이 확정·배포되고, 실제 `ReportSource` 어댑터와 그 계약 테스트가 들어온다.
2. dev·prod에 `JWT_PUBLIC_KEY`와 공급자별 내부 토큰·주소를 설정한다.
3. 그 전까지 Gateway `/excel`이 v1 서버 대신 이 서버로 실제 트래픽을 보내는지 확인하고, 보낸다면 전환을 미룬다.
