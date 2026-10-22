# Report #92 FIELD visit ledger

Implementation workspace: `feat-92-field-visit-ledger-excel`. Local changes only; not deployed.

## Contract

`POST /excel/expos/{expoId}/downloads`, template `REGISTRATION_LEDGER`:

- All: `{"template":"REGISTRATION_LEDGER","scope":"FILTER"}` combines the existing PRE/reservation source with known FIELD visits. Legacy unassigned FIELD rows retain `UNKNOWN` dates; no date is inferred or backfilled.
- Selected: `keys` also accepts `FIELD:{expoId}:{registrationId}`. PRE/reservation/FIELD keys may be mixed and retain request order. A re-registration keeps its registration key and exposes the latest generation plus earlier events.
- Filter: `registrationFilter.visitDate` (ISO date), `fieldStatus` (`ACTIVE`/`CANCELLED`), and existing `q` (own current name/phone). Date/status filters select FIELD rows only and exclude UNKNOWN. FIELD date/status cannot be combined with PRE `sessionId`/`status`. Existing PRE filters retain their original provider contract.

Source: Application V41 `GET /internal/expos/{expoId}/field-visits`, optional `visitDate`, UUID `cursor`, `size=100` (provider permits 1..500), authenticated with the existing Application internal token. Every page is consumed through `nextCursor=null`; repeated cursors, duplicate registration/date identities, foreign expo/date/participants, invalid generations/events, and missing/null pending flags fail without a partial file. Provider errors do not fall back to User's single registration flag.

`cancelledBy` and event `actorParticipantId` are participant IDs. They never populate the administrator ID column. Every page's `events` covers all generations for its `items`. Report preserves canceled earlier generations after re-registration.

## Workbook

Existing 30 fixed ledger columns and dynamic submitted answers retain their positions. Seven columns are appended after dynamic answers: visit date, FIELD status, generation, registration UUID, cancellation participant ID, cancellation pending, delivery pending. PRE rows leave these blank; legacy unassigned FIELD has `UNKNOWN` date.

Known FIELD rows join User's own profile by participant ID. A companion's null phone stays null; notification phone, own school/region, and occupation remain separate. No historical visit submission answers are invented from the current profile. Report #88 roster/profile fixes and program sources remain unchanged.

New sheets: `현장 등록 세대 이력` (registration key, generation, event, timestamp, participant actor, operation UUID), `방문일별 현장 인원` (participant-date count, active completed registrations, canceled status, registration delivery pending, cancellation delivery pending). FIELD events also appear in the existing registration history sheet with administrator ID blank. Counts describe the exported rows, not event-wide party size or attendance. Only ACTIVE with both pending flags false counts as completed; UNKNOWN is excluded from daily counts.

## Validation and rollout

PASS: 13 fakeprovider/unit/MVC suites, 138 tests, zero failures/errors/skips; `ktlintCheck`, `spotlessCheck`, and `assemble`. Suites: RegistrationLedger (11), ApplicationLedger (7), ExcelDownload (22), ExcelHttpContract (8), ExcelSourceIntegration (19), HttpReportSource (14), MappedAnswers (15), ExcelRenderers (12), ExcelWorkbook (1), ExcelSecurityConfig (1), ProgramAttendanceExcel (7), EntryRecordExcelService (13), PublicSurveyExcelService (8). Final focused recheck: all 11 RegistrationLedger tests plus both lint checks and assemble PASS after ensuring pending FIELD rows leave the old confirmed timestamp blank.

Actual synthetic workbook: `build/issue92/field-visit-ledger.xlsx`; generated through MockMvc and reopened with Apache POI for cell assertions. Covers two dates for one person, mixed PRE/FIELD, cancellation, re-registration generation history, actor identity, companion own-profile/null-phone preservation, UNKNOWN exclusion, pending counts and timestamps. Additional tests cover selected order, date/status/search filters, unknown/foreign selections, repeated cursors, malformed provider responses, missing/null pending flags, and source outages. ZIP integrity and five sheet names were independently inspected; no formula cells. Test reports are under `build/reports/tests/test` and `build/test-results/test` (the final focused run replaces the broad run's reports).

Every Gradle invocation runs under a parent Perl `Fcntl LOCK_EX` on `/tmp/expo-application-gradle.lock`, held across `system @ARGV`, with `nice -n10`, `--no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process`, and `-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2`. Temporary `/tmp/report92-test.init.gradle` sets one test fork, 512 MiB heap, two active CPUs, and Spring test context cache size four. Only `.env.example` local values are sourced. The initial run failed before test startup because `LOCAL_SECURITY_PASSWORD` was absent; sourcing those example values resolved it. Existing POI `dispose()` deprecation warnings remain.

All tests use synthetic provider data. No operational database, SMS, deployment, commit, push, PR, merge, or external human message is involved.

## Changed files

- `src/main/kotlin/team/startup/report/global/client/ApplicationClient.kt`: FIELD HTTP source.
- `src/main/kotlin/team/startup/report/global/client/FieldVisits.kt`: source DTOs and stable registration keys.
- `src/main/kotlin/team/startup/report/global/client/PreregistrationTargets.kt`: dedicated FIELD filters.
- `src/main/kotlin/team/startup/report/domain/excel/presentation/DownloadRequest.kt`: selection/filter documentation.
- `src/main/kotlin/team/startup/report/domain/excel/service/impl/RegistrationLedger.kt`: existing ledger integration and mixed selection ordering.
- `src/main/kotlin/team/startup/report/domain/excel/service/impl/FieldVisitLedger.kt`: pagination validation, FIELD rows, generation history and daily counts.
- `src/test/kotlin/team/startup/report/domain/excel/RegistrationLedgerTests.kt`: original PRE regression plus four FIELD scenarios.
- `docs/issue-92-handoff.md`: contracts, verification and deployment handoff.

Deploy Application V41 and its source contract first. Complete User #202 and Attendance #204 rollout, then deploy Report and enable the server feature. Client #860 must consume the FIELD keys/filters and appended columns separately. Do not enable Report against a provider missing the new source: all-ledger downloads deliberately fail rather than silently omit FIELD visits. Recheck cancellation, pending delivery, repeated visits, re-registration history, UNKNOWN dates, and daily counts against the deployed providers before declaring live completion.

Docker persistence/full Spring context checks and live deployment verification are not available in this workspace; they remain unverified. Temporary fakeprovider HTTP servers are test-scoped, and no persistent server or compose stack is started.
