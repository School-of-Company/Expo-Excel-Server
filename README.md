# Expo Report Server

박람회 참가자·연수자 명단, 출석 결과, 설문 응답을 XLSX로 내려주는 서버입니다.

## 개발 환경

- JDK 21, Kotlin 2.3.21, Spring Boot 4.1.1, Gradle 9.7.1
- Springdoc 3.1.1, Apache POI 5.5.1, Kotest 5.9.1

ktlint 플러그인은 14.2.0, Spotless는 8.10.2를 사용합니다.

## 엑셀 엔드포인트

모든 엑셀 API는 `Authorization: Bearer <JWT>`가 필요합니다.
Expo-User-Server의 RS256 공개키로 JWT를 검증하고 `role=ROLE_ADMIN` 및 해당 행사 관리 권한을 확인합니다.
로컬 계정의 formLogin·HTTP Basic 인증으로는 엑셀 API를 호출할 수 없습니다.

| 구분 | 메서드·경로 | 내용 |
| --- | --- | --- |
| v1 | `GET /excel/{expo_id}` | 교원 연수자 명단과 신청 강연 |
| v1 | `GET /excel/standard/{expo_id}` | 박람회 참가자 명단·등록 답변·설문 |
| v1 | `GET /excel/trainee/{trainee_id}` | 개인 연수 출석부 작성용 양식(실제 출석·이수 결과 아님) |
| 신규 | `POST /excel/expos/{expoId}/downloads` | 요청 본문으로 양식과 선택 신청 또는 필터 전체 범위 지정 |
| 신규 | `GET /excel/entry-records/{expo_id}` | 입장·재입장·퇴장 기록. 선택 쿼리 `from`·`to`(`yyyy-MM-dd`)는 함께 지정하거나 모두 생략 |
| 신규 | `GET /excel/public-surveys/{expo_id}` | 익명 공개 만족도 조사 응답 |
| 신규 | `GET /excel/expos/{expoId}/program-attendances` | 회차 실제 출석 결과. 필수 쿼리 `programType`(`STANDARD` 또는 `TRAINING`)·`programId` |

POST 다운로드의 `template`은 `STANDARD`, `TRAINEE`, `PROGRAM`, `ATTENDANCE`,
`APPLICATION_LEDGER`, `REGISTRATION_LEDGER`이고, `scope`은 `SELECTED` 또는 `FILTER`입니다.
양식별 요청 필드는 `DownloadRequest.kt`를 참고하세요. 쿼리 파라미터 대신 요청 본문을 사용합니다.

## 공급자와 환경 변수

User·Application·Expo는 **필수 공급자**입니다. 각 주소와 내부 토큰이 비어 있으면 서버 기동에 실패합니다.
내부 API 호출에는 `X-Internal-Token` 헤더를 사용합니다.

| 공급자 | 주소·토큰 환경 변수 | 공급자 측 토큰 설정 |
| --- | --- | --- |
| User(필수) | `USER_SERVICE_URL`, `USER_INTERNAL_TOKEN` | `INTERNAL_TOKEN` |
| Application(필수) | `APPLICATION_SERVICE_URL`, `APPLICATION_INTERNAL_TOKEN` | `APPLICATION_INTERNAL_TOKEN` |
| Expo(필수) | `EXPO_SERVICE_URL`, `EXPO_INTERNAL_TOKEN` | `EXPO_INTERNAL_TOKEN` |
| Attendance(선택) | `ATTENDANCE_SERVICE_URL`, `ATTENDANCE_INTERNAL_TOKEN` | `INTERNAL_TOKEN` |
| Form(선택) | `FORM_SERVICE_URL`, `FORM_INTERNAL_TOKEN` | `INTERNAL_TOKEN` |

선택 공급자는 주소와 토큰을 둘 다 비우면 서버가 기동하며 다른 엑셀 다운로드에는 영향이 없습니다.
Attendance 미설정 시 입장 기록·회차 실제 출석 결과 다운로드가, Form 미설정 시 공개 설문 응답 다운로드가 503을 반환합니다.
주소와 토큰 중 하나만 설정하면 기동에 실패합니다. 토큰은 공급자 측 설정과 같은 값을 사용하세요.

| 환경 변수 | 필수 여부·기본값·용도 |
| --- | --- |
| `JWT_PUBLIC_KEY` | 필수. Expo-User-Server의 `JWT_PRIVATE_KEY`와 짝인 RS256 공개키(SPKI PEM). 줄바꿈은 `\n`으로 입력 |
| `LOCAL_SECURITY_USER` | 기본 `developer`. 엑셀 외 인증 경로의 로컬 계정 |
| `LOCAL_SECURITY_PASSWORD` | 필수. 로컬 계정 비밀번호(예제 값은 로컬 전용) |
| `SERVER_PORT` | 기본 `8083` |
| `SWAGGER_ENABLED` | 기본 `false` |
| `CLIENT_CONNECT_TIMEOUT`, `CLIENT_READ_TIMEOUT` | 공급자 연결·응답 타임아웃(Duration). 기본 `3s`, `10s` |
| `LOGGING_HTTP_ACCESS_ENABLED` | HTTP 접근 로그. 기본 `true` |

## 로컬 실행

```bash
cp .env.example .env
# .env의 필수 공급자 주소·토큰, JWT_PUBLIC_KEY, 로컬 계정과 포트를 설정
# JWT_PUBLIC_KEY: 로컬 Expo-User-Server의 JWT_PRIVATE_KEY에서 공개키를 뽑아 넣는다(비어 있으면 기동 실패)
#   openssl pkey -in user-private.pem -pubout | awk 'NF {printf "%s\\n", $0}'
chmod 600 .env
throt run -- ./gradlew bootRun
```

기본 서버 주소는 `http://localhost:8083`입니다.
로컬 서버는 루프백 주소에만 바인딩됩니다.
Swagger는 기본 비활성화입니다. 선택 환경 변수 `SWAGGER_ENABLED=true`로 켜면
OpenAPI JSON은 `/stat/v3/api-docs`, Swagger UI는 `/stat/swagger-ui.html`에서 GET으로 공개됩니다.
설정은 `application.yaml` 하나로 관리하고 환경별 값은 환경 변수로 지정합니다.
`application.yaml`은 작업 디렉터리의 `.env`도 읽습니다.
DB 의존성과 `compose.yaml`은 제거되어 실행·검증에 PostgreSQL이나 Docker Compose가 필요하지 않습니다.

```bash
# CI와 같은 검증
throt run -- ./gradlew build ktlintCheck spotlessCheck
# CI는 위 Gradle 명령에 --no-daemon을 추가합니다.
# 포맷 수정
throt run -- ./gradlew spotlessApply
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
빌드·테스트·포맷을 검사하고 테스트 보고서를 저장합니다.
별도 GitHub Secrets는 필요하지 않습니다.
이슈/PR 템플릿은 [Gwangju-talent-festival-Server-V2](https://github.com/School-of-Company/Gwangju-talent-festival-Server-V2/tree/main/.github)에서 가져왔습니다.
이슈 템플릿의 자동 라벨인 `버그`, `할일`을 GitHub 저장소에 등록했습니다.
