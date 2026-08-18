# 매장 운영 대시보드 통계 기능 명세

> 상태: Issue #270 contract-first 활성 명세
> Runtime: Issue #270 branch에서 구현됨. #240/PR #394와 최신 dev를 반영했으며 focused/CI 검증 전에는 PR 병합 금지다.
> 소유자: Store/Analytics 통합 `@116Lv`; Reservation·Waiting 공개 계약 공동 검토

## 관련 정책과 범위

- 정본 정책: `ANALYTICS-001`~`ANALYTICS-004`
- 원본 도메인 정책: `RES-001`~`RES-015`, `WAIT-001`~`WAIT-017`, `CHECK-006`
- 대상 API: `GET /api/v1/store-operators/stores/{storeId}/dashboard-statistics`
- 포함: 오늘 예약 팀 수, 예약률, 팀 수 상한 사용률, 취소율, 웨이팅 현황, 노쇼 현황
- 제외: 프런트엔드, Pro 비교·해석·추천, 내보내기, 개인별 예약·웨이팅·노쇼 목록
- Analytics는 Reservation·Waiting의 Entity·Repository를 직접 참조하지 않고 공개 Service·DTO만 소비한다.

## 조사 기준과 현재 의존 계약

초기 계약 조사 기준은 `origin/dev` `8c61d641`이며, runtime 완료 시점에 최신 `origin/dev`로 rebase해 재검증한다.

| 의존성 | 현재 공개 계약 | #270에 충분한가 | 해제 방법 |
|---|---|---|---|
| Store 권한 | `StoreService.requireDashboardAuthority(...)`, `StoreDashboardAuthority` | 충족 | authority version과 KST time zone을 동일 snapshot 경계로 사용한다. |
| Reservation 통계 | `ReservationAnalyticsQueryService.getDashboardSnapshot(...)`, `ReservationAnalyticsSnapshot` | 확정 노쇼 필드 외 충족 | #240 audit aggregate와 `confirmedNoShowTeams`를 공개 DTO에 추가한다. |
| Reservation 수용량 | 공개 Reservation 통계의 allocation/capacity aggregate | 충족 | `policy_published_at <= asOf`인 최신 정책만 사용한다. |
| Reservation 취소 | 공개 Reservation 통계의 lifecycle/cancellation aggregate | 충족 | 고유 Reservation과 cancellation audit를 동일 `asOf`로 집계한다. |
| Waiting #271/#272 | `WaitingAnalyticsQueryService.getDashboardSnapshot(...)`, `WaitingAnalyticsSnapshot` | 충족 | Waiting 원장의 동일 `asOf` aggregate를 사용한다. |
| 예약 노쇼 #240 | `NO_SHOW`, `no_show_at`, 예약별 단일 `reservation_no_show_audits` | 확정값 충족, 후보 제외 | source-owned public analytics DTO로 확정 팀 수·version·dataThrough만 공개한다. |

#271과 #272는 완료됐지만 공개 목록 계약을 Dashboard가 반복 호출해 합계를 만들지는 않는다. 페이지 도중 상태가 바뀌면 같은 `asOf`가 아니고, 원 도메인의 페이지 크기·정렬을 분석 계약으로 고착시키기 때문이다.

## 지표 정의

공통 `asOf`는 UTC `Instant` 하나이며 출력은 offset이 있는 시각이다. `businessDate`는 `Asia/Seoul` 기준이고 구간은 `[D 00:00 KST, D+1 00:00 KST)`다. Reservation은 원본 `serviceDate=D`, Waiting은 OPER/WAIT의 시작일 귀속이 끝난 `businessDate=D`를 사용한다. 모든 입력 사건은 `occurredAt <= asOf`만 유효하다.

