# 접수 XLSX 호환 검증과 남은 계약 (#26)

2026-10-11 기준. 기존 4종 경로, 시트, 필수 열, 관리자 JWT 계약은
`excel-v1-contract.md`와 기존 테스트를 유지한다. #27은 PR #28로 머지되었으며
개인 전화번호와 null 전화번호/trainingId 회귀를 재사용한다.

## 확인한 공급자 코드

- Application develop `3ee75ef55d662ebcd771e91b4515ec72423bd1f9`:
  일반 프로그램 전체 신청 조회와 연수자 500명 단위 신청 조회가 존재한다.
  기존 DTO의 `status`는 출석 boolean이며 접수 상태가 아니다.
- Application PR #49 `1306e6b11ad29a3aaf11f091da41c49f6511ae17`:
  회차 원장, UUID 신청 ID, CONFIRMED 영수증, 당시 폼 스냅샷을 저장한다.
  Report용 회차 원장 조회 API는 추가하지 않았다. 기능 기본값은 비활성이고
  폼 공급자 연결도 남아 있다. #43/#45/#46은 OPEN이다.
- User develop `633e7738990a9fa914cc4d25d40b81db9b274201`:
  기존 상세 커서 조회, 일괄 조회, answers/questions 스냅샷 계약이 존재한다.
- Expo develop `cad63429eea3e969eeab4f60f9938e7e15a23358`:
  행사, 일반 프로그램 행사 소속 확인, 연수 프로그램 100개 일괄 조회가 존재한다.
- Client develop `53119cb6b9fdeb4c5c19055cf5f7a0e302f3d9bc`:
  `exportApplicationsCsv.ts`가 존재한다. XLSX 전체 출력으로 취급하지 않는다.
  기존 #270은 엑셀 버튼 작업이며 새 접수 범위 모달 계약은 명시하지 않는다.

코드 존재와 운영 배포는 별개다. 다른 서비스 파일은 수정하지 않는다.

## 독립 검증

기존 renderer/HTTP/security/source 테스트를 유지하고 `ExcelSourceIntegrationTests`에
다음 실제 다운로드 바이트를 POI로 다시 여는 검증만 추가한다.

- 동일 문항 ID와 선택지 키를 가진 두 제출 버전의 표시문구를 각 스냅샷으로 복원.
  기존 테스트가 문항 order와 스냅샷 없는 설문 제외 및 비식별 건수 로그를 검증한다.
- 4종에서 `=`, `+`, `-`, `@`로 시작하는 외부 입력이 formula가 아닌 STRING 셀.
- 1,001명, 커서 3페이지, Application 500/500/1, Expo 100개씩/마지막 1개를
  요청하고 최종 파일의 모든 사람과 강연을 확인.
- 뒤 페이지 503, 참가자 일괄 응답 일부 누락, 프로그램 일괄 응답 일부 누락은
  부분 파일을 성공으로 반환하지 않고 기존 endpoint별 JSON 오류로 실패.

이는 실제 HTTP 공급자 대역과 Report 컨트롤러를 연결한 검증이다.
운영 공급자, Client 화면, 실제 Excel 앱 검증을 뜻하지 않는다.

검증 결과: 격리된 PostgreSQL 17.6과 로컬 테스트 설정을 주입하여
`./gradlew build ktlintCheck spotlessCheck --no-daemon` 성공(28초).
총 60건, 실패/오류/건너뛰기 0건이며 XLSX 통합 테스트는 14건이다.
`git diff --check`도 통과하였다. 초기 설정/DB 미주입 실행은 기동 단계에서
실패했으며 테스트를 수정하거나 제외하지 않고 환경을 갖춰 전체 재검증하였다.
기존 POI dispose deprecated 및 exception handler Elvis 컴파일 경고는 이번 범위에서 유지한다.

## 공급자 확정이 필요한 요청 계약

Application #46과 #43/#45를 전제로 아래를
[Application #50](https://github.com/School-of-Company/Expo-Application-Server/issues/50)에 요청하였다.
경로와 DTO는 공급자가 확정할 때까지 Report 코드에 만들지 않는다.

1. 행사 권한과 동일 필터를 서버에서 검증하는 선택 신청 ID / 필터 전체 대상 조회.
   유형, 묶음/단일 회차, 접수 상태, 역할, 검색, 날짜의 의미와 관리자 목록 대비
   대상 수 일치를 정의한다. 화면 page/size를 출력 범위로 사용하지 않는다.
2. UUID 원장 신청 ID, participantId, nullable traineeId, programType/programId,
   groupId, 상태, formId/formVersion, 당시 questions/answers, 등록 답변 원천 참조.
   일반/연수 ID 공간 및 취소 이력을 구분하며 출석 boolean으로 상태를 대체하지 않는다.
3. 양식별 CONFIRMED/WAITING/CANCELLED 포함 규칙을 명시한다. 단일 회차 일반
   프로그램 출력은 서버가 확인하며, 사람 명단 전환은 안정된 participantId로
   중복 제거한다. 기존 조회에는 이 정보가 없으므로 임의로 적용하지 않는다.
4. 안정된 정렬/커서, 배치 상한, 조회 중 상태 변경의 일관성, 누락 ID/타 행사 ID,
   잘못된 필터 및 원천 장애 응답을 명세와 공급자 테스트로 고정한다.
5. 개인 번호와 notificationPhoneNumber를 별도 필드로 전달한다. 기존 전화번호
   열에는 개인 번호만 사용한다. 프로그램 답변은 승인된 기존 열 매핑만 연결한다.

Client에는 [Client #346](https://github.com/School-of-Company/Expo-Client/issues/346)으로
기존 4종 양식 및 선택/필터 전체 범위 모달, 단일 회차 제약,
상태별 포함 설명과 오류 표시를 별도 요청한다. 확정된 Report/API 계약으로
연결하며 신규 양식, 열 확장, 실제 출석/이수 보고서는 추가하지 않는다.

선택/필터 전체, 접수 상태, 단일 회차 원장, 사람 중복 제거, 새 프로그램 답변 연결과
Client 모달은 미완료다. 이 PR은 `Related #26`이며 #26을 자동 종료하지 않는다.
