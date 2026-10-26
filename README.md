# Expo Report Server

박람회 보고서 서버 초기 프로젝트입니다.

## 개발 환경

- JDK 21, Kotlin 2.3.21, Spring Boot 4.1.1, Gradle 9.7.1
- PostgreSQL 17.6, Docker Compose v2
- Springdoc 3.1.1, Apache POI 5.5.1, Kotest 5.9.1

원본 Spring Boot 4 및 인접 Expo 서비스와 호환되도록 Springdoc은 3.1.1,
ktlint 플러그인은 14.2.0, Spotless는 8.10.2를 사용합니다.
[Springdoc 공식 호환표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
기준으로 Boot 4는 Springdoc 3 계열을 사용합니다.
ktlint/Spotless 블록은 Expo Application 서비스와 동일합니다.
JPA 플러그인(noArg 포함), allOpen, kapt을 적용했습니다. 별도 annotation processor는 아직 없습니다.

## 로컬 실행

```bash
cp .env.example .env
# .env의 로컬 계정과 포트를 필요에 맞게 수정
# JWT_PUBLIC_KEY: 로컬 Expo-User-Server의 JWT_PRIVATE_KEY에서 공개키를 뽑아 넣는다(비어 있으면 기동 실패)
#   openssl pkey -in user-private.pem -pubout | awk 'NF {printf "%s\\n", $0}'
chmod 600 .env
docker compose up -d --wait
./gradlew bootRun
```

입장 기록 엑셀(`GET /excel/entry-records/{expo_id}?from=&to=`)은 Expo-Attendance-Server의
`GET /internal/expos/{expoId}/entry-records`(Attendance #150)를 페이지(2000건)씩 끝까지 읽고, 이름은 유저 서비스
`names` 내부 API로 채웁니다. 연결 설정 `ATTENDANCE_SERVICE_URL`과 `ATTENDANCE_INTERNAL_TOKEN`(Attendance `INTERNAL_TOKEN`과 같은 값)은
**선택**입니다. 둘 다 비우면 이 다운로드만 503이고 다른 엑셀과 기동에는 영향이 없으며, 하나만 넣으면 기동에 실패합니다.
`from`/`to`(`yyyy-MM-dd`)는 함께 주거나 모두 생략합니다.

공개 만족도 조사 응답 엑셀(`GET /excel/public-surveys/{expo_id}`)은 Expo-Form-Server의
`GET /internal/surveys/{expoId}/public-answers`(Form #215)를 커서(1000건)로 끝까지 읽어 익명 응답 1건을 1행으로 씁니다.
참가자 명단과 합치지 않고 전화번호·경품 정보는 없습니다. `FORM_SERVICE_URL`과 `FORM_INTERNAL_TOKEN`(Form `INTERNAL_TOKEN`)은
입장 기록과 같은 규칙의 **선택** 설정입니다(둘 다 비우면 이 다운로드만 503).

회차 실제 출석 결과 엑셀(`GET /excel/expos/{expoId}/program-attendances?programType=STANDARD|TRAINING&programId=`)은
같은 Attendance 연결로 `GET /internal/program-attendances/{standard|training}/{programId}`(Attendance #194, `attendanceDate` 필수)를 읽고
신청(전 상태)·참가자/연수자 상세와 결합합니다. 연결을 비우면 이 다운로드도 503이며, 출석 조회 실패는 전원 미출석 파일이 아니라 502입니다.

기본 서버 주소는 `http://localhost:8083`, DB 포트는 `35432`입니다.
로컬 서버와 DB는 루프백 주소에만 바인딩됩니다.
Swagger는 기본 비활성화입니다. 선택 환경 변수 `SWAGGER_ENABLED=true`로 켜면
OpenAPI JSON은 `/stat/v3/api-docs`, Swagger UI는 `/stat/swagger-ui.html`에서 GET으로 공개됩니다.
게이트웨이 `/docs`에는 개발 설정에서 이 JSON 경로를 등록합니다.
선택 환경 변수 `SWAGGER_INCLUDE_INTERNAL`의 기본값도 `false`이며 `/internal/**` 경로와
그 경로에서만 사용하는 스키마를 문서에서 제거합니다. `true`이면 내부 경로에
`X-Internal-Token` 인증을 문서화합니다. 현재 Report에는 내부 수신 API가 없습니다.
문서 포함 여부는 실제 API 인증을 변경하지 않습니다. 두 변수 모두 생략해도 부팅 설정에 영향이 없습니다.
Spring Security가 적용되어 있으므로 `.env`의 `LOCAL_SECURITY_USER`와
`LOCAL_SECURITY_PASSWORD`로 로그인하세요. 예제 계정은 로컬 전용입니다.
설정은 `application.yaml` 하나로 관리하고 환경별 값은 환경 변수로 지정합니다.
Eureka 등록은 기본적으로 비활성화합니다.
Flyway를 사용하며 스키마 변경은 `src/main/resources/db/migration`에 추가합니다.
아직 엔티티와 마이그레이션은 없습니다. JPA는 `ddl-auto: validate`로 실행합니다.
운영 환경은 별도 DB·보안·서비스 디스커버리 설정이 필요합니다.
dev·prod 배포 환경에도 `JWT_PUBLIC_KEY`를 넣어야 합니다(Config-Server `report-*.yml`에는 아직 없음).

```bash
# CI와 같은 검증 (PostgreSQL이 실행 중이어야 함)
./gradlew build ktlintCheck spotlessCheck --no-daemon
# 포맷 수정
./gradlew spotlessApply
# DB 중지 (데이터 볼륨 유지)
docker compose down
```

## 로그

Expo 서비스 공통 로그 표준입니다(Spring·Nest 모두 같음). Expo-Monitoring의 공용 Loki가 모든 서비스를 같은 라벨로 읽습니다.

- stdout의 모든 줄은 JSON 객체 하나입니다(`logback-spring.xml`, `LogstashEncoder`). 필수 키는 `@timestamp`(ISO-8601/RFC3339), `level`(대문자 `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`), `service`(Eureka 앱 이름 `spring.application.name`), `module`, `message`이고 그 밖의 키(MDC 값, `logger_name`, `stack_trace`)는 더 붙어도 됩니다. 그 외의 출력은 stdout에 두지 않습니다(Spring 배너 끔, Logback 상태 경고가 나오면 안 됨).
- 수집 파이프라인은 이 키 이름만 읽고 서비스별 fallback이 없습니다. 로그가 안 잡히거나 필드가 맞지 않으면 파이프라인에 파싱 fallback을 더하지 말고 서비스의 로그 출력을 고칩니다.
- `module`은 `ModuleNameConverter`가 로거 이름에서 만들고 호출부에서 넣지 않습니다. `team.startup.report.{domain|global}.<x>...`는 `<x>`, 그 밖에는 로거 이름의 바로 위 패키지입니다. Nest 서비스는 클래스별 로거 컨텍스트를 그대로 씁니다(`nestjs-pino` `renameContext: 'module'`).
- HTTP 요청은 1건당 접근 로그 1줄을 `module=HttpAccess`로 남깁니다(`HttpAccessLogFilter`). 메시지는 `<METHOD> <경로> status: <상태> (<ms>ms)`(예: `GET /excel/standard/{expo_id} status: 200 (12ms)`)이고, 경로는 매칭된 라우트 템플릿, 없으면(보안 거절·404) 쿼리를 뺀 URI 200자까지입니다. 쿼리·헤더·쿠키·본문은 남기지 않습니다. 레벨은 4xx `WARN`, 5xx `ERROR`, 그 밖 `INFO`이고 클라이언트가 끊으면 `499` `ERROR`입니다. `/actuator/**`와 Swagger 경로는 남기지 않습니다. 선택 환경 변수 `LOGGING_HTTP_ACCESS_ENABLED=false`로 끕니다(기본 `true`).
- `local` 프로필에서만 같은 JSON을 `${EXPO_LOG_DIR:-~/.expo/logs}/<service>/app.log`에도 써서 로컬 Loki가 파일을 tail합니다. 운영은 stdout만 쓰고(pm2가 받음) 로그 파일 설정을 더하지 않습니다.

## GitHub

`.github/workflows/ci.yml`은 main/develop push, 모든 PR, 수동 실행에서
PostgreSQL 기동 후 빌드·테스트·포맷을 검사하고 테스트 보고서를 저장합니다.
별도 GitHub Secrets는 필요하지 않습니다.
이슈/PR 템플릿은 [Gwangju-talent-festival-Server-V2](https://github.com/School-of-Company/Gwangju-talent-festival-Server-V2/tree/main/.github)에서 가져왔습니다.
이슈 템플릿의 자동 라벨인 `버그`, `할일`을 GitHub 저장소에 등록했습니다.