| 지표 | 포함 | 분자 | 분모 | 제외·산출 불가 |
|---|---|---|---|---|
| 오늘 예약 팀 수 | D 방문 예정이며 asOf까지 확정·방문 완료·향후 후보/확정 노쇼인 고유 Reservation | 포함된 Reservation ID 수 | 없음 | 취소·거절·결제 실패·만료 |
| 예약률 | D의 유효 서비스 구간을 점유하는 확정 계열 예약 | 유효 버킷의 확정 Reservation 점유 인원 합 | 같은 버킷의 플랫폼 제공 최대 인원 합 | Hold만의 점유와 결제 실패 제외. 분모가 없으면 `INVALID_DENOMINATOR`. |
| 팀 수 상한 사용률 | 위와 같은 서비스 구간 | 유효 버킷의 확정 Reservation 점유 팀 합 | 같은 버킷의 최대 팀 수 합 | 구간별 비율을 더하지 않는다. 분모가 없으면 산출 불가다. |
| 취소율 | D 방문 예정으로 한 번 이상 확정된 고유 Reservation | asOf까지 취소된 팀 | 확정된 적이 있는 전체 팀 | 취소 발생 날짜로 모집단을 바꾸지 않는다. 결제 실패는 제외한다. |
| 웨이팅 현황 | D에 귀속되고 asOf 현재 `WAITING` 또는 `CALLED`인 팀 | 상태별 팀·인원 합, 가장 이른 `createdAt`부터 asOf까지의 초 | 없음 | `ARRIVED`와 종결 상태는 현재 대기/호출 합에서 제외한다. 활성 팀이 없으면 최장 대기는 null이다. |
| 노쇼 현황 | 예약 후보, 예약 확정, Waiting 호출 미응답 확정을 서로 분리 | 분류별 고유 팀 수 | 없음 | 세 분류를 하나의 total로 합치지 않는다. 입력 계약이 없으면 0이 아니라 `UNAVAILABLE`. |

## 동일 기준 시각 snapshot

Dashboard service는 권한을 먼저 검증한 뒤 `Clock`을 한 번만 읽어 실제 생성 시각 `generatedAt`을 고정하고, 이를 UTC 1분 경계로 내린 값을 `asOf`로 사용한다. 모든 source query에는 같은 `storeId`, `businessDate`, `asOf`를 전달한다. 같은 매장·영업일·권한 version·1분 버킷의 재요청은 최초 저장된 canonical snapshot을 재사용하며, 버킷 안에서 뒤늦게 들어온 source 변경은 다음 분 snapshot에 반영한다. 각 source는 자신의 상태·감사·버전만 읽고 개인정보 없는 집계 DTO를 반환한다.

```java
public record StoreDashboardAuthority(
        long storeId,
        String timeZoneId,
        long dashboardAuthorityVersion
) {}

public interface ReservationAnalyticsQueryService {
    ReservationAnalyticsSnapshot getDashboardSnapshot(
            long storeId, LocalDate businessDate, Instant asOf);
}

public interface WaitingAnalyticsQueryService {
    WaitingAnalyticsSnapshot getDashboardSnapshot(
            long storeId, LocalDate businessDate, Instant asOf);
}
```

source DTO는 값뿐 아니라 `inputCheckpoint`, `dataThrough`, source schema/version, 보정 여부와 완전성 정보를 반환한다. Analytics는 DTO가 요청한 store/date/asOf와 다르면 그 source 전체를 `UNAVAILABLE/SOURCE_FAILED`로 분류하고 값을 사용하지 않는다.

응답 header는 `snapshotId`, `storeId`, `businessDate`, `timeZoneId`, `asOf`, `generatedAt`, `storeAuthorityVersion`을 고정한다. 여섯 지표는 각각 다음 metadata를 가진다.

