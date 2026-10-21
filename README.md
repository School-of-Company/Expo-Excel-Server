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

## GitHub

`.github/workflows/ci.yml`은 main/develop push, 모든 PR, 수동 실행에서
PostgreSQL 기동 후 빌드·테스트·포맷을 검사하고 테스트 보고서를 저장합니다.
별도 GitHub Secrets는 필요하지 않습니다.
이슈/PR 템플릿은 [Gwangju-talent-festival-Server-V2](https://github.com/School-of-Company/Gwangju-talent-festival-Server-V2/tree/main/.github)에서 가져왔습니다.
이슈 템플릿의 자동 라벨인 `버그`, `할일`을 GitHub 저장소에 등록했습니다.
