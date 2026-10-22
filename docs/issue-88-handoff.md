# Report #88 local handoff

Status: implemented and locally verified on 2026-10-22. Not deployed.
Branch: `fix/88-excel-roster-profile-columns` (renamed at the user's request).
Base: `57ca23274a848f31d172519ab8c283ac2d0606bd`, identical to remote main at start.
Remote develop was `b32cdd10471fa065b5c2e38423ce18952b127457` and is already an ancestor of HEAD. No dependency merge was needed. Local origin/develop was stale and was not used as the baseline.

## Column contracts for Client #849

| Export | Contract |
| --- | --- |
| TRAINEE GET / SELECTED / FILTER | A 이름, B 연수원아이디, C 전화번호, **D 알림 연락처**, E 신청방식; existing dynamic fields start at F; 공통 강연 / 선택 강연 remain last. Total: 7 + dynamic fields. |
| Registration ledger | **N 본인 지역** immediately after M 소속. 30 fixed fields, then original snapshot answer fields. |
| Application ledger | **J 본인 지역** immediately after I 소속. 26 fixed fields, then original application answer fields. |
| Legacy program roster | 6 columns: 순위 / 이름 / 전화번호 / 개인정보 동의 여부 / 서명 / 비고. |
| Detailed program roster | First 4 legacy identity fields, 소속 / 직업 / 신청 경로 / 신청 시각, dynamic answers, 서명 / 비고. Total: 10 + dynamic fields. A genuine dynamic 학번 question remains. |

Linked trainees use the existing participant registration/profile mapping and representative question titles. Representative answers are not copied into companion roster rows. Trainee phone and training ID remain their original values; notification contact comes from the linked participant's notificationPhoneNumber. Missing linked participants or required profile fields fail through the existing source failure contract.

Roster region codes and companion lists use display labels and names. Ledger fixed own-region values use the current participant profile; null stays blank and unknown region codes stay unchanged. Original snapshot answers, including raw region values, and registration answer provenance remain intact. Attendance and survey mapping keep their previous behavior. No print-area or width configuration was added.

## Changed source and tests

Production: `MappedAnswers.kt`, `ReportSource.kt`, `impl/HttpReportSource.kt`, `impl/ExcelDownloadService.kt`, `impl/ExcelRenderers.kt`, `impl/RegistrationLedger.kt`, `impl/ApplicationLedgerRenderer.kt` under `src/main/kotlin/team/startup/report/domain/excel/service/`.

Tests: `ExcelDownloadTests`, `ExcelSourceIntegrationTests`, `RegistrationLedgerTests`, `ApplicationLedgerTests`, `service/MappedAnswersTests`, `service/impl/ExcelRenderersTests` under `src/test/kotlin/team/startup/report/domain/excel/`.

## Verification

Using only `.env.example` values, with an isolated Report PostgreSQL container (`report-issue88`, port 35488):

```sh
set -a
source .env.example
set +a
POSTGRES_PORT=35488 ./gradlew build ktlintCheck spotlessCheck --no-daemon
```

PASS: 22 suites, 163 tests, 0 failures/errors/skips. Required suites: MappedAnswersTests 15, ExcelRenderersTests 12, ExcelDownloadTests 22, RegistrationLedgerTests 7, ApplicationLedgerTests 7; ExcelSourceIntegrationTests 19. Full execution includes auth, empty targets, provider errors, limits, survey and attendance regressions. Existing POI dispose deprecation warnings remain.

Synthetic artifacts only, ignored under `build/issue88/`: `trainee-get.xlsx`, `trainee-selected.xlsx`, `trainee-filter.xlsx`, `registration-ledger.xlsx`, `application-ledger.xlsx`. Separate Python zipfile/ElementTree inspection passed ZIP integrity, header/row widths, unique cell coordinates, no formula/error cells and no invented print area. Trainee files are identical and have 11 columns; focused registration/application ledgers have 31/27 columns including their one snapshot region field. Test reports: `build/reports/tests/test/index.html` and `build/test-results/test/`.

Evidence: `.omo/evidence/excel-qa/task-1.md` through `task-4.md`. Gate review is recorded separately in that directory.

Single read-only review-work gate: APPROVE, HIGH confidence, no blockers. Reviewer independently checked the final source diff, passing test XML and generated synthetic XLSX artifacts. The task-owned PostgreSQL container, network and synthetic volume were removed after validation.

## Boundaries and remaining checks

No commit, push, PR, merge, deployment, GitHub issue edit, Client change, operating data/config change, or new dependency. Original QA files remained read-only; raw exports and credentials were not copied into this worktree. Only synthetic exports were generated under ignored build output.

Live deployed API verification and Excel GUI/printing were not performed. Consumers using numeric column positions must account for the changed contracts above before deployment. Linked trainee exports now also read the event's participant detail pages, following the existing standard roster pattern.