- `value`: 정상 수치 또는 명시적 null
- `definitionVersion`: 지표 정의 버전
- `aggregationVersion`: 같은 정의·기간을 대체하는 증가 버전
- `asOf`: top-level과 동일한 기준 시각
- `dataThrough`: 마지막으로 반영된 source 사건 시각 또는 null
- `inputCheckpoint`: 재집계 입력 경계 또는 null
- `completeness`: `COMPLETE`, `DELAYED`, `PARTIAL`, `UNAVAILABLE`
- `corrected`: 보정 사건을 반영했는지 여부
- `reasonCode`: 정상일 때 null, 그 외 공개 사유

한 source 호출의 실패·지연은 그 source가 담당하는 지표만 변경한다. 권한 확인 자체가 실패하면 이전 snapshot이나 다른 지표를 공개하지 않고 HTTP 503으로 실패 폐쇄한다. 권한 확인 뒤 Reservation 집계가 실패해도 Waiting 집계는 계속하며 HTTP 200의 독립 지표 상태로 반환한다. 실패 예외 원문은 응답이나 snapshot JSON에 저장하지 않는다.

## 노쇼 계약 경계

2단계 통합 완료 응답은 다음을 강제한다.

- `reservationCandidate`: `value=null`, `UNAVAILABLE`, `SOURCE_CONTRACT_MISSING`
- `reservationConfirmed`: #240의 운영자 확정 `NO_SHOW` audit를 이용한 실제 집계값
- `waitingConfirmed`: #272 원장을 이용한 실제 집계값
- `noShow.completeness`: 후보 계약이 없으므로 두 확정값이 정상이어도 `PARTIAL/SOURCE_CONTRACT_MISSING`
- 예약과 Waiting의 판정 기준이 다르므로 합계 `total`을 만들지 않는다.

#240/PR #394가 제공한 확정 노쇼 계약은 다음과 같이 소비한다.

1. Reservation source가 영업일의 예약과 `reservation_no_show_audits.occurred_at <= asOf`를 결합해 고유 확정 팀 수를 집계한다.
2. 예약별 audit unique 제약으로 멱등 replay와 중복 명령을 한 번만 반영한다.
3. 최대 no-show audit ID를 source version/checkpoint에 포함하고 최대 `occurred_at`을 `dataThrough`에 포함한다.
4. `ReservationAnalyticsSnapshot.confirmedNoShowTeams`로 개인정보·예약 ID·사유 없이 공개한다.
5. #240은 `NO_SHOW`를 terminal 상태로 두고 정식 정정 workflow를 제외했으므로 현재 `corrected=false`다.

#240은 6시간 후보·자동 판정·정식 정정 workflow를 제외했다. 따라서 `reservationCandidate`는 계속 `UNAVAILABLE`이며, Analytics는 Reservation Entity·상태 문자열·audit Repository를 직접 참조하지 않고 공개 `ReservationAnalyticsQueryService`/DTO만 소비한다.

## 권한·존재 노출·HTTP

Store는 공개 탐색 가능한 자원이므로 기존 authorized enumeration 계약을 유지한다.

| 상황 | 결과 |
|---|---|
| 유효한 store-operator Access JWT 없음 | `401 AUTH_001` |
| 계정 상태가 허용되지 않음 | `403 AUTH_011` |
| 매장은 존재하지만 현재 대표 운영자가 아님 | `403 STORE_003` |
| 매장이 실제로 없음 | `404 STORE_001` |
| 권한 원본/필수 snapshot 인프라 장애 | `503 COMMON_012` |
| 권한 성공 뒤 일부 지표 source 실패 | `200`, 해당 지표만 `UNAVAILABLE` |

하위 Reservation·Waiting 식별자와 존재 여부는 응답하지 않는다. 타 매장의 수치, source checkpoint 원문에 포함된 내부 ID, 개인별 행, 연락처와 자유 텍스트를 노출하지 않는다.

## 중복·정정·재집계

- source는 원 aggregate ID의 `asOf` 기준 최신 유효 version만 사용한다.
- Reservation 수용량 분모는 DB가 기록한 `policy_published_at <= asOf`인 정책 중 최신 `policy_version`만 사용한다. 현재 정책을 과거 snapshot에 소급 적용하지 않는다.
- 기술 재시도·동일 command audit·동일 상태 사건은 안정적인 사건 ID/version으로 한 번만 반영한다.
- snapshot 유일 키는 `(store_id, business_date, as_of, store_authority_version)`이다.
- metric cell 유일 키는 `(dashboard_snapshot_id, metric_key)`다.
- 동일 1분 버킷 replay는 source가 그 사이 바뀌어도 최초 저장 snapshot을 반환한다. 다른 input checkpoint나 보정 입력은 다음 분 snapshot에서 새 `aggregationVersion`으로 반영한다.
- 최초 동시 발행은 안정적인 `stores` 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 게시하여 business date별 최신 marker를 하나만 남긴다.
- snapshot은 성공 후 제자리 덮어쓰지 않는다. 발행 transaction은 `stores` 행 잠금 안에서 header와 여섯 metric 삽입까지만 수행한다.
- 31일을 초과한 header는 발행과 분리된 시간당 retention job이 `(generated_at, dashboard_snapshot_id)` 인덱스로 최대 1,000행씩 10개 transaction에서 정리하고, metric FK의 `ON DELETE CASCADE`로 함께 삭제한다. 삭제된 기간은 재조회 시 source에서 다시 계산할 수 있다.
- 같은 source checkpoint와 definition version의 전체 재집계와 증분 결과는 값·무결성 digest가 같아야 한다.
- 부분 metric row를 먼저 최신으로 게시하지 않는다. header와 여섯 metric cell을 한 MySQL transaction에서 게시한다.

## 집계 전략 결정

| 방식 | 장점 | 위험 |
|---|---|---|
| 요청 시 실시간 조합 | 단순하고 현재 상태가 빠르다. | source 간 시점 차이, 재현·보정·감사와 페이지 비용이 약하다. |
| 독립 Analytics 사건 원장 | 재생·정정·중복 제거가 가장 강하다. | source 사건 복제, worker, migration과 지연 운영이 커진다. |
| 혼합 | source가 버전된 집계를 소유하고 Analytics가 동일 asOf 결과와 checkpoint를 snapshot으로 게시한다. | source 공개 계약과 Analytics snapshot 저장을 모두 구현해야 한다. |

Issue #270은 혼합 방식을 채택한다. 범용 Kafka/Outbox나 새 사용자 추적 사건은 만들지 않는다. 운영 상태·기존 내구성 감사와 source-owned projection만 사용하며, 실제 지연·재생 요구가 확인되기 전에는 별도 범용 사건 플랫폼을 추가하지 않는다.

## Migration과 exact allowlist

현재 `dev`의 마지막 migration은 #396으로 병합된 V55이다. 사용자 지정 재번호 결과 #270은 `V57__create_dashboard_analytics_snapshots.sql`을 사용한다. #270 병합 직전에 V57 충돌 여부와 전체 순서를 다시 검증한다.

### 이번 contract-first 변경 허용 경로

- `docs/05-functional-requirements.md`
- `docs/specs/analytics/spec.md`
- `docs/specs/analytics/openapi.yaml`
- `docs/specs/store-operator-openapi.yaml`
- `redocly.yaml`
- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java`

### 향후 runtime production exact allowlist

- Create: `backend/src/main/resources/db/migration/V57__create_dashboard_analytics_snapshots.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/dto/contract/StoreDashboardAuthority.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/entity/Store.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/contract/ReservationAnalyticsSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCancellationAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationNoShowAuditRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingAnalyticsSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingStatusEventRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/config/AnalyticsSecurityConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/config/DashboardSnapshotRetentionScheduleConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/controller/storeoperator/StoreDashboardAnalyticsController.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/dto/DashboardAnalyticsContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/entity/DashboardSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/entity/DashboardMetricSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/repository/DashboardSnapshotRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/repository/DashboardMetricSnapshotRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/AnalyticsMetricFailureClassifier.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/DashboardSnapshotTransactionExecutor.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/DashboardSnapshotRetentionJob.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsService.java`

### 향후 runtime test exact allowlist

- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/entity/StoreTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/entity/DashboardSnapshotTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/repository/DashboardAnalyticsMigrationTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/repository/DashboardSnapshotRepositoryIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/AnalyticsMetricFailureClassifierTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/DashboardSnapshotRetentionJobTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsHttpIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java`
- Existing verification only: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- Existing verification only: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

추가 경로가 필요하면 수정 전에 Issue #270의 allowlist와 이유를 갱신하고 공동 검토를 다시 받는다. #240 연동 경로는 #240 병합 후 별도 allowlist 보완으로 추가한다.

## 외부 계약 차단 목록

| 차단 항목 | 현재 상태 | 해제 조건 |
|---|---|---|
| Store 권한 version | 구현 완료 | focused authority/HTTP/CI 검증 통과 |
| Reservation 통계 source | 확정 노쇼 필드 외 구현 완료 | 확정 노쇼 공개 DTO와 MySQL 집계 테스트 승인 |
| Waiting 통계 source | 구현 완료 | focused asOf 집계/CI 검증 통과 |
| 예약 확정 노쇼 | #240/PR #394 병합 완료 | Reservation 공개 DTO·asOf 집계와 MySQL consumer 테스트 승인 |
| 예약 노쇼 후보 | #240 제외 범위 | #240 범위 변경 또는 별도 producer Issue 병합 |
| V57 | 사용자 지정 재번호로 #270에 지정 | Issue #270 병합 직전 충돌 여부 재확인 |

노쇼 차단은 나머지 다섯 지표와 Waiting 확정 미응답의 contract/runtime 구현을 막지 않는다. 다만 누락 source를 0 또는 `COMPLETE`로 반환하는 구현은 금지한다.

## 인수 조건

- 유효한 자기 매장 운영자는 구독 없이 여섯 지표 key가 있는 snapshot을 조회한다.
- top-level과 모든 metric의 `asOf`가 정확히 같고 source 입력은 그 시각을 넘지 않는다.
- 분모가 없거나 유효하지 않은 비율은 0이 아니라 `UNAVAILABLE/INVALID_DENOMINATOR`다.
- Reservation source 실패가 Waiting 성공을 덮지 않고 반대도 동일하다.
- 예약 확정 노쇼와 Waiting 확정 미응답은 각각 실제 값·독립 metadata를 가지며, 후보만 null/UNAVAILABLE이다.
- 다른 매장은 403, 실제 없는 공개 매장은 404이며 어떤 경우에도 통계·하위 개인 자료가 노출되지 않는다.
- 중복·역순·보정 입력 뒤 같은 checkpoint의 전체·증분 재집계 결과가 일치한다.
- Analytics production code에는 Reservation·Waiting Entity·Repository import가 없다.
- MySQL snapshot 게시, 권한 HTTP, OpenAPI drift와 source consumer 계약 테스트가 통과한다.

## 이번 단계 검증

로컬에서는 변경 범위의 단위 테스트와 영향받는 MySQL 통합 테스트, 문서·OpenAPI 계약 검증만 실행한다.

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.analytics.* --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.repository.DashboardAnalyticsMigrationTest --tests com.miriyum.domain.analytics.repository.DashboardSnapshotRepositoryIT --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceIT --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceIT --console=plain
cd ..
npx --yes @redocly/cli@2.35.1 lint analytics docs/specs/store-operator-openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle analytics --output NUL
git diff --check
```

전체 `build`, `check`, 전체 `integrationTest`, shard A~D 전체는 로컬에서 실행하지 않는다. runtime 구현 뒤 전체 suite의 정본은 GitHub Backend CI다.
