# Reservation Fulfillment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증된 현재 대표 매장 운영자가 자기 매장의 `CONFIRMED` 예약과 연결 MenuHold를 수량 복구 없이 원자적으로 `FULFILLED`로 종결하고, 성공 감사와 멱등 replay를 제공한다.

**Architecture:** `StoreReservationController.fulfillReservation`이 strict empty request와 공통 멱등 key를 받아 `ReservationFulfillmentCommandFacade.fulfill`에 전달한다. Facade는 고정 fingerprint·correlation과 제한 재시도를 소유하고, `ReservationService.fulfillStoreReservation`은 현재 소유권을 멱등 claim 전에 확인한 뒤 Reservation → MenuHold 순서로 잠그고 상태·감사·멱등 결과를 하나의 MySQL transaction으로 확정한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC/Security/Data JPA, Flyway, MySQL 8.0.40 Testcontainers, Gradle 9.6.1 Wrapper, JUnit 5, Mockito, AssertJ, pnpm 11.17.0, openapi-typescript 6.7.6

## Global Constraints

- 소유 Issue는 [#52](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/52)이며, 구현은 PR [#213](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/213)이 `dev`에 병합되고 Mandatory Execution Gate 재검증이 완료된 뒤에만 시작한다.
- 현재 브랜치는 `feature/issue-52-reservation-fulfillment`이다. PR #213 병합 전에는 push 또는 PR 생성을 하지 않는다.
- Mandatory Execution Gate에서 최신 `origin/dev`를 안전하게 동기화하고 #52 변경만 남는 diff, 실제 다음 Flyway 버전, 열린 PR의 migration 충돌, OpenAPI generator drift를 확인한다.
- Mandatory Execution Gate에서 production·test·migration·정본·조건부 generated 파일의 정확한 구현 allowlist를 Issue 본문에 기록하고 `READY FOR IMPLEMENTATION`으로 바꾸기 전에는 repository 구현 파일을 수정하지 않는다.
- 사용자 소유 `.idea/**`, `.superpowers/**`는 읽기·수정·삭제·stage·commit하지 않는다.
- 다른 도메인은 `StoreService.requireManagementOwnership(long, long)`, `MenuHoldService.lockForTermination(long)`, `MenuHoldService.fulfill(MenuHoldFulfillCommand)`와 공개 DTO·오류만 사용한다. Auth·Store·MenuHold Entity 또는 Repository에 직접 접근하지 않는다.
- Store의 `CLOSED`·휴점·운영 불가 상태는 방문 완료 거절 조건이 아니다. 현재 활성 store-operator 계정과 대표 소유권은 fresh와 replay 모두에서 다시 확인한다.
- 허용 전이는 `CONFIRMED → FULFILLED`뿐이다. QR 체크인, 노쇼, 환불, 준비 완료, 재활성화, 범용 status PATCH를 추가하지 않는다.
- MenuHold가 없으면 생성하거나 `fulfill`을 호출하지 않는다. 있으면 `CONFIRMED → FULFILLED`를 수행하고, 이미 `FULFILLED`인 공개 계약 결과도 성공으로 수용하며 `RELEASED`는 `MENU_HOLD_006`을 원형 전달한다.
- 수용량 bucket, allocation, 메뉴 재고, 수량 원장은 조회·잠금·복구·삭제하지 않는다. 취소 경로의 capacity 복구와 `Reservation` 재조회 패턴을 복제하지 않는다.
- 현재 소유권 preflight는 `IdempotencyExecutor.execute` 바깥에서 실행한다. replay는 저장된 `200/code/resource/data`를 반환하며 Reservation·MenuHold·감사를 다시 읽거나 변경하지 않는다.
- 멱등 command는 namespace `store-operator`, actor `operatorAccountId`, type `RESERVATION_FULFILL`, canonical UUID key를 사용한다.
- fingerprint field 순서는 `method`, `route`, `storeId`, `reservationId`이며 빈 request body field를 넣지 않는다.
- correlation은 `reservation-fulfill:store-operator:{operatorAccountId}:{normalizedKey}`이고 최대 91자다. Service `correlationId`, MenuHold `operationId`, 감사 `commandId`에 같은 값을 전달한다.
- 요청 DTO는 field 없는 `ReservationFulfillmentRequest` record다. `{}`만 정상이며 unknown field, body 누락, 깨진 JSON은 `COMMON_002`다.
- `storeId`와 `reservationId` 모두 Controller에서 `@Positive`다. 비양수는 `400 COMMON_001`, 양수 예약의 부재·타 매장은 store-scoped `404 RESERVATION_001`이다.
- 성공과 replay는 HTTP 200과 `ReservationDetailResponse`를 반환한다. 공개 응답에 `fulfilledAt` 또는 감사 내부 field를 추가하지 않는다.
- 성공 감사는 예약당 하나의 append-only `reservation_fulfillment_audits` 행이다. actor는 `STORE_OPERATOR`, 전이는 `CONFIRMED → FULFILLED`, 자유입력 reason은 없고 `RESERVATION_FULFILL` 명령 자체가 고정 전이 사유다.
- 감사에는 잠긴 Reservation의 `reservation_time_policy_version`과 `capacity_policy_version`을 양수 snapshot으로 기록한다. 해당 정책을 재판정하거나 관련 bucket을 조회하지 않는다.
- `occurredAt`은 fresh 성공 경로에서 `Clock`으로 한 번만 얻고 Reservation `fulfilledAt`과 감사 `occurredAt`에 동일하게 사용한다.
- migration의 `command_id`는 `VARCHAR(100)`과 `CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)`을 함께 사용한다.
- 기존 적용 migration은 수정하지 않고 Mandatory Execution Gate에서 계산한 신규 migration 파일만 추가한다.
- Java production 변경은 계약 중심 Javadoc, 생성자 주입, UTF-8, 4칸 들여쓰기, wildcard import 금지 규칙을 따른다.
- 테스트는 sleep 대신 latch/barrier와 실제 MySQL lock 관찰을 사용한다. Testcontainers/Spring 통합 클래스에는 `@Tag("integration")`과 정확히 하나의 shard tag를 선언한다.
- Tasks 1–5의 production behavior는 RED 실패를 실제로 관찰한 뒤 최소 GREEN을 구현한다. Task 6 dependency baseline과 Task 7 MySQL integration은 이미 구현된 behavior의 verification이라 PASS할 수 있으며 가짜 RED를 만들지 않는다. 모든 task는 지정 경로만 `git add`하여 non-amend 새 commit을 만들고 force push나 `dev` 직접 push를 사용하지 않는다.
- 다른 도메인의 공개 계약이 최신 `dev`에서 사라졌거나 변경됐다면 overload, fallback validator, dummy, 직접 repository 접근으로 우회하지 않고 `BLOCKED`로 보고한다.

---

## File Responsibility Map

Mandatory Execution Gate에서 Issue exact allowlist를 확정하기 전까지 아래 목록은 후보 경로다. 숫자를 최종 범위로 주장하지 않는다.

### Canonical contracts

- Modify: `docs/specs/reservation/spec.md` — 폐점·휴점에서도 기존 확정 예약의 방문 완료 허용, 전용 성공 감사와 원자성을 소유한다.
- Modify: `docs/specs/reservation/openapi.yaml` — fulfillment 요청·응답과 전용 403·404·409 예시를 소유한다.
- Modify: `docs/specs/mvp1-common/ownership.md` — 공통 활성 계정·대표 소유권과 명령별 Store 상태 적격성을 분리한다.
- Modify: `docs/specs/mvp1-common/domain-model.md` — idempotency → Reservation → MenuHold 잠금과 무복구 전이를 소유한다.
- Modify: `docs/03-domain-model.md` — 기존 거래 종결에 현재 거래 가능 상태를 요구하지 않는 예외를 소유한다.
- Modify: `docs/specs/store-search/spec.md` — 폐점·휴점 Store의 신규 거래 차단과 기존 거래 종결 허용을 정렬한다.
- Modify: `docs/specs/mvp1-common/spec.md` — 공통 관리 권한과 명령별 상태 조건의 경계를 정렬한다.
- Modify: `docs/07-data-and-api-contracts.md` — 양수 public ID, fulfillment 오류, 멱등 replay 경계를 정렬한다.
- Modify: `docs/05-functional-requirements.md` — 1차 MVP 직접 방문 완료와 QR·노쇼 고도화를 분리한다.
- Modify: `docs/service-policies/09-checkin-noshow.md` — fulfillment 결과를 고도화 CHECK가 보조 방문 증거로 소비하는 경계를 소유한다.
- Modify: `docs/service-policies/04-reservation.md` — 두 정책 version snapshot과 고정 `RESERVATION_FULFILL` 전이 사유를 소유한다.

### Reservation production

- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequest.java` — field 없는 strict request 타입이다.
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentActorType.java` — 감사 actor `STORE_OPERATOR`만 소유한다.
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAudit.java` — 성공 방문 완료 audit snapshot과 `recordSuccess` 불변식을 소유한다.
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java` — 감사 저장과 `findByReservationId(Long)`를 소유한다.
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandResult.java` — 저장·재생된 HTTP status와 detail data를 소유한다.
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java` — fingerprint, correlation, `requestedAt`, 기술 충돌 제한 재시도를 소유한다.
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java` — 소유권 preflight, 멱등 boundary, 잠금·전이·감사·응답 transaction을 소유한다.
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java` — fulfillment HTTP endpoint와 성공 envelope를 소유한다.
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java` — 정확한 fulfillment POST matcher를 인증 대상으로 추가한다.
- Create: Mandatory Execution Gate에서 계산한 다음 Flyway 버전의 `create_reservation_fulfillment_audits.sql` — 전용 감사 table과 DB constraints를 소유한다.

### Tests

- Create: `backend/src/test/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequestTest.java` — field 없는 request 타입과 JSON shape를 고정한다.
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAuditTest.java` — audit factory 불변식과 trim command ID를 고정한다.
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java` — clean install, 직전 버전 upgrade, schema constraint와 entity round-trip을 검증한다.
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java` — 소유권·replay·상태·MenuHold·감사·무복구 orchestration을 검증한다.
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java` — command construction과 retry 분류를 검증한다.
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java` — 인증·validation·strict body·오류 passthrough·security matcher를 검증한다.
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java` — fulfillment OpenAPI의 정확한 요청과 오류 참조를 검증한다.
- Create: `backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java` — Reservation production이 타 도메인 내부 Entity·Repository에 의존하지 않음을 고정한다.
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java` — MySQL 원자성·불변 자원·경합·rollback을 검증한다.

### Conditional generated output

Mandatory Execution Gate의 격리 generator probe가 실제 deterministic diff를 만든 경로만 Issue와 commit에 포함한다. 현재 예상 후보는 다음 두 경로지만 결과가 0 diff면 둘 다 제외한다.

- Conditional modify: `frontend/src/shared/api/generated/reservation.ts`
- Conditional modify: `frontend/src/shared/api/generated/auth-account.ts`

다른 generated 파일이 실제로 바뀌면 원인을 검토하고, Reservation OpenAPI 변경의 결정적 결과인 경우에만 그 정확한 경로를 Issue에 추가한다. 원인을 설명할 수 없으면 구현을 중단한다.

## Mandatory Execution Gate

이 gate는 Task 1의 repository 변경보다 먼저 끝나야 한다.

Plan 작성 시점의 관찰 증거는 PR #213 `MERGED`, head `8d98558a55345e4b7f9da918e234f008b54f8bd9`, merge commit 및 `dev` `258a2f0d6b4563b06d9ed2fc329203ea0de96faf`, live `dev` 최고 migration `V26`, PR #212 `OPEN` 및 `V27__create_pickup_reservations.sql` 사용이다. 이 값은 실행 허가나 고정 버전 선택이 아니라 비교 기준이며 아래 fail-closed query에서 모두 동적으로 live 재확인한다.

- [ ] **Gate 1: 현재 branch와 사용자 변경을 기록한다**

Run from repository root:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git diff --cached --name-only
```

Expected: branch는 `feature/issue-52-reservation-fulfillment`, staged index는 비어 있고 tracked 변경은 없다. `.idea/`, `.superpowers/`가 보여도 건드리지 않는다.

- [ ] **Gate 2: PR #213이 실제로 `dev`에 병합됐는지 확인한다**

```powershell
$pr213Raw = gh pr view 213 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json state,isDraft,mergedAt,mergeCommit,baseRefName,headRefName,url
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pr213Raw)) { throw 'BLOCKED: PR #213 query failed' }
try { $pr213 = $pr213Raw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: PR #213 returned invalid JSON' }
$pr213
```

Expected: `state`는 `MERGED`, `mergedAt`과 `mergeCommit.oid`는 null이 아니고 `baseRefName`은 `dev`다. 현재 비교값은 merge commit `258a2f0d6b4563b06d9ed2fc329203ea0de96faf`다. 하나라도 다르면 `BLOCKED: PR #213 not merged to dev`로 보고하고 이후 단계를 실행하지 않는다.

- [ ] **Gate 3: 최신 dev와 열린 migration 후보를 가져온다**

```powershell
git fetch origin dev
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: origin/dev fetch failed' }
$openPrRaw = gh pr list --repo sparta-spring4/Commerce-Final-Project-MiriYum --state open --limit 100 --json number,title,headRefName,baseRefName,files
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($openPrRaw)) { throw 'BLOCKED: open PR query failed' }
try { $openPrData = @($openPrRaw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'BLOCKED: open PR query returned invalid JSON' }
if (-not $openPrRaw.TrimStart().StartsWith('[')) { throw 'BLOCKED: open PR result is not a JSON array' }
$openPaths = @($openPrData | ForEach-Object { @($_.files) | ForEach-Object { $_.path } })
if (@($openPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\\' }).Count -ne 0) { throw 'BLOCKED: open PR file path set is invalid' }
$invalidOpenMigrationPaths = @($openPaths | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' -and $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
if ($invalidOpenMigrationPaths.Count -ne 0) { throw 'BLOCKED: open PR migration path set is invalid; no version may be selected' }
$devMigrationPaths = @(git ls-tree -r --name-only origin/dev -- backend/src/main/resources/db/migration)
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: origin/dev migration listing failed' }
if (@($devMigrationPaths | Where-Object { $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }).Count -ne 0) { throw 'BLOCKED: origin/dev migration path set is invalid' }
$openPrData
$devMigrationPaths
```

Expected: fetch 성공, 열린 PR의 migration 파일 목록과 `origin/dev`의 실제 migration 목록을 확보한다.

- [ ] **Gate 4: destructive rebase 없이 최신 dev를 동기화한다**

먼저 ancestry를 확인한다.

```powershell
git merge-base --is-ancestor origin/dev HEAD
git rev-list --left-right --count origin/dev...HEAD
```

첫 명령이 0이면 merge가 필요 없다. 1이면 아래처럼 현재 HEAD의 안전 ref를 만들고 merge한다.

```powershell
$issue52Head = git rev-parse --short=12 HEAD
git branch "feature/issue-52-reservation-fulfillment-pre-dev-$issue52Head" HEAD
git merge --no-edit origin/dev
```

Expected: conflict 없는 merge와 tracked clean 상태다. conflict가 생기면 `git merge --abort`만 실행해 pre-merge 상태로 돌아가고 `BLOCKED`로 보고한다. `git reset`, 강제 checkout, rebase를 사용하지 않는다. merge 뒤 아래 검사가 #51 중복 diff나 비-#52 경로를 보이면 구현을 시작하지 않고, fresh `origin/dev` worktree로 승인된 #52 설계·plan commit만 운반하는 별도 선택을 사용자에게 요청한다.

```powershell
git diff --name-only origin/dev...HEAD
git log --oneline --decorate origin/dev..HEAD
```

사용자가 fresh transport를 선택한 경우에만 다음을 실행한다. source branch는 그대로 보존하고 새 branch/worktree에 현재 source HEAD의 design과 plan blob만 명시적으로 옮긴다.

```powershell
$sourceHead = git rev-parse HEAD
$designPath = 'docs/superpowers/specs/2026-08-10-reservation-fulfillment-design.md'
$planPath = 'docs/superpowers/plans/2026-08-10-reservation-fulfillment.md'
$expectedTransport = @($designPath, $planPath) | Sort-Object
$transportRoot = Join-Path $env:TEMP "miriyum-issue52-dev-sync-$([guid]::NewGuid().ToString('N'))"
$transportBranch = "feature/issue-52-reservation-fulfillment-dev-sync-$([guid]::NewGuid().ToString('N').Substring(0,8))"
git worktree add -b $transportBranch $transportRoot origin/dev
if ($LASTEXITCODE -ne 0) { throw 'transport worktree creation failed' }
git -C $transportRoot restore --source=$sourceHead --worktree -- $designPath $planPath
if ($LASTEXITCODE -ne 0) { throw "BLOCKED: exact blob restore failed at $transportRoot; source $sourceHead is unchanged" }
git -C $transportRoot add -- $designPath $planPath
$stagedTransport = @(git -C $transportRoot diff --cached --name-only | Sort-Object)
if ([string]::Join("`n", $stagedTransport) -ne [string]::Join("`n", $expectedTransport)) { throw 'transport staged diff is not the exact approved two-doc scope' }
git -C $transportRoot diff --cached --exit-code $sourceHead -- $designPath $planPath
if ($LASTEXITCODE -ne 0) { throw 'transport staged blobs do not equal source HEAD' }
git -C $transportRoot commit -m "docs: transport reservation fulfillment plan"
if ($LASTEXITCODE -ne 0) { throw "BLOCKED: transport commit failed at $transportRoot; source $sourceHead is unchanged" }
git -C $transportRoot diff --exit-code $sourceHead HEAD -- $designPath $planPath
if ($LASTEXITCODE -ne 0) { throw 'transport committed blobs do not equal source HEAD' }
$transportDiff = @(git -C $transportRoot diff --name-only origin/dev...HEAD | Sort-Object)
if ([string]::Join("`n", $transportDiff) -ne [string]::Join("`n", $expectedTransport)) { throw 'transport diff is not the exact approved two-doc scope' }
git -C $transportRoot status --short --branch
git -C $transportRoot diff --check origin/dev...HEAD
```

Expected: source ref는 변경되지 않고 새 worktree가 최신 `origin/dev` + 정확한 두 문서 commit만 포함한다. 이후 실행 위치와 branch 전환은 사용자 승인 뒤 정하며 이 gate에서 기존 branch를 삭제·rename하지 않는다.

- [ ] **Gate 5: 실제 다음 migration 파일명을 계산한다**

```powershell
$repoSlug = 'sparta-spring4/Commerce-Final-Project-MiriYum'
$devMigrationPaths = @(git ls-tree -r --name-only origin/dev -- backend/src/main/resources/db/migration)
if ($LASTEXITCODE -ne 0 -or $devMigrationPaths.Count -eq 0) { throw 'BLOCKED: origin/dev migration listing failed or is empty' }
if (@($devMigrationPaths | Where-Object { $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }).Count -ne 0) { throw 'BLOCKED: invalid origin/dev migration path set' }
$openPrRaw = gh pr list --repo $repoSlug --state open --limit 100 --json number,files
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($openPrRaw)) { throw 'BLOCKED: open PR migration query failed' }
try { $openPrData = @($openPrRaw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'BLOCKED: open PR migration query returned invalid JSON' }
if (-not $openPrRaw.TrimStart().StartsWith('[')) { throw 'BLOCKED: open PR migration result is not a JSON array' }
$openPaths = @($openPrData | ForEach-Object { @($_.files) | ForEach-Object { $_.path } })
if (@($openPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\\' }).Count -ne 0) { throw 'BLOCKED: open PR file path set is invalid' }
$invalidOpenMigrationPaths = @($openPaths | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' -and $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
if ($invalidOpenMigrationPaths.Count -ne 0) { throw 'BLOCKED: open PR migration path set is invalid; no version may be selected' }
$openMigrationPaths = @($openPaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
$migrationFiles = @($devMigrationPaths + $openMigrationPaths)
$migrationVersions = @($migrationFiles | ForEach-Object {
  if ($_ -match '/V([0-9]+)__') { [int]$Matches[1] } else { throw "BLOCKED: invalid migration path: $_" }
})
if ($migrationVersions.Count -ne $migrationFiles.Count -or $migrationVersions.Count -eq 0) { throw 'BLOCKED: migration version set is invalid' }
$nextMigrationVersion = (($migrationVersions | Measure-Object -Maximum).Maximum + 1)
$migrationPath = "backend/src/main/resources/db/migration/V${nextMigrationVersion}__create_reservation_fulfillment_audits.sql"
$migrationPath
```

Expected: 한 개의 신규 경로가 출력되고 `origin/dev`와 열린 PR 어디에도 같은 version이 없다. 빈 결과·중복·번호 역행이면 `BLOCKED`로 보고한다.

- [ ] **Gate 6: 실제 OpenAPI patch artifact로 격리 generator drift를 판정한다**

아래 전체 PowerShell block을 repository root에서 한 번에 실행한다. 매 실행마다 GUID worktree를 만들고, exact patch를 artifact로 기록하고, 성공·실패 모두에서 tracked patch를 복원한 뒤 clean worktree만 제거한다.

```powershell
pnpm --dir frontend install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { throw 'root dependency install failed' }
$probeId = [guid]::NewGuid().ToString('N')
$probeRoot = Join-Path $env:TEMP "miriyum-issue52-openapi-probe-$probeId"
$probePatchPath = Join-Path $env:TEMP "miriyum-issue52-openapi-$probeId.patch"
$probeResultPath = Join-Path $env:TEMP 'miriyum-issue52-generator-probe.json'
$sourceModules = (Resolve-Path 'frontend/node_modules').Path
$probeModules = Join-Path $probeRoot 'frontend/node_modules'
$probeAdded = $false
$moduleJunctionAdded = $false
$probePatch = @'
diff --git a/docs/specs/reservation/openapi.yaml b/docs/specs/reservation/openapi.yaml
--- a/docs/specs/reservation/openapi.yaml
+++ b/docs/specs/reservation/openapi.yaml
@@ -243,4 +243,4 @@ paths:
         "404":
           $ref: "#/components/responses/ReservationNotFound"
         "409":
-          $ref: "#/components/responses/ReservationStateConflict"
+          $ref: "#/components/responses/ReservationFulfillmentConflict"
@@ -935,16 +935,39 @@ components:
     ReservationStateConflict:
       description: 현재 상태 또는 취소 정책에서 명령 불가
       content:
         application/json:
           schema:
             $ref: "../mvp1-common/openapi.yaml#/components/schemas/ErrorResponse"
           examples:
             invalidState:
               value:
                 code: RESERVATION_005
                 message: 현재 예약 상태에서 처리할 수 없습니다.
             cancellationPolicyRejected:
               value:
                 code: RESERVATION_006
                 message: 현재 취소 정책에서 처리할 수 없습니다.
+    ReservationFulfillmentConflict:
+      description: 방문 완료 상태·MenuHold·멱등·동시 요청 충돌
+      content:
+        application/json:
+          schema:
+            $ref: "../mvp1-common/openapi.yaml#/components/schemas/ErrorResponse"
+          examples:
+            invalidReservationState:
+              value:
+                code: RESERVATION_005
+                message: 현재 예약 상태에서 처리할 수 없습니다.
+            invalidMenuHoldState:
+              value:
+                code: MENU_HOLD_006
+                message: 현재 수량 상태에서 요청한 전이를 수행할 수 없습니다.
+            idempotencyKeyReused:
+              value:
+                code: COMMON_007
+                message: 동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다.
+            concurrentModification:
+              value:
+                code: COMMON_008
+                message: 동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요.
     CapacityConfigurationConflict:
'@
[System.IO.File]::WriteAllText(
  $probePatchPath,
  $probePatch,
  [System.Text.UTF8Encoding]::new($false)
)
try {
  git worktree add --detach $probeRoot HEAD
  if ($LASTEXITCODE -ne 0) { throw 'probe worktree add failed' }
  $probeAdded = $true
  New-Item -ItemType Junction -Path $probeModules -Target $sourceModules | Out-Null
  $moduleJunctionAdded = $true
  git -C $probeRoot apply --check $probePatchPath
  if ($LASTEXITCODE -ne 0) { throw 'exact OpenAPI probe patch no longer applies' }
  git -C $probeRoot apply $probePatchPath
  if ($LASTEXITCODE -ne 0) { throw 'OpenAPI probe patch failed' }
  pnpm --dir (Join-Path $probeRoot 'frontend') generate:api
  if ($LASTEXITCODE -ne 0) { throw 'probe generation failed' }
  $changed = @(git -C $probeRoot diff --name-only)
  $generated = @($changed | Where-Object {
    $_ -like 'frontend/src/shared/api/generated/*.ts'
  })
  $unexpected = @($changed | Where-Object {
    $_ -ne 'docs/specs/reservation/openapi.yaml' -and
    $_ -notlike 'frontend/src/shared/api/generated/*.ts'
  })
  if ($unexpected.Count -ne 0) {
    throw "unexpected probe paths: $($unexpected -join ', ')"
  }
  git -C $probeRoot diff --check
  if ($LASTEXITCODE -ne 0) { throw 'probe diff check failed' }
  [pscustomobject]@{
    head = (git -C $probeRoot rev-parse HEAD)
    patchSha256 = (Get-FileHash -Algorithm SHA256 $probePatchPath).Hash
    generatedPaths = $generated
  } | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 $probeResultPath
} finally {
  if ($probeAdded) {
    if ($moduleJunctionAdded -and (Test-Path -LiteralPath $probeModules)) {
      Remove-Item -LiteralPath $probeModules -Force
    }
    git -C $probeRoot restore --source=HEAD --staged --worktree -- .
    $dirty = @(git -C $probeRoot status --porcelain --untracked-files=all)
    if ($dirty.Count -eq 0) {
      git worktree remove $probeRoot
    } else {
      Write-Error "probe worktree remains dirty: $probeRoot"
    }
  }
  if (Test-Path -LiteralPath $probePatchPath) {
    Remove-Item -LiteralPath $probePatchPath
  }
}
Get-Content -Raw -Encoding utf8 $probeResultPath
```

Expected: JSON은 현재 HEAD, exact patch SHA-256, 실제 changed generated paths를 가진다. `git worktree remove`는 tracked clean 확인 뒤에만 실행된다. patch가 더 이상 적용되지 않으면 OpenAPI 현재 상태를 다시 읽고 계획·Issue를 재검토하지 않은 채 patch를 추측해 바꾸지 않는다.

- [ ] **Gate 7: UTF-8 Issue body를 생성·제출·readback 검증한다**

아래 block은 shell 변수를 모두 다시 계산하며 Gate 5/6 process 상태에 의존하지 않는다. 기존 title/state/assignee와 본문 나머지를 보존하고, 정확히 두 section만 교체한다.

```powershell
$repoSlug = 'sparta-spring4/Commerce-Final-Project-MiriYum'
$issueBeforeRaw = gh issue view 52 --repo $repoSlug --json title,state,assignees,labels,milestone,body,url
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueBeforeRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $issueBefore = $issueBeforeRaw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
$devSha = gh api "repos/$repoSlug/commits/dev" --jq .sha
if ($LASTEXITCODE -ne 0 -or $devSha -notmatch '^[0-9a-f]{40}$') { throw 'BLOCKED: live dev SHA query failed or returned invalid data' }
if ($devSha -ne (git rev-parse origin/dev)) { throw 'origin/dev fetch is stale; refetch and rerun gate' }
$pr213Raw = gh pr view 213 --repo $repoSlug --json state,mergeCommit,mergedAt,baseRefName
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pr213Raw)) { throw 'BLOCKED: PR #213 query failed' }
try { $pr213 = $pr213Raw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: PR #213 returned invalid JSON' }
if ($pr213.state -ne 'MERGED' -or $pr213.baseRefName -ne 'dev' -or $null -eq $pr213.mergeCommit) {
  throw 'PR #213 live merge gate failed'
}
$devMigrationPaths = @(git ls-tree -r --name-only origin/dev -- backend/src/main/resources/db/migration)
if ($LASTEXITCODE -ne 0 -or $devMigrationPaths.Count -eq 0) { throw 'BLOCKED: origin/dev migration listing failed or is empty' }
if (@($devMigrationPaths | Where-Object { $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }).Count -ne 0) { throw 'BLOCKED: invalid origin/dev migration path set' }
$openPrRaw = gh pr list --repo $repoSlug --state open --limit 100 --json number,files
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($openPrRaw)) { throw 'BLOCKED: open PR migration query failed' }
try { $openPrData = @($openPrRaw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'BLOCKED: open PR migration query returned invalid JSON' }
if (-not $openPrRaw.TrimStart().StartsWith('[')) { throw 'BLOCKED: open PR migration result is not a JSON array' }
$openPaths = @($openPrData | ForEach-Object { @($_.files) | ForEach-Object { $_.path } })
if (@($openPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\\' }).Count -ne 0) { throw 'BLOCKED: open PR file path set is invalid' }
$invalidOpenMigrationPaths = @($openPaths | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' -and $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
if ($invalidOpenMigrationPaths.Count -ne 0) { throw 'BLOCKED: open PR migration path set is invalid; no version may be selected' }
$openMigrationPaths = @($openPaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
$migrationFiles = @($devMigrationPaths + $openMigrationPaths)
$migrationVersions = @($migrationFiles | ForEach-Object {
  if ($_ -match '/V([0-9]+)__') { [int]$Matches[1] } else { throw "BLOCKED: invalid migration path: $_" }
})
if ($migrationVersions.Count -ne $migrationFiles.Count -or $migrationVersions.Count -eq 0) { throw 'BLOCKED: migration version set is invalid' }
$nextMigrationVersion = (($migrationVersions | Measure-Object -Maximum).Maximum + 1)
$migrationPath = "backend/src/main/resources/db/migration/V${nextMigrationVersion}__create_reservation_fulfillment_audits.sql"
$probeResultPath = Join-Path $env:TEMP 'miriyum-issue52-generator-probe.json'
if (-not (Test-Path -LiteralPath $probeResultPath)) { throw 'generator probe evidence missing' }
$probe = Get-Content -Raw -Encoding utf8 $probeResultPath | ConvertFrom-Json
if ($probe.head -ne (git rev-parse HEAD)) { throw 'generator probe HEAD is stale' }
$generatedPaths = @($probe.generatedPaths)
$fixedPaths = @(
  'docs/superpowers/specs/2026-08-10-reservation-fulfillment-design.md',
  'docs/superpowers/plans/2026-08-10-reservation-fulfillment.md',
  'docs/specs/reservation/spec.md',
  'docs/specs/reservation/openapi.yaml',
  'docs/specs/mvp1-common/ownership.md',
  'docs/specs/mvp1-common/domain-model.md',
  'docs/03-domain-model.md',
  'docs/specs/store-search/spec.md',
  'docs/specs/mvp1-common/spec.md',
  'docs/07-data-and-api-contracts.md',
  'docs/05-functional-requirements.md',
  'docs/service-policies/09-checkin-noshow.md',
  'docs/service-policies/04-reservation.md',
  'backend/src/main/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequest.java',
  'backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentActorType.java',
  'backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAudit.java',
  'backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java',
  'backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandResult.java',
  'backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java',
  'backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java',
  'backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java',
  'backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java',
  'backend/src/test/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequestTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAuditTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java'
)
$exactPaths = @($fixedPaths + $migrationPath + $generatedPaths | Sort-Object -Unique)
if ($exactPaths.Count -ne ($fixedPaths.Count + 1 + $generatedPaths.Count)) {
  throw 'implementation allowlist contains duplicates'
}
$allowlistLines = ($exactPaths | ForEach-Object { "- ``$_``" }) -join "`n"
$scopeSection = @"
### 구현 단계 repository 쓰기 허용 경로

$allowlistLines

위 목록은 $($exactPaths.Count)개 고유 경로의 exact implementation allowlist다. 목록 밖 경로는 수정·stage·commit하지 않는다.

"@
$gateSection = @"
### 구현 시작 게이트

구현 판정: ``READY FOR IMPLEMENTATION``

- PR #213 merge commit: ``$($pr213.mergeCommit.oid)``
- 재게이트 ``dev`` SHA: ``$devSha``
- 확정 migration: ``$migrationPath``
- generator patch SHA-256: ``$($probe.patchSha256)``
- generated paths: $([string]::Join(', ', $generatedPaths))
- 검증: ``backend\gradlew.bat -p backend --version``, focused ``test``, class-filtered ``integrationTest``, final ``build --rerun-tasks``, ``pnpm --dir frontend generate:api``, ``git diff --check``

"@
$body = $issueBefore.body
if ([regex]::Matches($body, '현재 판정: `READY FOR PLAN`').Count -ne 1) {
  throw 'expected one READY FOR PLAN marker'
}
$body = $body.Replace('현재 판정: `READY FOR PLAN`', '현재 판정: `READY FOR IMPLEMENTATION`')
$scopePattern = '(?s)### 계획 단계 repository 쓰기 허용 경로.*?(?=### 구현 시작 게이트)'
$gatePattern = '(?s)### 구현 시작 게이트.*?(?=## 확정된 제품 경계)'
if ([regex]::Matches($body, $scopePattern).Count -ne 1) { throw 'plan scope section mismatch' }
if ([regex]::Matches($body, $gatePattern).Count -ne 1) { throw 'implementation gate section mismatch' }
$body = [regex]::Replace($body, $scopePattern, $scopeSection)
$body = [regex]::Replace($body, $gatePattern, $gateSection)
$issueBodyPath = Join-Path $env:TEMP "miriyum-issue-52-body-$([guid]::NewGuid().ToString('N')).md"
[System.IO.File]::WriteAllText(
  $issueBodyPath,
  $body,
  [System.Text.UTF8Encoding]::new($false)
)
gh issue edit 52 --repo $repoSlug --body-file $issueBodyPath
if ($LASTEXITCODE -ne 0) { throw 'Issue body edit failed' }
$issueAfterRaw = gh issue view 52 --repo $repoSlug --json title,state,assignees,labels,milestone,body,url
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueAfterRaw)) { throw 'BLOCKED: Issue #52 readback query failed' }
try { $issueAfter = $issueAfterRaw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: Issue #52 readback returned invalid JSON' }
if ($issueAfter.body -ne (Get-Content -Raw -Encoding utf8 $issueBodyPath)) {
  throw 'Issue body readback mismatch'
}
if ($issueAfter.title -ne $issueBefore.title -or $issueAfter.state -ne $issueBefore.state) {
  throw 'Issue title/state changed unexpectedly'
}
$beforeAssignees = @($issueBefore.assignees.login | Sort-Object)
$afterAssignees = @($issueAfter.assignees.login | Sort-Object)
if ([string]::Join(',', $beforeAssignees) -ne [string]::Join(',', $afterAssignees)) {
  throw 'Issue assignees changed unexpectedly'
}
$beforeLabels = @($issueBefore.labels.name | Sort-Object)
$afterLabels = @($issueAfter.labels.name | Sort-Object)
if ([string]::Join(',', $beforeLabels) -ne [string]::Join(',', $afterLabels)) {
  throw 'Issue labels changed unexpectedly'
}
$beforeMilestone = if ($null -eq $issueBefore.milestone) { '' } else { $issueBefore.milestone.title }
$afterMilestone = if ($null -eq $issueAfter.milestone) { '' } else { $issueAfter.milestone.title }
if ($beforeMilestone -ne $afterMilestone) { throw 'Issue milestone changed unexpectedly' }
if ($issueAfter.body -notmatch 'READY FOR IMPLEMENTATION') { throw 'ready marker missing' }
$exactPaths | ForEach-Object {
  if ($issueAfter.body -notmatch [regex]::Escape("``$_``")) {
    throw "Issue allowlist missing: $_"
  }
}
Remove-Item -LiteralPath $issueBodyPath -Force
```

Expected: UTF-8 body readback이 byte-decoded text로 일치하고 title/state/assignees가 보존된다. exact path는 중복이 없고 migration/generated 결과가 현재 live 상태와 일치한다.

- [ ] **Gate 8: 구현 직전 범위를 재확인한다**

```powershell
git status --short
git diff --name-only origin/dev...HEAD
git diff --check
```

Expected: 현재 branch diff는 승인된 #52 설계·plan 문서뿐이고, user-owned untracked 경로 외 tracked/staged 변경이 없다.

### Task 1: Canonical Contracts, Strict Request, and OpenAPI

**Files:**
- Modify: 위 File Responsibility Map의 canonical contract 11경로
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequestTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java`
- Conditional modify: Gate 6에서 확인한 generated 경로

**Interfaces:**
- Consumes: 공통 `PublicId`, `IdempotencyKey`, `ReservationSuccessResponse`, `StoreAccessDenied`, `ReservationNotFound`, `ReservationStateConflict` OpenAPI components.
- Produces: field 없는 `public record ReservationFulfillmentRequest()`, 기존 fulfillment route의 strict body와 정확한 400·401·403·404·409 contract.

- [ ] **Task preflight: live Issue의 exact 경로와 고유성을 다시 읽는다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scopeMatch = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scopeMatch.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scopeMatch.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
$duplicates = @($livePaths | Group-Object | Where-Object Count -gt 1)
$liveMigrations = @($livePaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V\d+__create_reservation_fulfillment_audits\.sql$' })
$liveGenerated = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
if ($duplicates.Count -ne 0 -or $liveMigrations.Count -ne 1) { throw 'Issue exact allowlist is invalid' }
$taskPaths = @(
  'docs/specs/reservation/spec.md',
  'docs/specs/reservation/openapi.yaml',
  'docs/specs/mvp1-common/ownership.md',
  'docs/specs/mvp1-common/domain-model.md',
  'docs/03-domain-model.md',
  'docs/specs/store-search/spec.md',
  'docs/specs/mvp1-common/spec.md',
  'docs/07-data-and-api-contracts.md',
  'docs/05-functional-requirements.md',
  'docs/service-policies/09-checkin-noshow.md',
  'docs/service-policies/04-reservation.md',
  'backend/src/main/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequestTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java'
) + $liveGenerated
$missingTaskPaths = @($taskPaths | Where-Object { $_ -notin $livePaths })
if ($missingTaskPaths.Count -ne 0) { throw "Task 1 paths missing from live Issue allowlist: $($missingTaskPaths -join ',')" }
```

Expected: 고유 exact paths, 정확히 한 migration, 0개 이상의 명시적 generated paths를 현재 shell에서 얻는다. 이 task는 canonical/DTO/OpenAPI/test와 `$liveGenerated` 밖을 수정하지 않는다.

- [ ] **Step 1: strict request와 OpenAPI의 실패 테스트를 작성한다**

```java
@Test
void fulfillmentRequestHasNoBindableFields() {
    assertThat(ReservationFulfillmentRequest.class.isRecord()).isTrue();
    assertThat(ReservationFulfillmentRequest.class.getRecordComponents()).isEmpty();
}

@Test
void fulfillmentOperationResolvesStrictBodyAndAllConflictExamples() throws IOException {
    Map<String, Object> document = load(
            Path.of("..", "docs", "specs", "reservation", "openapi.yaml"));
    Map<String, Object> operation = map(map(map(document.get("paths")).get(
            "/api/v1/store-operator/stores/{storeId}/reservations/"
                    + "{reservationId}/fulfillments")).get("post"));
    Map<String, Object> schema = map(map(map(operation.get("requestBody"))
            .get("content")).get("application/json"));
    assertThat(map(schema.get("schema")))
            .containsEntry("$ref", "#/components/schemas/EmptyCommandRequest");
    assertThat(map(operation.get("responses")))
            .containsKeys("200", "400", "401", "403", "404", "409");
    assertThat(map(map(operation.get("responses")).get("409")))
            .containsEntry("$ref", "#/components/responses/ReservationFulfillmentConflict");
    Map<String, Object> conflict = resolveLocalResponse(document, operation, "409");
    Map<String, Object> examples = map(map(map(conflict.get("content"))
            .get("application/json")).get("examples"));
    assertThat(examples.values().stream()
            .map(ReservationOpenApiContractTest::map)
            .map(example -> map(example.get("value")).get("code")))
            .containsExactlyInAnyOrder(
                    "RESERVATION_005", "MENU_HOLD_006", "COMMON_007", "COMMON_008");
}

private static Map<String, Object> resolveLocalResponse(
        Map<String, Object> document,
        Map<String, Object> operation,
        String status
) {
    String reference = String.valueOf(
            map(map(operation.get("responses")).get(status)).get("$ref"));
    String prefix = "#/components/responses/";
    assertThat(reference).startsWith(prefix);
    return map(map(map(document.get("components")).get("responses"))
            .get(reference.substring(prefix.length())));
}
```

- [ ] **Step 2: RED를 관찰한다**

Run from repository root:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequestTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
```

Expected: `ReservationFulfillmentRequest`가 없어 test compilation이 실패하거나, fulfillment 전용 오류 예시 assertion이 실패한다.

- [ ] **Step 3: 최소 request DTO를 만든다**

```java
package com.miriyum.domain.reservation.dto.request;

/** 매장 운영자 예약 방문 완료의 field 없는 명령 body다. */
public record ReservationFulfillmentRequest() {
}
```

- [ ] **Step 4: 정본 10개 Markdown 경로의 정확한 section과 문구를 보정한다**

OpenAPI를 제외한 각 문서에 아래 문장을 지정 section의 기존 bullet과 합치거나 바로 뒤에 추가한다. 뜻이 같은 기존 문장이 있으면 중복 추가하지 않고 아래 문장으로 교체한다.

| 경로 | 정확한 section | 추가·교체할 문구 |
| --- | --- | --- |
| `docs/specs/reservation/spec.md` | `## 방문 완료` | `활성 매장 운영자의 현재 대표 소유권을 fresh와 replay에서 확인한다. CLOSED·휴점은 신규 거래만 차단하며 이미 CONFIRMED인 예약의 방문 완료는 허용한다. Reservation과 연결 MenuHold, reservation_fulfillment_audits 성공 감사, 멱등 성공 결과는 한 트랜잭션에서 모두 commit하거나 rollback하며 수용량·allocation·메뉴 재고·수량 원장을 조회하거나 복구하지 않는다.` |
| `docs/specs/mvp1-common/ownership.md` | `## O-006 매장 운영자의 예약 관리` → `### 상태와 API 경계` | `공통 관리 권한은 활성 store-operator 계정과 현재 대표 운영자 FK 일치까지다. 신규 거래의 Store 상태 적격성과 기존 거래 종결의 허용 여부는 각 명령 계약이 결정하며, 예약 방문 완료는 CLOSED·휴점에서도 현재 대표 운영자에게 허용한다.` |
| `docs/specs/mvp1-common/domain-model.md` | `## E-005 멱등 처리와 고정 잠금 순서` → `### 유스케이스별 적용` | `예약 방문 완료의 잠금 순서는 Global idempotency row → store-scoped Reservation → MenuHold root다. CONFIRMED Reservation과 연결 MenuHold만 FULFILLED로 종결하고 capacity bucket·allocation·inventory·ledger는 잠그거나 변경하지 않는다.` |
| `docs/03-domain-model.md` | `## 계정과 매장 관계` | 기존 `매장 관리 권한은 유효한 운영자 계정, 대상 매장의 대표 운영자 FK 일치와 대상 매장 상태를 함께 충족할 때 성립한다.` bullet을 `매장 관리의 공통 권한은 유효한 운영자 계정과 대상 매장의 대표 운영자 FK 일치로 성립한다. 현재 매장 상태 적격성은 명령별 계약이 판단하며, 예약 취소·방문 완료 같은 기존 확정 거래 종결은 CLOSED·휴점에서도 허용할 수 있다.`로 교체한다. |
| `docs/specs/store-search/spec.md` | `### 감사와 영향 거래 경계` | `휴점·폐점과 운영 모드 변경은 이후 신규 거래 후보를 차단하지만 기존 CONFIRMED 예약을 자동 변경하지 않는다. 현재 대표 운영자는 Reservation 소유 계약에 따라 기존 예약을 취소하거나 방문 완료할 수 있다.` |
| `docs/specs/mvp1-common/spec.md` | `## C-013 인증·인가 HTTP 상태와 개인 자원 존재 은닉` → `### 매장 운영자 권한 판정` | `활성 계정과 현재 대표 소유권은 모든 매장 관리 명령의 공통 preflight다. OPEN·승인·기능 활성 같은 거래 상태는 명령별 계약이며 기존 CONFIRMED 예약의 취소·방문 완료에는 별도 OPEN 조건을 적용하지 않는다.` |
| `docs/07-data-and-api-contracts.md` | `## 예약 자원·수량 원장` | `방문 완료는 Reservation과 연결 MenuHold의 상태만 FULFILLED로 종결한다. 예약 수용량, allocation, 메뉴 재고와 수량 원장은 정상 이행 이력으로 보존하며 조회·복구·삭제하지 않는다.` |
| `docs/07-data-and-api-contracts.md` | `## 멱등성과 오류 응답` | `예약 방문 완료의 양수 storeId·reservationId는 경로 식별자로 fingerprint에 포함한다. 비양수 ID는 COMMON_001, 양수이지만 대상 매장 범위에 없는 예약은 RESERVATION_001이며, replay 전에도 현재 대표 소유권을 다시 확인한다.` |
| `docs/05-functional-requirements.md` | 표의 `예약` 행 `단계 경계` cell | `1차는 결제 없는 즉시 확정 예약, 사용자·운영자 취소와 운영자 직접 방문 완료다. 직접 방문 완료는 QR·체크인 시각·노쇼 판정 없이 독립 실행한다. 예약금·환불과 QR·노쇼 연결은 고도화에서만 진입한다.` |
| `docs/05-functional-requirements.md` | 표의 `체크인·노쇼` 행 `단계 경계` cell | `고도화는 회전형 QR과 노쇼 절차를 제공하며, 1차 MVP 운영자 직접 방문 완료 결과를 보조 방문 증거로 소비한다. QR은 #52의 선행 조건이나 대체 endpoint가 아니다.` |
| `docs/service-policies/09-checkin-noshow.md` | 문서 첫 `고도화 경계` 인용문 | `1차 MVP의 매장 운영자 직접 방문 완료는 QR·시간·노쇼 판정 없는 독립 Reservation 명령이다. 고도화 CHECK는 QR 성공 또는 그 기존 완료 결과를 정상 방문 증거로 소비하며 #52를 다시 실행하지 않는다.` |
| `docs/service-policies/09-checkin-noshow.md` | `## CHECK-006 노쇼 후보와 확정 절차` → `### 확정 상태·행위자·절차` | `고도화 판정은 QR 체크인 성공 또는 이미 확정된 운영자 방문 완료를 정상 방문 증거로 사용한다. #52 완료는 CHECK 상태를 생성하지 않고 CHECK가 #52의 사전 조건도 되지 않는다.` |
| `docs/service-policies/04-reservation.md` | `## RES-014 예약 상태와 상태 전이` → `### 동시성·실패·복구·알림·감사·개인정보` | `방문 완료 성공 감사는 actor, requestedAt, occurredAt, CONFIRMED → FULFILLED, 잠긴 Reservation의 reservation_time_policy_version·capacity_policy_version과 commandId를 기록한다. 자유입력 reason은 두지 않고 RESERVATION_FULFILL 명령 자체를 고정 전이 사유로 사용한다.` |

수정 직후 각 literal이 해당 소유 section에 한 번만 존재하는지 확인한다.

```powershell
function Assert-LiteralOnce([string]$path, [string]$literal) {
  $text = Get-Content -Raw -Encoding utf8 $path
  $count = [regex]::Matches($text, [regex]::Escape($literal)).Count
  if ($count -ne 1) { throw "$path must contain the canonical literal exactly once; count=$count; literal=$literal" }
}
Assert-LiteralOnce 'docs/specs/reservation/spec.md' 'CLOSED·휴점은 신규 거래만 차단하며 이미 CONFIRMED인 예약의 방문 완료는 허용한다.'
Assert-LiteralOnce 'docs/specs/mvp1-common/ownership.md' '공통 관리 권한은 활성 store-operator 계정과 현재 대표 운영자 FK 일치까지다.'
Assert-LiteralOnce 'docs/specs/mvp1-common/domain-model.md' '예약 방문 완료의 잠금 순서는 Global idempotency row → store-scoped Reservation → MenuHold root다.'
Assert-LiteralOnce 'docs/03-domain-model.md' '매장 관리의 공통 권한은 유효한 운영자 계정과 대상 매장의 대표 운영자 FK 일치로 성립한다.'
Assert-LiteralOnce 'docs/specs/store-search/spec.md' '휴점·폐점과 운영 모드 변경은 이후 신규 거래 후보를 차단하지만 기존 CONFIRMED 예약을 자동 변경하지 않는다.'
Assert-LiteralOnce 'docs/specs/mvp1-common/spec.md' '활성 계정과 현재 대표 소유권은 모든 매장 관리 명령의 공통 preflight다.'
Assert-LiteralOnce 'docs/07-data-and-api-contracts.md' '방문 완료는 Reservation과 연결 MenuHold의 상태만 FULFILLED로 종결한다.'
Assert-LiteralOnce 'docs/07-data-and-api-contracts.md' '예약 방문 완료의 양수 storeId·reservationId는 경로 식별자로 fingerprint에 포함한다.'
Assert-LiteralOnce 'docs/05-functional-requirements.md' '1차는 결제 없는 즉시 확정 예약, 사용자·운영자 취소와 운영자 직접 방문 완료다.'
Assert-LiteralOnce 'docs/05-functional-requirements.md' '고도화는 회전형 QR과 노쇼 절차를 제공하며, 1차 MVP 운영자 직접 방문 완료 결과를 보조 방문 증거로 소비한다.'
Assert-LiteralOnce 'docs/service-policies/09-checkin-noshow.md' '1차 MVP의 매장 운영자 직접 방문 완료는 QR·시간·노쇼 판정 없는 독립 Reservation 명령이다.'
Assert-LiteralOnce 'docs/service-policies/09-checkin-noshow.md' '고도화 판정은 QR 체크인 성공 또는 이미 확정된 운영자 방문 완료를 정상 방문 증거로 사용한다.'
Assert-LiteralOnce 'docs/service-policies/04-reservation.md' '자유입력 reason은 두지 않고 RESERVATION_FULFILL 명령 자체를 고정 전이 사유로 사용한다.'
```

- [ ] **Step 5: OpenAPI fulfillment contract를 보강한다**

기존 route와 `EmptyCommandRequest` schema를 유지하고 route의 409를 `#/components/responses/ReservationFulfillmentConflict`로 바꾼다. Gate 6 patch와 byte-for-byte 같은 component를 추가한다.

```yaml
"403":
  $ref: "#/components/responses/StoreAccessDenied"
"404":
  $ref: "#/components/responses/ReservationNotFound"
"409":
  $ref: "#/components/responses/ReservationFulfillmentConflict"
```

```yaml
ReservationFulfillmentConflict:
  description: 방문 완료 상태·MenuHold·멱등·동시 요청 충돌
  content:
    application/json:
      schema:
        $ref: "../mvp1-common/openapi.yaml#/components/schemas/ErrorResponse"
      examples:
        invalidReservationState:
          value: { code: RESERVATION_005, message: 현재 예약 상태에서 처리할 수 없습니다. }
        invalidMenuHoldState:
          value: { code: MENU_HOLD_006, message: 현재 수량 상태에서 요청한 전이를 수행할 수 없습니다. }
        idempotencyKeyReused:
          value: { code: COMMON_007, message: 동일한 Idempotency-Key를 다른 요청에 사용할 수 없습니다. }
        concurrentModification:
          value: { code: COMMON_008, message: 동시 요청 충돌로 처리하지 못했습니다. 다시 시도해 주세요. }
```

403은 기존 `StoreAccessDenied`, 404는 기존 `ReservationNotFound` `$ref`를 유지한다. 외부 code/message는 실제 `StoreErrorCode`, `ReservationErrorCode`, `MenuHoldErrorCode`, `CommonErrorCode`와 대조한다.

- [ ] **Step 6: generator를 실행하고 Gate 6 결과와 일치하는 경로만 유지한다**

Run from repository root:

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $issueBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($issueBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$allowedGenerated = @([regex]::Matches(
  $issueBody,
  '`(frontend/src/shared/api/generated/[^`]+\.ts)`'
) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
pnpm --dir frontend generate:api
$changedGenerated = @(git diff --name-only -- frontend/src/shared/api/generated)
if ([string]::Join("`n", $changedGenerated) -ne [string]::Join("`n", $allowedGenerated)) {
  throw 'generated diff does not match live Issue exact allowlist'
}
git diff --check
```

Expected: generated diff는 live Issue exact allowlist에 기록된 경로와 정확히 같다. 0 diff로 판정됐다면 generated 파일은 변경되지 않는다.

- [ ] **Step 7: GREEN과 문서 계약을 검증한다**

Run from repository root:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequestTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
```

Expected: 두 test class가 PASS하고 기존 cancellation OpenAPI assertions도 유지된다.

- [ ] **Step 8: 지정 경로만 commit한다**

```powershell
git add docs/specs/reservation/spec.md docs/specs/reservation/openapi.yaml docs/specs/mvp1-common/ownership.md docs/specs/mvp1-common/domain-model.md docs/03-domain-model.md docs/specs/store-search/spec.md docs/specs/mvp1-common/spec.md docs/07-data-and-api-contracts.md docs/05-functional-requirements.md docs/service-policies/09-checkin-noshow.md docs/service-policies/04-reservation.md backend/src/main/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequest.java backend/src/test/java/com/miriyum/domain/reservation/dto/request/ReservationFulfillmentRequestTest.java backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $issueBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($issueBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$generatedToStage = @([regex]::Matches(
  $issueBody,
  '`(frontend/src/shared/api/generated/[^`]+\.ts)`'
) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
if ($generatedToStage.Count -gt 0) { git add -- $generatedToStage }
git diff --cached --name-only
git commit -m "feat(reservation): 방문 완료 계약을 확정한다"
```

Gate 6에서 generated 0 diff면 두 번째 `git add`는 실행하지 않는다. 다른 generated 경로가 exact allowlist에 확정됐다면 그 경로만 명시적으로 stage한다.

### Task 2: Fulfillment Audit Persistence

**Files:**
- Create: live Issue exact allowlist에서 이 task 시작 시 다시 읽은 유일한 versioned `create_reservation_fulfillment_audits.sql`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentActorType.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAuditTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java`

**Interfaces:**
- Consumes: `ReservationStatus.CONFIRMED`, `ReservationStatus.FULFILLED`, `reservations(reservation_id)`.
- Produces: `ReservationFulfillmentAudit.recordSuccess(long, ReservationFulfillmentActorType, long, Instant, Instant, ReservationStatus, ReservationStatus, long, long, String)` and `Optional<ReservationFulfillmentAudit> findByReservationId(Long)`.

- [ ] **Task preflight: live Issue migration과 dev/open-PR 충돌을 다시 계산한다**

```powershell
$repoSlug = 'sparta-spring4/Commerce-Final-Project-MiriYum'
git fetch origin dev
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: origin/dev fetch failed' }
$issueRaw = gh issue view 52 --repo $repoSlug --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scopeMatch = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scopeMatch.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scopeMatch.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
$duplicates = @($livePaths | Group-Object | Where-Object Count -gt 1)
$migrationPaths = @($livePaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V\d+__create_reservation_fulfillment_audits\.sql$' })
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
if ($duplicates.Count -ne 0 -or $migrationPaths.Count -ne 1) { throw 'Issue exact allowlist is invalid' }
$migrationPath = $migrationPaths[0]
$devMigrationPaths = @(git ls-tree -r --name-only origin/dev -- backend/src/main/resources/db/migration)
if ($LASTEXITCODE -ne 0 -or $devMigrationPaths.Count -eq 0) { throw 'BLOCKED: origin/dev migration listing failed or is empty' }
if (@($devMigrationPaths | Where-Object { $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }).Count -ne 0) { throw 'BLOCKED: invalid origin/dev migration path set' }
$openPrRaw = gh pr list --repo $repoSlug --state open --limit 100 --json number,files
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($openPrRaw)) { throw 'BLOCKED: open PR migration query failed' }
try { $openPrData = @($openPrRaw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'BLOCKED: open PR migration query returned invalid JSON' }
if (-not $openPrRaw.TrimStart().StartsWith('[')) { throw 'BLOCKED: open PR migration result is not a JSON array' }
$openPaths = @($openPrData | ForEach-Object { @($_.files) | ForEach-Object { $_.path } })
if (@($openPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\\' }).Count -ne 0) { throw 'BLOCKED: open PR file path set is invalid' }
$invalidOpenMigrationPaths = @($openPaths | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' -and $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
if ($invalidOpenMigrationPaths.Count -ne 0) { throw 'BLOCKED: open PR migration path set is invalid; no version may be selected' }
$openMigrationPaths = @($openPaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
$occupied = @($devMigrationPaths + $openMigrationPaths)
$maxVersion = (@($occupied | ForEach-Object { if ($_ -match '/V([0-9]+)__') { [int]$Matches[1] } }) | Measure-Object -Maximum).Maximum
if ($null -eq $maxVersion) { throw 'BLOCKED: no valid migration version; do not select a filename' }
$expectedMigration = "backend/src/main/resources/db/migration/V$($maxVersion + 1)__create_reservation_fulfillment_audits.sql"
if ($migrationPath -ne $expectedMigration -or $occupied -contains $migrationPath) { throw 'BLOCKED: migration allocation drifted; rerun Mandatory Execution Gate and update Issue before editing' }
$taskPaths = @(
  $migrationPath,
  'backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentActorType.java',
  'backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAudit.java',
  'backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java',
  'backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAuditTest.java',
  'backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java'
)
$missingTaskPaths = @($taskPaths | Where-Object { $_ -notin $livePaths })
if ($missingTaskPaths.Count -ne 0) { throw "Task 2 paths missing from live Issue allowlist: $($missingTaskPaths -join ',')" }
```

Expected: live Issue에 migration이 정확히 하나 있고 최신 `origin/dev`와 모든 open PR 뒤의 유일한 다음 버전이다. 다르면 이 task를 시작하지 않는다.

- [ ] **Step 1: entity 불변식 RED test를 작성한다**

```java
@Test
void recordsAndRoundTripsEverySuccessfulStoreOperatorAuditField() {
    ReservationFulfillmentAudit audit = ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT,
            ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
            9L, 12L,
            "  " + COMMAND_ID + "  ");

    assertThat(audit.getReservationId()).isEqualTo(77L);
    assertThat(audit.getActorType()).isEqualTo(ReservationFulfillmentActorType.STORE_OPERATOR);
    assertThat(audit.getActorId()).isEqualTo(33L);
    assertThat(audit.getRequestedAt()).isSameAs(REQUESTED_AT);
    assertThat(audit.getOccurredAt()).isSameAs(OCCURRED_AT);
    assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.FULFILLED);
    assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(9L);
    assertThat(audit.getCapacityPolicyVersion()).isEqualTo(12L);
    assertThat(audit.getCommandId()).isEqualTo(COMMAND_ID);
}

@Test
void rejectsNullBlankAndOversizedCommandId() {
    assertThatThrownBy(() -> auditWithCommand(null))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> auditWithCommand("   "))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> auditWithCommand("a".repeat(101)))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsNonPositiveReservationAndActorIds() {
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            0L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 0L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsNonPositivePolicyVersions() {
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 0L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 0L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsNullActorRequestedAndOccurredTimes() {
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, null, 33L, REQUESTED_AT, OCCURRED_AT,
            ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
            9L, 12L, COMMAND_ID)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            null, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, null, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsNullStatusesAndEveryNonFulfillmentTransition() {
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, null,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            null, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CANCELLED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.CONFIRMED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void rejectsOccurredBeforeRequested() {
    assertThatThrownBy(() -> ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            OCCURRED_AT, REQUESTED_AT, ReservationStatus.CONFIRMED,
            ReservationStatus.FULFILLED, 9L, 12L, COMMAND_ID))
            .isInstanceOf(IllegalArgumentException.class);
}

private static ReservationFulfillmentAudit auditWithCommand(String commandId) {
    return ReservationFulfillmentAudit.recordSuccess(
            77L, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
            REQUESTED_AT, OCCURRED_AT,
            ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
            9L, 12L, commandId);
}
```

- [ ] **Step 2: RED를 관찰한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationFulfillmentAuditTest"
```

Expected: 새 entity와 enum이 없어 compilation FAIL이다.

- [ ] **Step 3: migration contract RED test를 작성한다**

`ReservationMigrationTest`에 다음 이름의 tests를 먼저 추가한다. `latestVersion()`은 실제 적용된 fulfillment migration filename에서 정수만 파싱하고, 기존 `reservation()`·`insertV25ParentsAndCancelledReservation(MySQLContainer)` fixture를 직접 사용한다.

```java
@Test
void cleanInstallAppliesFulfillmentAuditMigrationAndJpaRoundTrips() {
    assertThat(appliedScripts()).contains(migrationScriptName());
    long reservationId = reservationRepository.saveAndFlush(reservation()).getId();
    ReservationFulfillmentAudit saved = auditRepository.saveAndFlush(
            ReservationFulfillmentAudit.recordSuccess(
                    reservationId, ReservationFulfillmentActorType.STORE_OPERATOR, 33L,
                    REQUESTED_AT, OCCURRED_AT,
                    ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
                    9L, 12L, COMMAND_ID));
    entityManager.clear();
    assertThat(auditRepository.findByReservationId(reservationId))
            .get().satisfies(audit -> {
                assertThat(audit.getId()).isEqualTo(saved.getId());
                assertThat(audit.getReservationId()).isEqualTo(reservationId);
                assertThat(audit.getActorType()).isEqualTo(ReservationFulfillmentActorType.STORE_OPERATOR);
                assertThat(audit.getActorId()).isEqualTo(33L);
                assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
                assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
                assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.FULFILLED);
                assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(9L);
                assertThat(audit.getCapacityPolicyVersion()).isEqualTo(12L);
                assertThat(audit.getCommandId()).isEqualTo(COMMAND_ID);
            });
}

@Test
void upgradeFromImmediatelyPreviousVersionPreservesReservationWithoutAuditBackfill()
        throws Exception {
    try (MySQLContainer<?> legacy = new MySQLContainer<>(MYSQL_IMAGE)) {
        legacy.start();
        Flyway.configure().dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                .target(MigrationVersion.fromVersion(String.valueOf(latestVersion() - 1)))
                .load().migrate();
        insertV25ParentsAndCancelledReservation(legacy);
        long reservationId = 40001L;
        Flyway upgraded = Flyway.configure()
                .dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                .load();
        upgraded.migrate();
        JdbcTemplate upgradedJdbc = new JdbcTemplate(dataSource(legacy));
        assertThat(statusOf(upgradedJdbc, reservationId)).isEqualTo("CANCELLED");
        assertThat(countAudits(upgradedJdbc, reservationId)).isZero();
        assertThat(Arrays.stream(upgraded.info().applied()).map(MigrationInfo::getScript))
                .contains(migrationScriptName());
    }
}

@Test
void databaseRejectsDuplicateReservationMissingParentAndParentDelete() {
    long reservationId = newReservationId();
    insertAudit(jdbcTemplate, reservationId, "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, COMMAND_ID);
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, reservationId, "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, COMMAND_ID + "-2"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, Long.MAX_VALUE, "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "fk"));
    assertDatabaseRejects(() -> jdbcTemplate.update(
            "DELETE FROM reservations WHERE reservation_id = ?", reservationId));
}

@Test
void databaseRejectsEveryRequiredNullColumn() {
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, null, "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "null-reservation"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), null, 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "null-actor-type"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", null,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "null-actor-id"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            null, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "null-requested"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, null, "CONFIRMED", "FULFILLED", 9L, 12L, "null-occurred"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, null, "FULFILLED", 9L, 12L, "null-before"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", null, 9L, 12L, "null-after"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", null, 12L, "null-time-version"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, null, "null-capacity-version"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, null));
}

@Test
void databaseRejectsInvalidActorTransitionTimeAndVersions() {
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "CONSUMER", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "actor"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 0L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "actor-id"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            OCCURRED_AT, REQUESTED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "time"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CANCELLED", "FULFILLED", 9L, 12L, "before"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "CONFIRMED", 9L, 12L, "after"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 0L, 12L, "time-version"));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 0L, "capacity-version"));
}

@Test
void databaseRejectsBlankTrimmedAndOversizedCommandIds() {
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "   "));
    assertDatabaseRejects(() -> insertAudit(jdbcTemplate, newReservationId(), "STORE_OPERATOR", 33L,
            REQUESTED_AT, OCCURRED_AT, "CONFIRMED", "FULFILLED", 9L, 12L, "a".repeat(101)));
}

private static void assertDatabaseRejects(ThrowingCallable statement) {
    assertThatThrownBy(statement).isInstanceOf(DataAccessException.class);
}

private static void insertAudit(
        JdbcTemplate jdbc, Long reservationId, String actorType, Long actorId,
        Instant requestedAt, Instant occurredAt, String beforeStatus,
        String afterStatus, Long timeVersion, Long capacityVersion, String commandId
) {
    jdbc.update("""
            INSERT INTO reservation_fulfillment_audits (
              reservation_id, actor_type, actor_id, requested_at, occurred_at,
              before_status, after_status, reservation_time_policy_version,
              capacity_policy_version, command_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, reservationId, actorType, actorId, timestampOrNull(requestedAt),
            timestampOrNull(occurredAt), beforeStatus, afterStatus,
            timeVersion, capacityVersion, commandId);
}

private long newReservationId() {
    return reservationRepository.saveAndFlush(reservation()).getId();
}

private static Timestamp timestampOrNull(Instant value) {
    return value == null ? null : Timestamp.from(value);
}
```

```java
private static DataSource dataSource(MySQLContainer<?> mysql) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(mysql.getDriverClassName());
    dataSource.setUrl(mysql.getJdbcUrl());
    dataSource.setUsername(mysql.getUsername());
    dataSource.setPassword(mysql.getPassword());
    return dataSource;
}

private List<String> appliedScripts() {
    return Arrays.stream(flyway.info().applied()).map(MigrationInfo::getScript).toList();
}

private String migrationScriptName() {
    List<String> matches = appliedScripts().stream()
            .filter(script -> script.matches("V[0-9]+__create_reservation_fulfillment_audits\\.sql"))
            .toList();
    assertThat(matches).hasSize(1);
    return matches.getFirst();
}

private int latestVersion() {
    Matcher matcher = Pattern.compile("^V([0-9]+)__").matcher(migrationScriptName());
    assertThat(matcher.find()).isTrue();
    return Integer.parseInt(matcher.group(1));
}

private static String statusOf(JdbcTemplate jdbc, long reservationId) {
    return jdbc.queryForObject("SELECT status FROM reservations WHERE reservation_id = ?",
            String.class, reservationId);
}

private static int countAudits(JdbcTemplate jdbc, long reservationId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_fulfillment_audits WHERE reservation_id = ?",
            Integer.class, reservationId);
}
```

`insertV25ParentsAndCancelledReservation(MySQLContainer)`와 `reservation()`는 현재 class에 이미 존재하는 exact helpers를 직접 재사용한다. FK 검증 뒤 parent 삭제도 `assertDatabaseRejects(() -> jdbcTemplate.update("DELETE FROM reservations WHERE reservation_id = ?", reservationId))`로 별도 확인한다.

- [ ] **Step 4: migration RED를 관찰한다**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest"
```

Expected: `reservation_fulfillment_audits` table이 없어 FAIL이다.

- [ ] **Step 5: 신규 migration을 작성한다**

Task 2 preflight가 live Issue에서 다시 검증한 exact 파일명에 다음 schema를 넣는다.

```sql
CREATE TABLE reservation_fulfillment_audits (
    reservation_fulfillment_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    reservation_time_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_fulfillment_audit_id),
    CONSTRAINT uk_reservation_fulfillment_audits_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_fulfillment_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_fulfillment_audits_actor_type
        CHECK (actor_type = 'STORE_OPERATOR'),
    CONSTRAINT ck_reservation_fulfillment_audits_actor_id CHECK (actor_id > 0),
    CONSTRAINT ck_reservation_fulfillment_audits_time CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_fulfillment_audits_transition
        CHECK (before_status = 'CONFIRMED' AND after_status = 'FULFILLED'),
    CONSTRAINT ck_reservation_fulfillment_audits_versions
        CHECK (reservation_time_policy_version > 0 AND capacity_policy_version > 0),
    CONSTRAINT ck_reservation_fulfillment_audits_command_id
        CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- [ ] **Step 6: entity와 repository를 최소 구현한다**

```java
public enum ReservationFulfillmentActorType {
    STORE_OPERATOR
}
```

```java
@Entity
@Table(name = "reservation_fulfillment_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_fulfillment_audits_reservation",
                columnNames = "reservation_id"))
public class ReservationFulfillmentAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_fulfillment_audit_id")
    private Long id;
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ReservationFulfillmentActorType actorType;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", nullable = false, length = 20)
    private ReservationStatus beforeStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 20)
    private ReservationStatus afterStatus;
    @Column(name = "reservation_time_policy_version", nullable = false)
    private Long reservationTimePolicyVersion;
    @Column(name = "capacity_policy_version", nullable = false)
    private Long capacityPolicyVersion;
    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    protected ReservationFulfillmentAudit() {}

    private ReservationFulfillmentAudit(
            long reservationId, ReservationFulfillmentActorType actorType, long actorId,
            Instant requestedAt, Instant occurredAt, ReservationStatus beforeStatus,
            ReservationStatus afterStatus, long reservationTimePolicyVersion,
            long capacityPolicyVersion, String commandId
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.actorType = requireNonNull(actorType, "actorType");
        this.actorId = requirePositive(actorId, "actorId");
        this.requestedAt = requireNonNull(requestedAt, "requestedAt");
        this.occurredAt = requireNonNull(occurredAt, "occurredAt");
        if (occurredAt.isBefore(requestedAt)) throw new IllegalArgumentException("occurredAt must not be before requestedAt");
        this.beforeStatus = requireNonNull(beforeStatus, "beforeStatus");
        this.afterStatus = requireNonNull(afterStatus, "afterStatus");
        if (beforeStatus != ReservationStatus.CONFIRMED || afterStatus != ReservationStatus.FULFILLED) {
            throw new IllegalArgumentException("success audit must record CONFIRMED to FULFILLED");
        }
        this.reservationTimePolicyVersion = requirePositive(reservationTimePolicyVersion, "reservationTimePolicyVersion");
        this.capacityPolicyVersion = requirePositive(capacityPolicyVersion, "capacityPolicyVersion");
        this.commandId = requireCommandId(commandId);
    }

    public static ReservationFulfillmentAudit recordSuccess(
            long reservationId, ReservationFulfillmentActorType actorType, long actorId,
            Instant requestedAt, Instant occurredAt, ReservationStatus beforeStatus,
            ReservationStatus afterStatus, long reservationTimePolicyVersion,
            long capacityPolicyVersion, String commandId
    ) {
        return new ReservationFulfillmentAudit(reservationId, actorType, actorId,
                requestedAt, occurredAt, beforeStatus, afterStatus,
                reservationTimePolicyVersion, capacityPolicyVersion, commandId);
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static <T> T requireNonNull(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
    private static String requireCommandId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        String normalized = value.trim();
        if (normalized.isBlank() || normalized.length() > 100) {
            throw new IllegalArgumentException("commandId must be 1 to 100 characters");
        }
        return normalized;
    }

    public Long getId() { return id; }
    public Long getReservationId() { return reservationId; }
    public ReservationFulfillmentActorType getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getOccurredAt() { return occurredAt; }
    public ReservationStatus getBeforeStatus() { return beforeStatus; }
    public ReservationStatus getAfterStatus() { return afterStatus; }
    public Long getReservationTimePolicyVersion() { return reservationTimePolicyVersion; }
    public Long getCapacityPolicyVersion() { return capacityPolicyVersion; }
    public String getCommandId() { return commandId; }
}
```

```java
public interface ReservationFulfillmentAuditRepository
        extends JpaRepository<ReservationFulfillmentAudit, Long> {
    Optional<ReservationFulfillmentAudit> findByReservationId(Long reservationId);
}
```

- [ ] **Step 7: focused GREEN을 확인한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationFulfillmentAuditTest"
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest"
```

Expected: entity와 migration tests PASS. upgrade test는 직전 실제 migration version에서 신규 migration이 적용되고 기존 Reservation을 backfill하지 않음을 확인한다.

- [ ] **Step 8: 지정 경로만 commit한다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)').Groups['scope'].Value
$migrationPaths = @([regex]::Matches($scope, '(?m)^- `(backend/src/main/resources/db/migration/V\d+__create_reservation_fulfillment_audits\.sql)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if ($migrationPaths.Count -ne 1) { throw 'live Issue migration path is not unique' }
git add -- $migrationPaths[0] backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentActorType.java backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAudit.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationFulfillmentAuditTest.java backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java
git diff --cached --name-only
git commit -m "feat(reservation): 방문 완료 감사를 저장한다"
```

### Task 3: Reservation Fulfillment Transaction

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandResult.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`

**Interfaces:**
- Consumes: `StoreService.requireManagementOwnership(long, long)`, `ReservationRepository.findByIdAndStoreIdForUpdate(Long, Long)`, `MenuHoldService.lockForTermination(long)`, `MenuHoldService.fulfill(MenuHoldFulfillCommand)`, Task 2 audit repository.
- Produces: `ReservationFulfillmentCommandResult fulfillStoreReservation(long, long, long, IdempotencyCommand, Instant, String)` and private `BusinessResult<ReservationDetailResponse> fulfillReservationWork(Reservation, long, Instant, String)`.

- [ ] **Task preflight: live Issue exact scope를 현재 shell에서 다시 검증한다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if (@($livePaths | Group-Object | Where-Object Count -gt 1).Count -ne 0) { throw 'duplicate Issue path' }
if (@($livePaths | Where-Object { $_ -match 'create_reservation_fulfillment_audits\.sql$' }).Count -ne 1) { throw 'migration path is not unique' }
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
$taskPaths = @('backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandResult.java','backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java','backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java')
if (@($taskPaths | Where-Object { $_ -notin $livePaths }).Count -ne 0) { throw 'Task 3 path missing from live Issue' }
```

- [ ] **Step 1: Service RED tests를 작성한다**

현재 `ReservationServiceTest` fixture에 다음 mock·상수·constructor wiring을 먼저 추가한다. 기존 `Clock.fixed(NOW, ZoneOffset.UTC)` 인자는 `clock`으로 교체하고 모든 기존 시간 기반 test가 같은 `NOW`를 받도록 lenient 기본값을 둔다.

```java
private static final long OPERATOR_ID = 33L;
private static final long STORE_ID = 22L;
private static final long RESERVATION_ID = 77L;
private static final Instant REQUESTED_AT = NOW.minusSeconds(10);
private static final Instant OCCURRED_AT = NOW;
private static final String FULFILLMENT_KEY = "550e8400-e29b-41d4-a716-446655440000";
private static final String CORRELATION =
        "reservation-fulfill:store-operator:33:" + FULFILLMENT_KEY;

@Mock private Clock clock;
@Mock private ReservationFulfillmentAuditRepository fulfillmentAuditRepository;

@BeforeEach
void setUp() {
    lenient().when(clock.instant()).thenReturn(NOW);
    reservationService = new ReservationService(
            storeScheduleService, storeServiceIntervalValidationService,
            timePolicyRepository, storeService, idempotencyExecutor,
            timePolicyAuditRepository, new ObjectMapper(), clock,
            capacityBucketRepository, reservationRepository,
            consumerAccountService, menuHoldSnapshotQueryService,
            storeTransactionEligibilityService, capacityAllocationRepository,
            cancellationPolicySelector, menuHoldService,
            cancellationAuditRepository, cancellationPolicyEvaluator,
            fulfillmentAuditRepository);
}
```

다음 각각을 독립 test로 작성한다.

```java
@Test
void fulfillmentChecksCurrentOwnershipBeforeIdempotencyClaim() {
    willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
            .given(storeService)
            .requireManagementOwnership(OPERATOR_ID, STORE_ID);

    assertServiceError(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION),
            StoreErrorCode.ACCESS_DENIED);

    then(idempotencyExecutor).shouldHaveNoInteractions();
}
```

```java
@Test
void fulfillmentTransitionsReservationAndPresentHoldWithoutResourceRestore() {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    given(menuHoldService.fulfill(any(MenuHoldFulfillCommand.class)))
            .willReturn(MenuHoldCommandResult.fulfilled(RESERVATION_ID));

    ReservationFulfillmentCommandResult result = reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);

    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.data().status()).isEqualTo("FULFILLED");
    then(capacityBucketRepository).shouldHaveNoInteractions();
    then(capacityAllocationRepository).shouldHaveNoInteractions();
}
```

다음 named tests를 같은 RED 묶음에 실제 코드로 추가한다. `successfulOutcome()`은 저장된 200/SUCCESS/RESERVATION/detail payload이고 `confirmedReservation()`은 store ID 22의 CONFIRMED fixture다.

```java
@Test
void fulfillmentUsesOwnershipOnlyAndNeverChecksTransactionEligibility() {
    given(reservationRepository.findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID))
            .willReturn(Optional.of(confirmedReservation()));
    given(menuHoldService.lockForTermination(RESERVATION_ID))
            .willReturn(MenuHoldTerminationPresence.NO_HOLD);
    given(idempotencyExecutor.execute(eq(fulfillmentCommand()), any()))
            .willAnswer(invocation -> invocation.<Supplier<BusinessResult<?>>>getArgument(1).get());

    reservationService.fulfillStoreReservation(OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);

    then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
    then(storeService).shouldHaveNoMoreInteractions();
    then(storeTransactionEligibilityService).shouldHaveNoInteractions();
}

@Test
void replayRechecksOwnershipAndDoesNotReadOrMutateReservationHoldOrAudit() {
    given(idempotencyExecutor.execute(eq(fulfillmentCommand()), any()))
            .willReturn(successfulOutcome());

    ReservationFulfillmentCommandResult result = reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);

    then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
    then(reservationRepository).shouldHaveNoInteractions();
    then(menuHoldService).shouldHaveNoInteractions();
    then(fulfillmentAuditRepository).shouldHaveNoInteractions();
    assertThat(result.httpStatus()).isEqualTo(200);
}

@Test
void noHoldNeverCallsFulfillAndDoesNotCreateOrRestoreResources() {
    stubFreshConfirmed(MenuHoldTerminationPresence.NO_HOLD);
    reservationService.fulfillStoreReservation(OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);
    then(menuHoldService).should(never()).fulfill(any());
    then(capacityBucketRepository).shouldHaveNoInteractions();
    then(capacityAllocationRepository).shouldHaveNoInteractions();
}

@Test
void acceptsAlreadyFulfilledMenuHoldAsThePublicFulfilledOutcome() {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    given(menuHoldService.fulfill(new MenuHoldFulfillCommand(RESERVATION_ID, CORRELATION)))
            .willReturn(MenuHoldCommandResult.fulfilled(RESERVATION_ID));
    reservationService.fulfillStoreReservation(OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);
    then(fulfillmentAuditRepository).should().saveAndFlush(any());
}

@ParameterizedTest
@EnumSource(value = ReservationStatus.class, names = {"CONFIRMED"}, mode = EnumSource.Mode.EXCLUDE)
void rejectsEveryNonConfirmedStateBeforeLockingMenuHold(ReservationStatus status) {
    stubFreshIdempotency(fulfillmentCommand(), null);
    given(reservationRepository.findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID))
            .willReturn(Optional.of(reservationIn(status)));
    assertServiceError(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION),
            ReservationErrorCode.INVALID_STATE_TRANSITION);
    then(menuHoldService).shouldHaveNoInteractions();
}

@Test
void usesOneOccurredAtLocksInOrderAndNeverRehydratesAfterMenuHoldFulfill() {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    given(menuHoldService.fulfill(any())).willReturn(MenuHoldCommandResult.fulfilled(RESERVATION_ID));
    reservationService.fulfillStoreReservation(OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION);
    InOrder order = inOrder(reservationRepository, menuHoldService, fulfillmentAuditRepository);
    order.verify(reservationRepository).findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID);
    order.verify(menuHoldService).lockForTermination(RESERVATION_ID);
    order.verify(menuHoldService).fulfill(new MenuHoldFulfillCommand(RESERVATION_ID, CORRELATION));
    ArgumentCaptor<ReservationFulfillmentAudit> audit = ArgumentCaptor.forClass(ReservationFulfillmentAudit.class);
    order.verify(fulfillmentAuditRepository).saveAndFlush(audit.capture());
    assertThat(audit.getValue().getOccurredAt()).isEqualTo(OCCURRED_AT);
    then(reservationRepository).should(never()).findById(anyLong());
    then(clock).should().instant();
}

@Test
void propagatesAuditFailureSoTheTransactionCanRollBack() {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    given(menuHoldService.fulfill(any())).willReturn(MenuHoldCommandResult.fulfilled(RESERVATION_ID));
    DataIntegrityViolationException failure = new DataIntegrityViolationException("audit insert");
    given(fulfillmentAuditRepository.saveAndFlush(any())).willThrow(failure);
    assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION)).isSameAs(failure);
}

@Test
void missingOrWrongStoreReservationIsHiddenAsReservation001() {
    stubFreshIdempotency(fulfillmentCommand(), null);
    given(reservationRepository.findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID))
            .willReturn(Optional.empty());

    assertServiceError(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION),
            ReservationErrorCode.RESERVATION_NOT_FOUND);
    then(menuHoldService).shouldHaveNoInteractions();
}

@ParameterizedTest
@MethodSource("inconsistentFulfillmentHoldResults")
void rejectsNullOrInconsistentMenuHoldResult(MenuHoldCommandResult result) {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    given(menuHoldService.fulfill(new MenuHoldFulfillCommand(RESERVATION_ID, CORRELATION)))
            .willReturn(result);

    assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("menu hold fulfillment result is inconsistent");
    then(fulfillmentAuditRepository).shouldHaveNoInteractions();
}

private static Stream<MenuHoldCommandResult> inconsistentFulfillmentHoldResults() {
    return Stream.of(null, MenuHoldCommandResult.fulfilled(RESERVATION_ID + 1),
            MenuHoldCommandResult.released(RESERVATION_ID));
}

@Test
void propagatesMenuHoldServiceExceptionWithoutReservationRemapping() {
    stubFreshConfirmed(MenuHoldTerminationPresence.HOLD_PRESENT);
    ServiceException failure = new ServiceException(
            MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
    given(menuHoldService.fulfill(any())).willThrow(failure);

    assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
            OPERATOR_ID, STORE_ID, RESERVATION_ID,
            fulfillmentCommand(), REQUESTED_AT, CORRELATION)).isSameAs(failure);
    then(fulfillmentAuditRepository).shouldHaveNoInteractions();
}
```

위 tests가 사용하는 helpers는 다음처럼 현재 class의 `reservation(Long, Long)`와 기존 `stubFreshIdempotency`를 재사용해 정의한다. Store의 실제 `CLOSED` 상태 성공은 Task 7의 MySQL fixture가 별도로 증명한다.

```java
private IdempotencyCommand fulfillmentCommand() {
    return new IdempotencyCommand("store-operator", OPERATOR_ID,
            "RESERVATION_FULFILL", FULFILLMENT_KEY, "f".repeat(64));
}

private Reservation confirmedReservation() {
    return reservation(RESERVATION_ID, 11L);
}

private Reservation reservationIn(ReservationStatus status) {
    Reservation reservation = confirmedReservation();
    switch (status) {
        case CONFIRMED -> { }
        case CANCELLED -> reservation.cancel(OCCURRED_AT);
        case FULFILLED -> reservation.fulfill(OCCURRED_AT);
    }
    return reservation;
}

private void stubFreshConfirmed(MenuHoldTerminationPresence presence) {
    stubFreshIdempotency(fulfillmentCommand(), null);
    given(reservationRepository.findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID))
            .willReturn(Optional.of(confirmedReservation()));
    given(menuHoldService.lockForTermination(RESERVATION_ID)).willReturn(presence);
    given(fulfillmentAuditRepository.saveAndFlush(any(ReservationFulfillmentAudit.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    if (presence == MenuHoldTerminationPresence.HOLD_PRESENT) {
        given(menuHoldSnapshotQueryService.findByReservationId(RESERVATION_ID))
                .willReturn(List.of());
    }
}

private IdempotentOutcome successfulOutcome() {
    Reservation replayed = confirmedReservation();
    replayed.fulfill(OCCURRED_AT);
    ReservationDetailResponse data = ReservationDetailResponse.from(replayed, List.of());
    return new IdempotentOutcome(true, 200, "SUCCESS", "RESERVATION",
            String.valueOf(RESERVATION_ID), new ObjectMapper().valueToTree(data));
}

private static void assertServiceError(ThrowingCallable call, ErrorCode expected) {
    assertThatThrownBy(call).isInstanceOf(ServiceException.class)
            .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                    .isEqualTo(expected));
}
```

- [ ] **Step 2: RED를 관찰한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
```

Expected: 새 public method/result/dependency가 없어 compilation FAIL이다.

- [ ] **Step 3: result record와 constructor dependency를 추가한다**

```java
public record ReservationFulfillmentCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {
}
```

Spring `@Autowired` full constructor에 `ReservationFulfillmentAuditRepository`를 추가한다. 기존 package-private legacy constructor가 다른 테스트를 위해 남아 있다면 새 dependency를 거기서 가짜로 생성하지 않고 null로 두며, fulfillment 진입 시 명시적 dependency guard로 잘못된 사용을 실패시킨다.

- [ ] **Step 4: public transaction과 argument contract를 구현한다**

```java
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public ReservationFulfillmentCommandResult fulfillStoreReservation(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyCommand command,
        Instant requestedAt,
        String correlationId
) {
    requireFulfillmentArguments(
            operatorAccountId, storeId, reservationId,
            command, requestedAt, correlationId);
    storeService.requireManagementOwnership(operatorAccountId, storeId);
    IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
        Reservation reservation = reservationRepository
                .findByIdAndStoreIdForUpdate(reservationId, storeId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND));
        return fulfillReservationWork(
                reservation, operatorAccountId, requestedAt, correlationId);
    });
    return fulfillmentResult(outcome);
}
```

`requireFulfillmentArguments`는 다음 exact code로 양수 IDs, namespace/actor/type, requested time과 correlation을 검증한다.

```java
private static void requireFulfillmentArguments(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyCommand command,
        Instant requestedAt,
        String correlationId
) {
    String normalizedKey = command == null ? null : command.idempotencyKey();
    String expectedCorrelation = normalizedKey == null ? null
            : "reservation-fulfill:store-operator:"
                    + operatorAccountId + ":" + normalizedKey;
    if (operatorAccountId <= 0 || storeId <= 0 || reservationId <= 0
            || command == null
            || !"store-operator".equals(command.principalNamespace())
            || operatorAccountId != command.principalId()
            || !"RESERVATION_FULFILL".equals(command.commandType())
            || requestedAt == null
            || correlationId == null
            || correlationId.length() > 91
            || !correlationId.equals(expectedCorrelation)) {
        throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
}
```

- [ ] **Step 5: business work를 최소 구현한다**

```java
private BusinessResult<ReservationDetailResponse> fulfillReservationWork(
        Reservation reservation,
        long operatorAccountId,
        Instant requestedAt,
        String correlationId
) {
    if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
        throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
    }
    MenuHoldTerminationPresence presence =
            menuHoldService.lockForTermination(reservation.getId());
    if (presence == null) {
        throw new IllegalStateException("menu hold termination presence is required");
    }

    Instant occurredAt = clock.instant();
    reservation.fulfill(occurredAt);
    if (presence == MenuHoldTerminationPresence.HOLD_PRESENT) {
        MenuHoldCommandResult result = menuHoldService.fulfill(
                new MenuHoldFulfillCommand(reservation.getId(), correlationId));
        if (result == null
                || result.reservationId() != reservation.getId()
                || result.outcome() != MenuHoldCommandResult.Outcome.FULFILLED) {
            throw new IllegalStateException("menu hold fulfillment result is inconsistent");
        }
    }

    ReservationFulfillmentAudit audit = ReservationFulfillmentAudit.recordSuccess(
            reservation.getId(), ReservationFulfillmentActorType.STORE_OPERATOR,
            operatorAccountId, requestedAt, occurredAt,
            ReservationStatus.CONFIRMED, ReservationStatus.FULFILLED,
            reservation.getTimeSnapshot().getReservationTimePolicyVersion(),
            reservation.getCapacityPolicyVersion(), correlationId);
    fulfillmentAuditRepository.saveAndFlush(audit);
    List<MenuHoldItemResult> menuSnapshots =
            presence == MenuHoldTerminationPresence.HOLD_PRESENT
                    ? menuHoldSnapshotQueryService.findByReservationId(reservation.getId())
                    : List.of();
    ReservationDetailResponse response =
            ReservationDetailResponse.from(reservation, menuSnapshots);
    return new BusinessResult<>(200, "SUCCESS", "RESERVATION",
            String.valueOf(reservation.getId()), response);
}
```

`MenuHoldService.fulfill`은 persistence context를 clear하지 않으므로 `reservationRepository.findById` 재조회는 추가하지 않는다.

- [ ] **Step 6: replay deserialization을 구현한다**

`fulfillmentResult(IdempotentOutcome)`은 다음처럼 저장 payload의 offset과 공개 field만 복원한다. 현재 class의 기존 `storedOffsetDateTime(JsonNode, String)` helper를 그대로 재사용하며 `fulfilledAt`은 생성자에 넣거나 공개하지 않는다.

```java
private ReservationFulfillmentCommandResult fulfillmentResult(IdempotentOutcome outcome) {
    if (outcome == null || outcome.data() == null
            || outcome.httpStatus() != HttpStatus.OK.value()
            || !SUCCESS_RESPONSE_CODE.equals(outcome.responseCode())
            || !"RESERVATION".equals(outcome.resourceType())) {
        throw new IllegalStateException("fulfillment idempotent outcome is inconsistent");
    }
    ReservationDetailResponse deserialized = objectMapper.treeToValue(
            outcome.data(), ReservationDetailResponse.class);
    if (!outcome.resourceId().equals(deserialized.reservationId())) {
        throw new IllegalStateException("fulfillment resource id is inconsistent");
    }
    ReservationDetailResponse response = new ReservationDetailResponse(
            deserialized.reservationId(), deserialized.storeId(), deserialized.storeName(),
            deserialized.serviceDate(), deserialized.timeStatus(),
            storedOffsetDateTime(outcome.data(), "startAt"),
            storedOffsetDateTime(outcome.data(), "serviceEndAt"),
            deserialized.timeZoneId(), deserialized.party(), deserialized.status(),
            deserialized.menuSelections(),
            storedOffsetDateTime(outcome.data(), "createdAt"),
            deserialized.cancelledBy(), deserialized.cancellationReason());
    return new ReservationFulfillmentCommandResult(outcome.httpStatus(), response);
}
```

- [ ] **Step 7: GREEN을 확인한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
```

Expected: 기존 creation/cancellation tests와 신규 fulfillment tests가 모두 PASS한다.

- [ ] **Step 8: 지정 경로만 commit한다**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandResult.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java
git diff --cached --name-only
git commit -m "feat(reservation): 방문 완료 트랜잭션을 구현한다"
```

### Task 4: Fulfillment Command Facade

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java`

**Interfaces:**
- Consumes: Task 1 `ReservationFulfillmentRequest`, Task 3 `ReservationService.fulfillStoreReservation`, `IdempotencyKey`, `IdempotencyCommand`, `RequestFingerprint`.
- Produces: `ReservationFulfillmentCommandResult fulfill(long, long, long, IdempotencyKey, ReservationFulfillmentRequest)`.

- [ ] **Task preflight: live Issue scope와 migration/generated 고유성을 다시 읽는다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if (@($livePaths | Group-Object | Where-Object Count -gt 1).Count -ne 0 -or @($livePaths | Where-Object { $_ -match 'create_reservation_fulfillment_audits\.sql$' }).Count -ne 1) { throw 'Issue exact paths are invalid' }
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
foreach ($path in @('backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java','backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java')) { if ($path -notin $livePaths) { throw "Task 4 path missing: $path" } }
```

- [ ] **Step 1: command construction RED test를 작성한다**

Test fixture는 다음 literal definitions를 사용한다.

```java
private static final long OPERATOR_ID = 33L;
private static final long STORE_ID = 73L;
private static final long RESERVATION_ID = 321L;
private static final Instant REQUESTED_AT = Instant.parse("2026-08-10T00:00:00Z");
private static final IdempotencyKey KEY = IdempotencyKey.parse(
        "550e8400-e29b-41d4-a716-446655440000");
private static final ReservationFulfillmentRequest REQUEST =
        new ReservationFulfillmentRequest();
private static final ReservationFulfillmentCommandResult RESULT =
        new ReservationFulfillmentCommandResult(200, null);
private static final String CORRELATION =
        "reservation-fulfill:store-operator:33:550e8400-e29b-41d4-a716-446655440000";

@Mock private ReservationService reservationService;
private ReservationFulfillmentCommandFacade facade;

@BeforeEach
void setUp() {
    facade = new ReservationFulfillmentCommandFacade(
            reservationService, Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));
}
```

```java
@Test
void buildsExactScopedCommandAndNinetyOneCharacterCorrelation() {
    facade.fulfill(Long.MAX_VALUE, 73L, 321L, KEY,
            new ReservationFulfillmentRequest());

    ArgumentCaptor<IdempotencyCommand> command =
            ArgumentCaptor.forClass(IdempotencyCommand.class);
    ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
    then(reservationService).should().fulfillStoreReservation(
            eq(Long.MAX_VALUE), eq(73L), eq(321L), command.capture(),
            eq(REQUESTED_AT), correlation.capture());
    assertThat(command.getValue().commandType()).isEqualTo("RESERVATION_FULFILL");
    assertThat(command.getValue().requestFingerprint()).isEqualTo(
            RequestFingerprint.of(
                    "method=4:POST|"
                            + "route=81:/api/v1/store-operator/stores/{storeId}/reservations/"
                            + "{reservationId}/fulfillments|"
                            + "storeId=2:73|reservationId=3:321|"));
    assertThat(correlation.getValue())
            .isEqualTo("reservation-fulfill:store-operator:9223372036854775807:"
                    + "550e8400-e29b-41d4-a716-446655440000")
            .hasSize(91);
}
```

Route 길이 assertion은 Java에서 실제 문자열 `.length()`와 대조하고 값이 다르면 canonical 길이 prefix를 실제 길이로 고친다. body field는 추가하지 않는다.

- [ ] **Step 2: retry RED tests를 작성한다**

MySQL error 1213, 1205와 `ObjectOptimisticLockingFailureException`만 최대 3회 재시도하고, domain/validation/timeout/integrity/unrelated lock은 한 번만 호출함을 다음 named tests로 검증한다.

```java
@Test
void retriesDeadlockThenLockTimeoutWithSameCommandTimeAndCorrelation() {
    given(reservationService.fulfillStoreReservation(anyLong(), anyLong(), anyLong(),
            any(), any(), anyString()))
            .willThrow(mysqlLockFailure(1213))
            .willThrow(mysqlLockFailure(1205))
            .willReturn(RESULT);
    RecordingClock clock = new RecordingClock(REQUESTED_AT);
    List<Long> delays = new ArrayList<>();
    ReservationFulfillmentCommandFacade facade =
            newFacade(clock, attempt -> attempt == 1 ? 100L : 300L, delays::add);

    assertThat(facade.fulfill(OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, REQUEST)).isSameAs(RESULT);
    ArgumentCaptor<IdempotencyCommand> commands = ArgumentCaptor.forClass(IdempotencyCommand.class);
    ArgumentCaptor<Instant> times = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<String> correlations = ArgumentCaptor.forClass(String.class);
    then(reservationService).should(times(3)).fulfillStoreReservation(
            eq(OPERATOR_ID), eq(STORE_ID), eq(RESERVATION_ID),
            commands.capture(), times.capture(), correlations.capture());
    assertThat(commands.getAllValues()).allSatisfy(value -> assertThat(value).isSameAs(commands.getAllValues().get(0)));
    assertThat(times.getAllValues()).allSatisfy(value -> assertThat(value).isSameAs(REQUESTED_AT));
    assertThat(correlations.getAllValues()).containsOnly(CORRELATION);
    String firstCorrelation = correlations.getAllValues().getFirst();
    assertThat(correlations.getAllValues()).allSatisfy(
            value -> assertThat(value).isSameAs(firstCorrelation));
    assertThat(clock.instantCalls()).isEqualTo(1);
    assertThat(delays).containsExactly(100L, 300L);
}

@ParameterizedTest
@MethodSource("nonRetryableFailures")
void doesNotRetryDomainValidationTimeoutIntegrityOrUnrelatedLock(RuntimeException failure) {
    given(reservationService.fulfillStoreReservation(anyLong(), anyLong(), anyLong(),
            any(), any(), anyString())).willThrow(failure);
    List<Long> delays = new ArrayList<>();
    ReservationFulfillmentCommandFacade facade =
            newFacade(new RecordingClock(REQUESTED_AT), ignored -> 100L, delays::add);
    assertThatThrownBy(() -> facade.fulfill(
            OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, REQUEST)).isSameAs(failure);
    then(reservationService).should().fulfillStoreReservation(
            anyLong(), anyLong(), anyLong(), any(), any(), anyString());
    assertThat(delays).isEmpty();
}

private static Stream<RuntimeException> nonRetryableFailures() {
    return Stream.of(new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION),
            new IllegalArgumentException("invalid"), new QueryTimeoutException("timeout"),
            new DataIntegrityViolationException("constraint", new SQLException("duplicate", "23000", 1062)),
            new CannotAcquireLockException("unrelated", new SQLException("unrelated", "HY000", 1062)));
}

private static CannotAcquireLockException mysqlLockFailure(int code) {
    return new CannotAcquireLockException("lock", new SQLException("mysql lock", "40001", code));
}

@Test
void retriesOptimisticConflictAndReturnsSecondAttempt() {
    ObjectOptimisticLockingFailureException first =
            new ObjectOptimisticLockingFailureException(
                    Reservation.class, RESERVATION_ID);
    given(reservationService.fulfillStoreReservation(anyLong(), anyLong(), anyLong(),
            any(), any(), anyString())).willThrow(first).willReturn(RESULT);
    List<Long> delays = new ArrayList<>();
    ReservationFulfillmentCommandFacade facade =
            newFacade(new RecordingClock(REQUESTED_AT), ignored -> 100L, delays::add);

    assertThat(facade.fulfill(
            OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, REQUEST)).isSameAs(RESULT);
    then(reservationService).should(times(2)).fulfillStoreReservation(
            anyLong(), anyLong(), anyLong(), any(), any(), anyString());
    assertThat(delays).containsExactly(100L);
}

@Test
void thirdRetryableFailureBecomesCommon008AndPreservesCause() {
    CannotAcquireLockException failure = mysqlLockFailure(1213);
    given(reservationService.fulfillStoreReservation(anyLong(), anyLong(), anyLong(),
            any(), any(), anyString())).willThrow(failure, failure, failure);
    ReservationFulfillmentCommandFacade facade =
            newFacade(new RecordingClock(REQUESTED_AT), ignored -> 100L, ignored -> {});

    assertThatThrownBy(() -> facade.fulfill(
            OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, REQUEST))
            .isInstanceOf(ServiceException.class)
            .satisfies(error -> {
                ServiceException conflict = (ServiceException) error;
                assertThat(conflict.getErrorCode())
                        .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION);
                assertThat(conflict.getCause()).isSameAs(failure);
            });
    then(reservationService).should(times(3)).fulfillStoreReservation(
            anyLong(), anyLong(), anyLong(), any(), any(), anyString());
}

@Test
void interruptedRetryRestoresFlagAndBecomesCommon008() {
    CannotAcquireLockException failure = mysqlLockFailure(1205);
    given(reservationService.fulfillStoreReservation(anyLong(), anyLong(), anyLong(),
            any(), any(), anyString())).willThrow(failure);
    ReservationFulfillmentCommandFacade facade = newFacade(
            new RecordingClock(REQUESTED_AT), ignored -> 100L,
            ignored -> { throw new InterruptedException("interrupted"); });
    Thread.interrupted();
    try {
        assertThatThrownBy(() -> facade.fulfill(
                OPERATOR_ID, STORE_ID, RESERVATION_ID, KEY, REQUEST))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(CommonErrorCode.CONCURRENT_MODIFICATION));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
        Thread.interrupted();
    }
}

@Test
void productionRetryDelayStaysInsideApprovedRanges() {
    for (int sample = 0; sample < 200; sample++) {
        assertThat(ReservationFulfillmentCommandFacade.defaultDelayMillis(1))
                .isBetween(100L, 200L);
        assertThat(ReservationFulfillmentCommandFacade.defaultDelayMillis(2))
                .isBetween(300L, 500L);
    }
}

private ReservationFulfillmentCommandFacade newFacade(
        Clock clock,
        IntToLongFunction retryDelayMillis,
        ReservationFulfillmentCommandFacade.RetrySleeper retrySleeper
) {
    return new ReservationFulfillmentCommandFacade(
            reservationService, clock, retryDelayMillis, retrySleeper);
}

private static final class RecordingClock extends Clock {
    private final Instant instant;
    private final AtomicInteger calls = new AtomicInteger();

    private RecordingClock(Instant instant) { this.instant = instant; }
    int instantCalls() { return calls.get(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
        return this;
    }
    @Override public Instant instant() {
        calls.incrementAndGet();
        return instant;
    }
}
```

- [ ] **Step 3: RED를 관찰한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentCommandFacadeTest"
```

Expected: facade가 없어 compilation FAIL이다.

- [ ] **Step 4: facade를 최소 구현한다**

```java
public ReservationFulfillmentCommandResult fulfill(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyKey key,
        ReservationFulfillmentRequest request
) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(request, "request must not be null");
    Instant requestedAt = clock.instant();
    String normalizedKey = key.value();
    IdempotencyCommand command = new IdempotencyCommand(
            "store-operator", operatorAccountId, "RESERVATION_FULFILL",
            normalizedKey, RequestFingerprint.of(
                    fulfillmentCanonical(storeId, reservationId)));
    String correlationId = "reservation-fulfill:store-operator:"
            + operatorAccountId + ":" + normalizedKey;
    return executeWithRetry(() -> reservationService.fulfillStoreReservation(
            operatorAccountId, storeId, reservationId,
            command, requestedAt, correlationId));
}
```

Canonical builder와 retry implementation은 fulfillment facade 안에 다음 exact code로 둔다. cancellation facade를 범용 종결 facade로 리팩터링하지 않는다.

```java
private static String fulfillmentCanonical(long storeId, long reservationId) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "method", "POST");
    appendCanonical(canonical, "route",
            "/api/v1/store-operator/stores/{storeId}/reservations/"
                    + "{reservationId}/fulfillments");
    appendCanonical(canonical, "storeId", String.valueOf(storeId));
    appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
    return canonical.toString();
}

private static void appendCanonical(StringBuilder target, String field, String value) {
    target.append(field).append('=').append(value.length()).append(':')
            .append(value).append('|');
}

private <T> T executeWithRetry(Supplier<T> command) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
            return command.get();
        } catch (RuntimeException exception) {
            if (!isRetryableTechnicalFailure(exception)) throw exception;
            if (attempt == MAX_ATTEMPTS) throw concurrentModification(exception);
            sleepBeforeRetry(attempt, exception);
        }
    }
    throw new IllegalStateException("unreachable retry state");
}

private void sleepBeforeRetry(int failedAttempt, RuntimeException failure) {
    try {
        retrySleeper.sleep(retryDelayMillis.applyAsLong(failedAttempt));
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        exception.addSuppressed(failure);
        throw concurrentModification(exception);
    }
}

static long defaultDelayMillis(int failedAttempt) {
    return switch (failedAttempt) {
        case 1 -> ThreadLocalRandom.current().nextLong(100L, 201L);
        case 2 -> ThreadLocalRandom.current().nextLong(300L, 501L);
        default -> throw new IllegalArgumentException("unsupported retry attempt");
    };
}

private static boolean isRetryableTechnicalFailure(RuntimeException failure) {
    if (failure instanceof ServiceException
            || failure instanceof IllegalArgumentException
            || failure instanceof QueryTimeoutException
            || failure instanceof DataIntegrityViolationException) return false;
    if (failure instanceof ObjectOptimisticLockingFailureException) return true;
    return failure instanceof CannotAcquireLockException
            && containsRetryableMysqlLockFailure(failure);
}

private static boolean containsRetryableMysqlLockFailure(Throwable failure) {
    Throwable current = failure;
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    while (current != null && visited.add(current)) {
        if (current instanceof SQLException sqlException
                && (sqlException.getErrorCode() == 1213
                || sqlException.getErrorCode() == 1205)) return true;
        current = current.getCause();
    }
    return false;
}

private static ServiceException concurrentModification(Throwable cause) {
    ServiceException conflict = new ServiceException(
            CommonErrorCode.CONCURRENT_MODIFICATION);
    conflict.initCause(cause);
    return conflict;
}
```

Facade fields와 constructors는 다음 exact seam을 사용한다.

```java
private static final int MAX_ATTEMPTS = 3;
private final ReservationService reservationService;
private final Clock clock;
private final IntToLongFunction retryDelayMillis;
private final RetrySleeper retrySleeper;

@FunctionalInterface
interface RetrySleeper { void sleep(long millis) throws InterruptedException; }

@Autowired
public ReservationFulfillmentCommandFacade(
        ReservationService reservationService, Clock clock
) {
    this(reservationService, clock,
            ReservationFulfillmentCommandFacade::defaultDelayMillis, Thread::sleep);
}

ReservationFulfillmentCommandFacade(
        ReservationService reservationService,
        Clock clock,
        IntToLongFunction retryDelayMillis,
        RetrySleeper retrySleeper
) {
    this.reservationService = Objects.requireNonNull(reservationService);
    this.clock = Objects.requireNonNull(clock);
    this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis);
    this.retrySleeper = Objects.requireNonNull(retrySleeper);
}
```

- [ ] **Step 5: GREEN을 확인한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentCommandFacadeTest"
```

Expected: fingerprint/correlation/time/retry/non-retry/exhaustion/interrupt tests PASS.

- [ ] **Step 6: 지정 경로만 commit한다**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java
git diff --cached --name-only
git commit -m "feat(reservation): 방문 완료 명령을 구성한다"
```

### Task 5: Fulfillment Controller and Security

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java`

**Interfaces:**
- Consumes: Task 1 request, Task 4 facade, `AuthenticatedPrincipal`, `IdempotencyKey.parse(String)`, `ApiResponse.success`.
- Produces: authenticated `POST /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/fulfillments` with HTTP status from command result.

- [ ] **Task preflight: live Issue exact scope를 다시 읽고 controller 경로를 대조한다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if (@($livePaths | Group-Object | Where-Object Count -gt 1).Count -ne 0 -or @($livePaths | Where-Object { $_ -match 'create_reservation_fulfillment_audits\.sql$' }).Count -ne 1) { throw 'Issue exact paths are invalid' }
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
foreach ($path in @('backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java','backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java','backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java')) { if ($path -notin $livePaths) { throw "Task 5 path missing: $path" } }
```

- [ ] **Step 1: success·replay RED tests를 작성한다**

현재 controller test fixture에 facade bean과 정확한 IDs/URL/helper를 추가한다.

```java
private static final long OPERATOR_ID = 33L;
private static final long STORE_ID = 22L;
private static final long RESERVATION_ID = 77L;
private static final String FULFILLMENT_URL = DETAIL_URL + "/fulfillments";

@MockitoBean
private ReservationFulfillmentCommandFacade fulfillmentFacade;

private ReservationDetailResponse fulfilledDetailResponse() {
    ReservationDetailResponse detail = detailResponse();
    return new ReservationDetailResponse(
            detail.reservationId(), detail.storeId(), detail.storeName(),
            detail.serviceDate(), detail.timeStatus(), detail.startAt(),
            detail.serviceEndAt(), detail.timeZoneId(), detail.party(),
            "FULFILLED", detail.menuSelections(), detail.createdAt());
}
```

```java
@Test
void fulfillsStoreReservationWithPrincipalKeyAndStrictEmptyBody() throws Exception {
    authenticateStoreOperator(33L);
    given(fulfillmentFacade.fulfill(eq(33L), eq(22L), eq(77L),
            any(IdempotencyKey.class), any(ReservationFulfillmentRequest.class)))
            .willReturn(new ReservationFulfillmentCommandResult(
                    200, fulfilledDetailResponse()));

    mockMvc.perform(post(FULFILLMENT_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.status").value("FULFILLED"))
            .andExpect(jsonPath("$.data.fulfilledAt").doesNotExist());
}
```

Replay 경계는 다음 독립 test로 고정한다.

```java
@Test
void replaysStoredHttp200AndPayloadWithoutRewritingFacadeResult() throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    ReservationDetailResponse stored = fulfilledDetailResponse();
    given(fulfillmentFacade.fulfill(eq(OPERATOR_ID), eq(STORE_ID), eq(RESERVATION_ID),
            any(IdempotencyKey.class), any(ReservationFulfillmentRequest.class)))
            .willReturn(new ReservationFulfillmentCommandResult(200, stored));

    mockMvc.perform(post(FULFILLMENT_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.reservationId").value(stored.reservationId()))
            .andExpect(jsonPath("$.data.status").value("FULFILLED"));
    then(fulfillmentFacade).should().fulfill(eq(OPERATOR_ID), eq(STORE_ID),
            eq(RESERVATION_ID), any(IdempotencyKey.class),
            any(ReservationFulfillmentRequest.class));
}
```

- [ ] **Step 2: validation·auth·deny RED tests를 작성한다**

다음을 이름 있는 독립 tests와 실제 request assertions로 추가한다.

- key 누락 `COMMON_003`, malformed key `COMMON_004`, facade 미호출
- body 누락, malformed JSON, `{"unexpected":true}` 모두 `COMMON_002`, facade 미호출
- `storeId` 또는 `reservationId` 0·음수는 `COMMON_001`, facade 미호출
- 무인증 `AUTH_001`, consumer token `AUTH_004`
- 대표 Store·Reservation·MenuHold `ServiceException` code/status 원형 전달
- 기존 `returnsStoreReservationDetailEnvelope`, `cancelsStoreReservationWithAuthenticatedPrincipalAndIdempotencyKey`, `deniesUnknownPostAndNonApprovedMethodInStoreReservationFamily` tests는 그대로 유지해 GET detail·cancellation POST 허용과 unknown subpath/non-approved method deny를 회귀 검증

```java
@ParameterizedTest
@ValueSource(strings = {"0", "-1"})
void rejectsNonPositiveStoreIdBeforeFacade(String storeId) throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    mockMvc.perform(post("/api/v1/store-operator/stores/{storeId}/reservations/77/fulfillments", storeId)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_001"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}

@ParameterizedTest
@ValueSource(strings = {"0", "-1"})
void rejectsNonPositiveReservationIdBeforeFacade(String reservationId) throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    mockMvc.perform(post("/api/v1/store-operator/stores/22/reservations/{reservationId}/fulfillments", reservationId)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_001"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}

@ParameterizedTest
@NullAndEmptySource
@ValueSource(strings = {"{", "[]", "{\"unexpected\":true}"})
void rejectsMissingMalformedAndUnknownBodyAsCommon002(String body) throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    MockHttpServletRequestBuilder request = post(FULFILLMENT_URL)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) request.content(body);
    mockMvc.perform(request).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_002"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}


@Test
void rejectsMissingIdempotencyKeyAsCommon003BeforeFacade() throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    mockMvc.perform(post(FULFILLMENT_URL)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_003"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}

@Test
void rejectsMalformedIdempotencyKeyAsCommon004BeforeFacade() throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    mockMvc.perform(post(FULFILLMENT_URL).header("Idempotency-Key", "not-a-uuid")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_004"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}

@Test
void unauthenticatedFulfillmentReturnsAuth001() throws Exception {
    mockMvc.perform(post(FULFILLMENT_URL).header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_001"));
}

@Test
void consumerTokenCannotEnterStoreOperatorChain() throws Exception {
    mockMvc.perform(post(FULFILLMENT_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_004"));
    then(fulfillmentFacade).shouldHaveNoInteractions();
}

@Test
void passesThroughReservationConflictWithoutControllerRemapping() throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    given(fulfillmentFacade.fulfill(anyLong(), anyLong(), anyLong(), any(), any()))
            .willThrow(new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION));
    mockMvc.perform(post(FULFILLMENT_URL).header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_005"));
}
```

나머지 cross-domain 오류는 다음 parameterized test로 code/status 원형과 facade 1회 호출을 고정한다.

```java
@ParameterizedTest
@MethodSource("fulfillmentPassthroughErrors")
void passesThroughStoreReservationAndMenuHoldErrors(
        ErrorCode errorCode, int expectedStatus
) throws Exception {
    authenticateStoreOperator(OPERATOR_ID);
    reset(fulfillmentFacade);
    given(fulfillmentFacade.fulfill(anyLong(), anyLong(), anyLong(), any(), any()))
            .willThrow(new ServiceException(errorCode));

    mockMvc.perform(post(FULFILLMENT_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    then(fulfillmentFacade).should().fulfill(
            anyLong(), anyLong(), anyLong(), any(), any());
}

private static Stream<Arguments> fulfillmentPassthroughErrors() {
    return Stream.of(
            Arguments.of(StoreErrorCode.ACCESS_DENIED, 403),
            Arguments.of(ReservationErrorCode.RESERVATION_NOT_FOUND, 404),
            Arguments.of(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT, 409));
}
```

- [ ] **Step 3: RED를 관찰한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest"
```

Expected: fulfillment bean/method/security matcher가 없어 compilation 또는 HTTP authorization FAIL이다.

- [ ] **Step 4: controller endpoint를 구현한다**

```java
@PostMapping("/{reservationId}/fulfillments")
public ResponseEntity<ApiResponse<ReservationDetailResponse>> fulfillReservation(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable @Positive long storeId,
        @PathVariable @Positive long reservationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody ReservationFulfillmentRequest request
) {
    ReservationFulfillmentCommandResult result =
            reservationFulfillmentCommandFacade.fulfill(
                    principal.accountId(), storeId, reservationId,
                    IdempotencyKey.parse(rawKey), request);
    return ResponseEntity.status(result.httpStatus())
            .body(ApiResponse.success("예약 방문이 완료되었습니다.", result.data()));
}
```

Facade를 constructor field로 추가한다. `ServiceException`을 catch하거나 재매핑하지 않는다.

- [ ] **Step 5: exact security matcher를 추가한다**

```java
private static final String STORE_RESERVATION_FULFILLMENT =
        STORE_RESERVATION_ROOT + "/*/fulfillments";
```

```java
.requestMatchers(HttpMethod.POST, STORE_RESERVATION_CANCELLATION).authenticated()
.requestMatchers(HttpMethod.POST, STORE_RESERVATION_FULFILLMENT).authenticated()
.anyRequest().denyAll()
```

Store-operator namespace chain만 수정한다. consumer chain과 다른 method/subpath deny를 유지한다.

- [ ] **Step 6: GREEN을 확인한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest"
```

Expected: 신규 tests와 기존 list/detail/cancellation/security tests 모두 PASS.

- [ ] **Step 7: 지정 경로만 commit한다**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java
git diff --cached --name-only
git commit -m "feat(reservation): 방문 완료 API를 연다"
```

### Task 6: Production Dependency Boundary

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java`

**Interfaces:**
- Consumes: compiled Reservation production source paths.
- Produces: source-level guard that permits other domains' public service/DTO/error contracts and rejects internal Entity/Repository imports.

- [ ] **Task preflight: live Issue exact scope와 migration/generated 고유성을 다시 읽는다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if (@($livePaths | Group-Object | Where-Object Count -gt 1).Count -ne 0 -or @($livePaths | Where-Object { $_ -match 'create_reservation_fulfillment_audits\.sql$' }).Count -ne 1) { throw 'Issue exact paths are invalid' }
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
if ('backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java' -notin $livePaths) { throw 'Task 6 path missing' }
```

- [ ] **Step 1: dependency boundary test를 작성한다**

```java
@Test
void reservationProductionDoesNotImportOtherDomainEntitiesOrRepositories()
        throws IOException {
    Path production = Path.of("src", "main", "java", "com", "miriyum", "domain", "reservation");
    try (Stream<Path> files = Files.walk(production)) {
        List<String> forbiddenImports = files
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(ReservationProductionDependencyTest::linesUnchecked)
                .filter(line -> line.startsWith("import com.miriyum.domain."))
                .filter(line -> !line.startsWith("import com.miriyum.domain.reservation."))
                .filter(line -> line.contains(".entity.") || line.contains(".repository."))
                .toList();
        assertThat(forbiddenImports).isEmpty();
    }
}
```

```java
private static Stream<String> linesUnchecked(Path path) {
    try {
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream();
    } catch (IOException exception) {
        throw new UncheckedIOException("failed to read " + path, exception);
    }
}
```

`Files.readAllLines`가 반환한 list-backed stream은 별도 file handle을 유지하지 않는다. 이 test는 기존 source의 baseline을 먼저 관찰하는 guard verification이며 production behavior RED가 아니다. 신규 fulfillment가 타 도메인 내부 import를 추가하지 못하게 하는 회귀 경계다.

- [ ] **Step 2: 현재 source에 대해 test를 실행한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.ReservationProductionDependencyTest"
```

Expected: PASS. FAIL이면 신규 fulfillment import만이 아니라 기존 Reservation production의 실제 위반을 확인한다. #52와 무관한 기존 위반이면 범위를 임의 확장하지 않고 Issue에 기록해 `BLOCKED` 여부를 판단한다.

- [ ] **Step 3: 공개 계약 사용을 reflection으로 고정한다**

```java
@Test
void fulfillmentConsumesOnlyApprovedStoreAndMenuHoldServiceSignatures()
        throws NoSuchMethodException {
    assertThat(StoreService.class.getMethod(
            "requireManagementOwnership", long.class, long.class).getReturnType())
            .isEqualTo(void.class);
    assertThat(MenuHoldService.class.getMethod(
            "lockForTermination", long.class).getReturnType())
            .isEqualTo(MenuHoldTerminationPresence.class);
    assertThat(MenuHoldService.class.getMethod(
            "fulfill", MenuHoldFulfillCommand.class).getReturnType())
            .isEqualTo(MenuHoldCommandResult.class);
}
```

- [ ] **Step 4: GREEN을 다시 확인한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.ReservationProductionDependencyTest"
```

Expected: public dependency signature와 forbidden import scan PASS.

- [ ] **Step 5: test만 commit한다**

```powershell
git add backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java
git diff --cached --name-only
git commit -m "test(reservation): 방문 완료 의존 경계를 고정한다"
```

### Task 7: MySQL Atomicity, Concurrency, and Rollback

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java`

**Interfaces:**
- Consumes: public `ReservationFulfillmentCommandFacade.fulfill`, MySQL schema, existing cancellation public facade for terminal race.
- Produces: committed database evidence for exactly-once fulfillment, immutable resource ledgers, deterministic terminal winner, and rollback/retry.

이 task의 integration tests는 Tasks 2–5에서 이미 RED→GREEN으로 구현한 behavior를 실제 MySQL transaction에서 검증하는 PASS verification이다. 여기서 test가 처음부터 실패해야 한다고 가정하거나 가짜 실패를 만들지 않는다.

- [ ] **Task preflight: live Issue exact scope와 migration/generated 고유성을 다시 읽는다**

```powershell
$issueRaw = gh issue view 52 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json body
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: Issue #52 query failed' }
try { $liveBody = ($issueRaw | ConvertFrom-Json -ErrorAction Stop).body } catch { throw 'BLOCKED: Issue #52 returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($liveBody)) { throw 'BLOCKED: Issue #52 body is empty' }
$scope = [regex]::Match($liveBody, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $liveBody -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'Issue #52 is not ready' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
if (@($livePaths | Group-Object | Where-Object Count -gt 1).Count -ne 0 -or @($livePaths | Where-Object { $_ -match 'create_reservation_fulfillment_audits\.sql$' }).Count -ne 1) { throw 'Issue exact paths are invalid' }
$generatedPaths = @($livePaths | Where-Object { $_ -match '^frontend/src/shared/api/generated/.+\.ts$' })
if ('backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java' -notin $livePaths) { throw 'Task 7 path missing' }
```

- [ ] **Step 1: 실제 MySQL fixture와 불변 자원 snapshot helper를 작성한다**

```java
@Tag("integration")
@Tag("integration-shard-b")
@Testcontainers
@SpringBootTest(
        classes = MiriyumApplication.class,
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
            "miriyum.store.schedule.activation-enabled=false",
            "miriyum.reservation.time-policy.activation-enabled=false"
        }
)
class ReservationFulfillmentIT {
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.40");
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired ReservationFulfillmentCommandFacade fulfillmentFacade;
    @Autowired ReservationCancellationCommandFacade cancellationFacade;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactions;
    @Autowired StoreOperatorAccountRepository operatorRepository;
    @Autowired ConsumerAccountRepository consumerRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationCapacityBucketRepository capacityBucketRepository;
    @Autowired ReservationCapacityAllocationRepository allocationRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuInventoryBucketRepository inventoryBucketRepository;

    @AfterEach
    void dropFailureTriggers() {
        for (FailurePoint point : FailurePoint.values()) dropFailureTrigger(point);
    }
}
```

`confirmedScenario(boolean withHold)`는 다음처럼 실제 parent entities를 저장하고 `withHold`일 때만 CONFIRMED MenuHold와 inventory/ledger를 만든다. fixture에서 CLOSED를 검증할 때는 `Store`의 기존 공개 상태 전이 메서드로 저장 직전에 CLOSED로 바꾸고 service가 상태를 읽지 않아도 성공하는 별도 case를 둔다.

```java
private Scenario confirmedScenario(boolean withHold) {
    return confirmedScenario(withHold, false);
}

private Scenario confirmedScenario(boolean withHold, boolean closedStore) {
    return transactions.execute(status -> {
        int sequence = WORKER_SEQUENCE.incrementAndGet();
        StoreOperatorAccount operator = operatorRepository.saveAndFlush(
                StoreOperatorAccount.create("fulfill-owner-" + sequence + "@example.com",
                        "hashed-password", "owner"));
        Store store = Store.create(
                operator.getId(), Long.toString(9_000_000_000L + sequence),
                BusinessType.CAFE, "Fulfillment Store " + sequence, "", Region.SEOUL,
                "fixture-address", "CAFE_BAKERY", Set.of(), true, true, false,
                "Asia/Seoul", LocalDateTime.of(2026, 8, 1, 9, 0),
                "STORE_ONBOARDING_REQUIRED_TERMS_V1");
        if (closedStore) store.close();
        store = storeRepository.saveAndFlush(store);
        ConsumerAccount consumer = consumerRepository.saveAndFlush(
                ConsumerAccount.createWithContact(
                        "fulfill-consumer-" + sequence + "@example.com", "hashed-password",
                        "consumer", String.format(Locale.ROOT, "010%08d", sequence),
                        "opaque-fulfill-contact-" + sequence));
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                store.getId(), 1L, 60, 60, 0);
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "fulfillment fixture");
        ReservationTimeSnapshot time = ReservationTimeSnapshot.calculate(policy,
                LocalDateTime.of(LocalDate.of(2026, 8, 15), LocalTime.of(12, 0)),
                ZoneId.of("Asia/Seoul"), null);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.confirm(
                consumer.getId(), store.getId(), store.getName(), time,
                PartyComposition.of(2, 0, 0),
                ReservationContactSnapshot.contactable("opaque-target-" + sequence),
                1L, new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-10T00:00:00Z")));
        ReservationCapacityBucket bucket = capacityBucketRepository.saveAndFlush(
                ReservationCapacityBucket.create(store.getId(), LocalDate.of(2026, 8, 15),
                        LocalTime.of(12, 0), LocalTime.of(13, 0), 10, 5,
                        2, 1, 1, 10, true, 1L));
        allocationRepository.saveAndFlush(ReservationCapacityAllocation.allocate(
                reservation.getId(), bucket.getId(), 2, 1L));
        Long holdId = withHold
                ? seedConfirmedMenuHold(operator.getId(), store.getId(), consumer.getId(),
                        reservation.getId(), sequence)
                : null;
        return new Scenario(operator.getId(), store.getId(), reservation.getId(), holdId);
    });
}

private long seedConfirmedMenuHold(
        long operatorId, long storeId, long consumerId, long reservationId, int sequence
) {
    Menu menu = Menu.create(storeId, menuContent(), operatorId,
            Instant.parse("2026-08-01T00:00:00Z"));
    menu.publish(Instant.parse("2026-08-01T00:00:00Z"));
    menu = menuRepository.saveAndFlush(menu);
    MenuInventoryBucket inventory = inventoryBucketRepository.saveAndFlush(
            MenuInventoryBucket.create(menu.getId(), LocalDate.of(2026, 8, 15),
                    LocalTime.of(12, 0), LocalDate.of(2026, 8, 15), LocalTime.of(13, 0),
                    "Asia/Seoul", 1L, 5, 5, 0, 0, false));
    jdbcTemplate.update("UPDATE menu_inventory_buckets SET online_hold_remaining = 4 WHERE menu_inventory_bucket_id = ?", inventory.getId());
    String acquireOperation = "reservation-create:fulfillment-it:" + sequence;
    jdbcTemplate.update("""
            INSERT INTO menu_inventory_ledger (
              operation_id, source_operation_id, menu_inventory_bucket_id,
              operation_type, pool_type, quantity_delta, quantity_before,
              quantity_after, created_at, updated_at
            ) VALUES (?, NULL, ?, 'ACQUIRE', 'ONLINE_HOLD', -1, 5, 4,
                      CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, acquireOperation, inventory.getId());
    jdbcTemplate.update("""
            INSERT INTO menu_holds (
              reservation_id, store_id, consumer_account_id, service_date, start_time,
              end_date, end_time, acquire_operation_id, status, created_at, updated_at
            ) VALUES (?, ?, ?, '2026-08-15', '12:00:00', '2026-08-15', '13:00:00',
                      ?, 'CONFIRMED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, reservationId, storeId, consumerId, acquireOperation);
    long holdId = jdbcTemplate.queryForObject(
            "SELECT menu_hold_id FROM menu_holds WHERE reservation_id = ?",
            Long.class, reservationId);
    jdbcTemplate.update("""
            INSERT INTO menu_hold_items (
              menu_hold_id, menu_id, menu_inventory_bucket_id, menu_policy_version,
              menu_name_snapshot, unit_price_snapshot, inventory_policy_version,
              quantity, created_at, updated_at
            ) VALUES (?, ?, ?, 1, 'Fulfillment Americano', 5000, 1, 1,
                      CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, holdId, menu.getId(), inventory.getId());
    return holdId;
}
```

```java
private static MenuContent menuContent() {
    return new MenuContent(
            "Fulfillment Americano", "", 5_000, false, "BEVERAGE",
            List.of(), List.of(), true, false,
            DisclosureRegistrationStatus.REGISTERED,
            List.of(new AllergenDisclosure(
                    AllergenIngredientCode.MILK,
                    AllergenDisclosureStatus.CONTAINS)),
            DisclosureRegistrationStatus.NOT_APPLICABLE,
            List.of(), false);
}

private static IdempotencyKey key(int suffix) {
    return IdempotencyKey.parse(String.format(
            Locale.ROOT, "550e8400-e29b-41d4-a716-%012d", suffix));
}
```

반환형과 snapshot은 다음으로 고정한다.

```java
private record Scenario(long operatorId, long storeId, long reservationId, Long menuHoldId) {}
private record ResourceSnapshot(
        Map<String, Object> reservation,
        List<Map<String, Object>> holds,
        List<Map<String, Object>> capacities,
        List<Map<String, Object>> allocations,
        List<Map<String, Object>> inventory,
        List<Map<String, Object>> ledger,
        List<Map<String, Object>> audits,
        List<Map<String, Object>> cancellationAudits,
        List<Map<String, Object>> idempotency) {}

private ResourceSnapshot snapshot(Scenario scenario) {
    return new ResourceSnapshot(
            jdbcTemplate.queryForMap("SELECT status, fulfilled_at FROM reservations WHERE reservation_id = ?", scenario.reservationId()),
            jdbcTemplate.queryForList("SELECT menu_hold_id, status FROM menu_holds WHERE reservation_id = ? ORDER BY menu_hold_id", scenario.reservationId()),
            jdbcTemplate.queryForList("SELECT reservation_capacity_bucket_id, occupied_people, occupied_teams FROM reservation_capacity_buckets WHERE store_id = ? ORDER BY reservation_capacity_bucket_id", scenario.storeId()),
            jdbcTemplate.queryForList("SELECT reservation_capacity_allocation_id, reservation_capacity_bucket_id, occupied_people, occupied_teams, capacity_policy_version FROM reservation_capacity_allocations WHERE reservation_id = ? ORDER BY reservation_capacity_bucket_id", scenario.reservationId()),
            jdbcTemplate.queryForList("SELECT menu_inventory_bucket_id, online_hold_remaining, shared_remaining, lock_version FROM menu_inventory_buckets ORDER BY menu_inventory_bucket_id"),
            jdbcTemplate.queryForList("SELECT menu_inventory_ledger_id, operation_id, operation_type, quantity_delta, quantity_before, quantity_after FROM menu_inventory_ledger ORDER BY menu_inventory_ledger_id"),
            jdbcTemplate.queryForList("SELECT actor_type, actor_id, requested_at, occurred_at, before_status, after_status, reservation_time_policy_version, capacity_policy_version, command_id FROM reservation_fulfillment_audits WHERE reservation_id = ?", scenario.reservationId()),
            jdbcTemplate.queryForList("SELECT actor_type, actor_id, cancellation_reason, requested_at, occurred_at, before_status, after_status, cancellation_policy_version, capacity_policy_version, command_id FROM reservation_cancellation_audits WHERE reservation_id = ?", scenario.reservationId()),
            jdbcTemplate.queryForList("SELECT idempotency_key, processing_status, result_http_status, result_response_code, result_payload FROM idempotency_commands WHERE principal_namespace = 'store-operator' AND principal_id = ? AND command_type = 'RESERVATION_FULFILL' ORDER BY idempotency_command_id", scenario.operatorId()));
}
```

- [ ] **Step 2: HOLD_PRESENT와 NO_HOLD commit verification을 작성한다**

```java
@ParameterizedTest(name = "withHold={0}")
@ValueSource(booleans = {true, false})
void commitsReservationHoldAuditAndIdempotencyWithoutChangingResources(boolean withHold) {
    Scenario scenario = confirmedScenario(withHold);
    ResourceSnapshot before = snapshot(scenario);

    ReservationFulfillmentCommandResult result = fulfillmentFacade.fulfill(
            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
            key(10 + (withHold ? 1 : 0)), new ReservationFulfillmentRequest());
    ResourceSnapshot after = snapshot(scenario);

    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(after.reservation().get("status")).isEqualTo("FULFILLED");
    assertThat(after.holds()).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("FULFILLED"));
    if (!withHold) assertThat(after.holds()).isEmpty();
    assertThat(after.capacities()).isEqualTo(before.capacities());
    assertThat(after.allocations()).isEqualTo(before.allocations());
    assertThat(after.inventory()).isEqualTo(before.inventory());
    assertThat(after.ledger()).isEqualTo(before.ledger());
    assertThat(after.audits()).hasSize(1);
    assertThat(after.audits().getFirst().get("occurred_at"))
            .isEqualTo(after.reservation().get("fulfilled_at"));
    assertThat(after.idempotency()).singleElement()
            .satisfies(row -> assertThat(row.get("processing_status")).isEqualTo("SUCCEEDED"));
}

@Test
void fulfillsConfirmedReservationOwnedByClosedStore() {
    Scenario scenario = confirmedScenario(false, true);

    assertThat(jdbcTemplate.queryForObject(
            "SELECT operation_status FROM stores WHERE store_id = ?",
            String.class, scenario.storeId())).isEqualTo("CLOSED");
    assertThat(fulfillmentFacade.fulfill(
            scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
            key(12), new ReservationFulfillmentRequest()).httpStatus()).isEqualTo(200);

    ResourceSnapshot after = snapshot(scenario);
    assertThat(after.reservation().get("status")).isEqualTo("FULFILLED");
    assertThat(after.audits()).hasSize(1);
}
```

- [ ] **Step 3: 동일 key와 다른 key의 실제 lock wait verification을 작성한다**

동일 key test는 별도 holder transaction이 `idempotency_commands` UNIQUE row를 `PROCESSING`으로 insert한 뒤 두 worker를 시작한다. 다른 key test는 별도 holder가 Reservation PK를 `FOR UPDATE`로 잡는다. 둘 다 `Future.get(250ms)` timeout과 `performance_schema.data_lock_waits`를 함께 확인한다.

```java
private void awaitBlockingWaits(long holderConnectionId, String table, String index, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
         PreparedStatement statement = connection.prepareStatement("""
                 SELECT COUNT(*) FROM performance_schema.data_lock_waits wait_edge
                 JOIN performance_schema.data_locks blocking_lock
                   ON blocking_lock.ENGINE = wait_edge.ENGINE
                  AND blocking_lock.ENGINE_LOCK_ID = wait_edge.BLOCKING_ENGINE_LOCK_ID
                 JOIN information_schema.INNODB_TRX blocking_transaction
                   ON blocking_transaction.TRX_ID = blocking_lock.ENGINE_TRANSACTION_ID
                 WHERE blocking_transaction.TRX_MYSQL_THREAD_ID = ?
                   AND blocking_lock.OBJECT_SCHEMA = DATABASE()
                   AND blocking_lock.OBJECT_NAME = ? AND blocking_lock.INDEX_NAME = ?
                 """)) {
        statement.setLong(1, holderConnectionId);
        statement.setString(2, table);
        statement.setString(3, index);
        while (System.nanoTime() < deadline) {
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) >= expected) return;
            }
            Thread.onSpinWait();
        }
    } catch (SQLException exception) {
        throw new IllegalStateException("cannot observe lock waits", exception);
    }
    throw new AssertionError("expected lock waits on " + table + "." + index);
}

private static void assertFutureBlocked(Future<?> future) {
    assertThatThrownBy(() -> future.get(250, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
}
```

같은 key와 다른 key 경합은 다음 literal methods로 구현한다.

```java
@Test
void sameKeyContentionBlocksOnIdempotencyAndReturnsOneEqualStoredResult() throws Exception {
    Scenario scenario = confirmedScenario(true);
    IdempotencyKey key = key(200);
    String fingerprint = fulfillmentFingerprint(scenario.storeId(), scenario.reservationId());
    ResourceSnapshot before = snapshot(scenario);
    CountDownLatch holderLocked = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    CountDownLatch workersReady = new CountDownLatch(2);
    CountDownLatch startWorkers = new CountDownLatch(1);
    AtomicLong holderConnectionId = new AtomicLong();
    ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
    Future<Long> holder = null;
    Future<Attempt> first = null;
    Future<Attempt> second = null;
    try {
        holder = executor.submit(() -> transactions.execute(status -> {
            jdbcTemplate.update("""
                    INSERT INTO idempotency_commands (
                      principal_namespace, principal_id, command_type, idempotency_key,
                      request_fingerprint, processing_status, created_at, updated_at
                    ) VALUES ('store-operator', ?, 'RESERVATION_FULFILL', ?, ?,
                              'PROCESSING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, scenario.operatorId(), key.value(), fingerprint);
            holderConnectionId.set(jdbcTemplate.queryForObject(
                    "SELECT CONNECTION_ID()", Long.class));
            holderLocked.countDown();
            awaitLatch(releaseHolder, "idempotency holder release");
            status.setRollbackOnly();
            return holderConnectionId.get();
        }));
        assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
        first = executor.submit(() -> fulfillAfterStart(
                scenario, key, workersReady, startWorkers));
        second = executor.submit(() -> fulfillAfterStart(
                scenario, key, workersReady, startWorkers));
        assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
        startWorkers.countDown();
        assertFutureBlocked(first);
        assertFutureBlocked(second);
        awaitBlockingWaits(holderConnectionId.get(),
                "idempotency_commands", "uk_idempotency_commands", 2);
        assertThat(snapshot(scenario)).isEqualTo(before);

        releaseHolder.countDown();
        holder.get(10, TimeUnit.SECONDS);
        Attempt firstAttempt = first.get(15, TimeUnit.SECONDS);
        Attempt secondAttempt = second.get(15, TimeUnit.SECONDS);
        assertThat(firstAttempt.errorCode()).isNull();
        assertThat(secondAttempt.errorCode()).isNull();
        assertThat(firstAttempt.result()).isEqualTo(secondAttempt.result());
        ResourceSnapshot after = snapshot(scenario);
        assertThat(after.audits()).hasSize(1);
        assertThat(after.idempotency()).singleElement().satisfies(row -> {
            assertThat(row.get("idempotency_key")).isEqualTo(key.value());
            assertThat(row.get("processing_status")).isEqualTo("SUCCEEDED");
            assertThat(row.get("result_http_status")).isEqualTo(200);
        });
    } finally {
        holderLocked.countDown();
        releaseHolder.countDown();
        workersReady.countDown();
        workersReady.countDown();
        startWorkers.countDown();
        cancelIfRunning(holder);
        cancelIfRunning(first);
        cancelIfRunning(second);
        shutdownAndAwait(executor);
    }
}

@Test
void differentKeysBlockOnReservationAndProduceOneSuccessOneReservation005() throws Exception {
    Scenario scenario = confirmedScenario(true);
    CountDownLatch holderLocked = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    CountDownLatch workersReady = new CountDownLatch(2);
    CountDownLatch startWorkers = new CountDownLatch(1);
    AtomicLong holderConnectionId = new AtomicLong();
    ExecutorService executor = Executors.newFixedThreadPool(3, workerFactory());
    Future<Long> holder = null;
    Future<Attempt> first = null;
    Future<Attempt> second = null;
    try {
        holder = executor.submit(() -> transactions.execute(status -> {
            jdbcTemplate.queryForObject(
                    "SELECT reservation_id FROM reservations WHERE reservation_id = ? FOR UPDATE",
                    Long.class, scenario.reservationId());
            holderConnectionId.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
            holderLocked.countDown();
            awaitLatch(releaseHolder, "reservation holder release");
            return holderConnectionId.get();
        }));
        assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
        first = executor.submit(() -> fulfillAfterStart(scenario, key(201), workersReady, startWorkers));
        second = executor.submit(() -> fulfillAfterStart(scenario, key(202), workersReady, startWorkers));
        assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
        startWorkers.countDown();
        assertFutureBlocked(first);
        assertFutureBlocked(second);
        awaitBlockingWaits(holderConnectionId.get(), "reservations", "PRIMARY", 2);
        releaseHolder.countDown();
        holder.get(10, TimeUnit.SECONDS);
        List<Attempt> attempts = List.of(
                first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(attempt ->
                attempt.errorCode() == ReservationErrorCode.INVALID_STATE_TRANSITION).hasSize(1);
        assertThat(snapshot(scenario).audits()).hasSize(1);
    } finally {
        holderLocked.countDown();
        releaseHolder.countDown();
        workersReady.countDown();
        workersReady.countDown();
        startWorkers.countDown();
        cancelIfRunning(holder);
        cancelIfRunning(first);
        cancelIfRunning(second);
        shutdownAndAwait(executor);
    }
}

private Attempt fulfillAfterStart(
        Scenario scenario, IdempotencyKey key,
        CountDownLatch ready, CountDownLatch start
) {
    ready.countDown();
    awaitLatch(start, "fulfillment worker start");
    try {
        return Attempt.success(fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key, new ReservationFulfillmentRequest()));
    } catch (ServiceException exception) {
        return Attempt.failure(exception);
    }
}

private static String fulfillmentFingerprint(long storeId, long reservationId) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "method", "POST");
    appendCanonical(canonical, "route",
            "/api/v1/store-operator/stores/{storeId}/reservations/"
                    + "{reservationId}/fulfillments");
    appendCanonical(canonical, "storeId", String.valueOf(storeId));
    appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
    return RequestFingerprint.of(canonical.toString());
}

private static void appendCanonical(StringBuilder target, String field, String value) {
    target.append(field).append('=').append(value.length()).append(':')
            .append(value).append('|');
}

```

모든 concurrency test는 `CountDownLatch workersReady/startWorkers/releaseHolder`, `Executors.newFixedThreadPool(3, workerFactory())`, `try/finally`에서 latch 해제·future cancel·`shutdownNow()`·10초 `awaitTermination`을 실제 사용한다. sleep으로 순서를 추정하지 않는다.

```java
private static void awaitLatch(CountDownLatch latch, String name) {
    try {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(name + " timed out");
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(name + " interrupted", exception);
    }
}

private static void cancelIfRunning(Future<?> future) {
    if (future != null && !future.isDone()) future.cancel(true);
}

private static void shutdownAndAwait(ExecutorService executor) throws Exception {
    executor.shutdownNow();
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        throw new AssertionError("fulfillment workers did not terminate");
    }
}

private static ThreadFactory workerFactory() {
    return task -> {
        Thread worker = new Thread(task,
                "reservation-fulfillment-it-worker-" + WORKER_SEQUENCE.incrementAndGet());
        worker.setDaemon(true);
        return worker;
    };
}
```

- [ ] **Step 4: cancellation-vs-fulfillment terminal race verification을 작성한다**

`cancellationAndFulfillmentRaceHasOneTerminalWinner`는 다음처럼 두 public facade를 같은 barrier에서 호출하고 최종 상태별 resource 효과를 literal assertion으로 분기한다.

```java
@Test
void cancellationAndFulfillmentRaceHasOneTerminalWinner() throws Exception {
    Scenario scenario = confirmedScenario(true);
    ResourceSnapshot before = snapshot(scenario);
    CountDownLatch workersReady = new CountDownLatch(2);
    CountDownLatch startWorkers = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2, workerFactory());
    Future<Attempt> cancellation = null;
    Future<Attempt> fulfillment = null;
    try {
        cancellation = executor.submit(() -> cancelAfterStart(
                scenario, key(250), workersReady, startWorkers));
        fulfillment = executor.submit(() -> fulfillAfterStart(
                scenario, key(251), workersReady, startWorkers));
        assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
        startWorkers.countDown();

        List<Attempt> attempts = List.of(
                cancellation.get(15, TimeUnit.SECONDS),
                fulfillment.get(15, TimeUnit.SECONDS));
        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(attempt ->
                attempt.errorCode() == ReservationErrorCode.INVALID_STATE_TRANSITION)
                .hasSize(1);

        ResourceSnapshot after = snapshot(scenario);
        String terminalStatus = (String) after.reservation().get("status");
        if ("CANCELLED".equals(terminalStatus)) {
            assertThat(after.cancellationAudits()).hasSize(1);
            assertThat(after.audits()).isEmpty();
            assertThat(after.holds()).singleElement().satisfies(row ->
                    assertThat(row.get("status")).isEqualTo("RELEASED"));
            assertThat(after.capacities()).allSatisfy(row -> {
                assertThat(row.get("occupied_people")).isEqualTo(0);
                assertThat(row.get("occupied_teams")).isEqualTo(0);
            });
            assertThat(after.allocations()).isEqualTo(before.allocations());
            assertThat(inventoryRemaining(scenario)).isEqualTo(5);
            assertThat(restoreLedgerCount(scenario)).isOne();
            assertThat(after.idempotency()).isEmpty();
        } else {
            assertThat(terminalStatus).isEqualTo("FULFILLED");
            assertThat(after.cancellationAudits()).isEmpty();
            assertThat(after.audits()).hasSize(1);
            assertThat(after.holds()).singleElement().satisfies(row ->
                    assertThat(row.get("status")).isEqualTo("FULFILLED"));
            assertThat(after.capacities()).isEqualTo(before.capacities());
            assertThat(after.allocations()).isEqualTo(before.allocations());
            assertThat(after.inventory()).isEqualTo(before.inventory());
            assertThat(after.ledger()).isEqualTo(before.ledger());
            assertThat(after.idempotency()).singleElement().satisfies(row ->
                    assertThat(row.get("processing_status")).isEqualTo("SUCCEEDED"));
        }
    } finally {
        workersReady.countDown();
        workersReady.countDown();
        startWorkers.countDown();
        cancelIfRunning(cancellation);
        cancelIfRunning(fulfillment);
        shutdownAndAwait(executor);
    }
}

private Attempt cancelAfterStart(
        Scenario scenario, IdempotencyKey key,
        CountDownLatch ready, CountDownLatch start
) {
    ready.countDown();
    awaitLatch(start, "cancellation worker start");
    try {
        return Attempt.success(cancellationFacade.cancelByStoreOperator(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key, new StoreCancellationRequest("terminal race")));
    } catch (ServiceException exception) {
        return Attempt.failure(exception);
    }
}

private int inventoryRemaining(Scenario scenario) {
    return jdbcTemplate.queryForObject("""
            SELECT inventory.online_hold_remaining
              FROM menu_inventory_buckets inventory
              JOIN menu_hold_items item
                ON item.menu_inventory_bucket_id = inventory.menu_inventory_bucket_id
              JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
             WHERE hold.reservation_id = ?
            """, Integer.class, scenario.reservationId());
}

private int restoreLedgerCount(Scenario scenario) {
    return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM menu_inventory_ledger ledger
              JOIN menu_hold_items item
                ON item.menu_inventory_bucket_id = ledger.menu_inventory_bucket_id
              JOIN menu_holds hold ON hold.menu_hold_id = item.menu_hold_id
             WHERE hold.reservation_id = ? AND ledger.operation_type = 'RESTORE'
            """, Integer.class, scenario.reservationId());
}

private record Attempt(Object result, ErrorCode errorCode) {
    boolean succeeded() { return result != null; }
    static Attempt success(Object result) { return new Attempt(result, null); }
    static Attempt failure(ServiceException failure) { return new Attempt(null, failure.getErrorCode()); }
}
```

- [ ] **Step 5: 세 late failure의 trigger rollback과 같은-key 재시도를 검증한다**

```java
private enum FailurePoint { MENU_HOLD_UPDATE, AUDIT_INSERT, IDEMPOTENCY_SUCCEEDED_UPDATE }

private void createFailureTrigger(FailurePoint point) {
    String ddl = switch (point) {
        case MENU_HOLD_UPDATE -> "CREATE TRIGGER trg_fulfill_hold_failure BEFORE UPDATE ON menu_holds FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fulfillment hold failure'";
        case AUDIT_INSERT -> "CREATE TRIGGER trg_fulfill_audit_failure BEFORE INSERT ON reservation_fulfillment_audits FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fulfillment audit failure'";
        case IDEMPOTENCY_SUCCEEDED_UPDATE -> "CREATE TRIGGER trg_fulfill_idempotency_failure BEFORE UPDATE ON idempotency_commands FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'fulfillment idempotency failure'";
    };
    jdbcTemplate.execute(ddl);
}

private void dropFailureTrigger(FailurePoint point) {
    String name = switch (point) {
        case MENU_HOLD_UPDATE -> "trg_fulfill_hold_failure";
        case AUDIT_INSERT -> "trg_fulfill_audit_failure";
        case IDEMPOTENCY_SUCCEEDED_UPDATE -> "trg_fulfill_idempotency_failure";
    };
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name);
}

@ParameterizedTest
@EnumSource(FailurePoint.class)
void latePersistenceFailureRollsBackAllEffectsAndSameKeyCanRetry(FailurePoint point) {
    Scenario scenario = confirmedScenario(true);
    IdempotencyKey key = key(300 + point.ordinal());
    ResourceSnapshot before = snapshot(scenario);
    try {
        createFailureTrigger(point);
        assertThatThrownBy(() -> fulfillmentFacade.fulfill(
                scenario.operatorId(), scenario.storeId(), scenario.reservationId(),
                key, new ReservationFulfillmentRequest()))
                .hasRootCauseInstanceOf(SQLException.class);
    } finally {
        dropFailureTrigger(point);
    }
    assertThat(snapshot(scenario)).isEqualTo(before);
    assertThat(fulfillmentFacade.fulfill(scenario.operatorId(), scenario.storeId(),
            scenario.reservationId(), key, new ReservationFulfillmentRequest()).httpStatus())
            .isEqualTo(200);
    assertThat(snapshot(scenario).audits()).hasSize(1);
}
```

- [ ] **Step 6: class-filtered verification을 실행한다**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentIT" --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest"
```

Expected: 두 integration class PASS, worker executor 종료, non-daemon test thread 0. 실패하면 먼저 `systematic-debugging`으로 DB 상태·SQL·transaction boundary를 추적한다. 수정은 live Issue의 fulfillment production 경로와 앞 task에 이미 존재하는 behavioral RED에 한정하고, 새 production behavior라면 해당 unit RED를 먼저 추가한다.

- [ ] **Step 7: 영향받는 종결 계약을 최소 회귀한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest" --tests "com.miriyum.domain.menuhold.service.MenuHoldTerminalServiceTest"
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT"
```

Expected: cancellation과 MenuHold terminal 공개 계약 PASS, fulfillment가 취소 복구 동작을 바꾸지 않는다.

- [ ] **Step 8: test와 실제 최소 fix만 non-amend commit한다**

```powershell
git add backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java
git diff --cached --name-only
git commit -m "test(reservation): 방문 완료 원자성을 검증한다"
```

production fix가 발생했다면 이 commit 전에 해당 앞 task로 돌아가 behavioral RED→GREEN과 그 task의 explicit stage를 수행한다. Task 7 commit에는 IT 경로만 포함하고 allowlist 밖 수정이 필요하면 `BLOCKED`다.

### Task 8: Final Verification and Scope Audit

**Files:**
- Verify only: live Issue #52 exact allowlist와 `origin/dev...HEAD` 전체

**Interfaces:**
- Consumes: Tasks 1–7의 committed feature.
- Produces: 명령·결과·위험·증거가 있는 merge-readiness report. 실패한 gate가 있으면 완료를 주장하지 않는다.

- [ ] **Step 1: remote dev·PR #213·migration을 live 재검증하고 필요하면 안전하게 재동기화한다**

Run from repository root:

```powershell
$repoSlug = 'sparta-spring4/Commerce-Final-Project-MiriYum'
git status --short --branch
git diff --cached --name-only
$tracked = @(git status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final tracked status query failed' }
$staged = @(git diff --cached --name-only)
if ($LASTEXITCODE -ne 0 -or $tracked.Count -ne 0 -or $staged.Count -ne 0) { throw 'BLOCKED: tracked worktree/index must be clean before final refresh' }
git fetch origin dev
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final origin/dev fetch failed' }
$pr213Raw = gh pr view 213 --repo $repoSlug --json state,baseRefName,headRefOid,mergeCommit,mergedAt
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pr213Raw)) { throw 'BLOCKED: final PR #213 query failed' }
try { $pr213 = $pr213Raw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: final PR #213 query returned invalid JSON' }
if ($pr213.state -ne 'MERGED' -or $pr213.baseRefName -ne 'dev' -or $pr213.headRefOid -ne '8d98558a55345e4b7f9da918e234f008b54f8bd9') { throw 'PR #213 live evidence changed' }
git merge-base --is-ancestor $pr213.mergeCommit.oid origin/dev
if ($LASTEXITCODE -ne 0) { throw 'PR #213 merge commit is not in live origin/dev' }
$issueRaw = gh issue view 52 --repo $repoSlug --json title,body,state,assignees,labels,milestone,url
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueRaw)) { throw 'BLOCKED: final Issue #52 query failed' }
try { $issue = $issueRaw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: final Issue #52 query returned invalid JSON' }
if ([string]::IsNullOrWhiteSpace($issue.body)) { throw 'BLOCKED: final Issue #52 body is empty' }
$scope = [regex]::Match($issue.body, '(?s)### 구현 단계 repository 쓰기 허용 경로\s+(?<scope>.*?)(?=### 구현 시작 게이트)')
if (-not $scope.Success -or $issue.body -notmatch '구현 판정: `READY FOR IMPLEMENTATION`') { throw 'BLOCKED: Issue #52 is not ready for implementation' }
$livePaths = @([regex]::Matches($scope.Groups['scope'].Value, '(?m)^- `([^`]+)`\r?$') | ForEach-Object { $_.Groups[1].Value })
$duplicates = @($livePaths | Group-Object | Where-Object Count -gt 1)
$migrationPaths = @($livePaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V\d+__create_reservation_fulfillment_audits\.sql$' })
if ($livePaths.Count -eq 0 -or $duplicates.Count -ne 0 -or $migrationPaths.Count -ne 1) { throw 'BLOCKED: live Issue exact allowlist is invalid' }
$gatedMatches = [regex]::Matches($issue.body, '(?m)^- 재게이트 `dev` SHA: `([0-9a-f]{40})`$')
if ($gatedMatches.Count -ne 1) { throw 'BLOCKED: live Issue must contain exactly one gated dev SHA' }
$gatedDev = $gatedMatches[0].Groups[1].Value
git cat-file -e "$gatedDev`^{commit}"
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: gated dev SHA is not a local commit after fetch' }
$liveDev = git rev-parse origin/dev
if ($LASTEXITCODE -ne 0 -or $liveDev -notmatch '^[0-9a-f]{40}$') { throw 'BLOCKED: live origin/dev SHA is invalid' }
$devMigrationPaths = @(git ls-tree -r --name-only origin/dev -- backend/src/main/resources/db/migration)
if ($LASTEXITCODE -ne 0 -or $devMigrationPaths.Count -eq 0) { throw 'BLOCKED: final origin/dev migration listing failed or is empty' }
if (@($devMigrationPaths | Where-Object { $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }).Count -ne 0) { throw 'BLOCKED: final origin/dev migration path set is invalid' }
$openPrRaw = gh pr list --repo $repoSlug --state open --limit 100 --json number,files
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($openPrRaw)) { throw 'BLOCKED: final open PR migration query failed' }
try { $openPrData = @($openPrRaw | ConvertFrom-Json -ErrorAction Stop) } catch { throw 'BLOCKED: final open PR migration query returned invalid JSON' }
if (-not $openPrRaw.TrimStart().StartsWith('[')) { throw 'BLOCKED: final open PR migration result is not a JSON array' }
$openPaths = @($openPrData | ForEach-Object { @($_.files) | ForEach-Object { $_.path } })
if (@($openPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -match '\\' }).Count -ne 0) { throw 'BLOCKED: final open PR file path set is invalid' }
$invalidOpenMigrationPaths = @($openPaths | Where-Object { $_ -like 'backend/src/main/resources/db/migration/*' -and $_ -notmatch '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' })
if ($invalidOpenMigrationPaths.Count -ne 0) { throw 'BLOCKED: final open PR migration path set is invalid; no version may be selected' }
$occupiedOutsideFeature = @($devMigrationPaths + @($openPaths | Where-Object { $_ -match '^backend/src/main/resources/db/migration/V[0-9]+__[^/]+\.sql$' }))
$migrationVersion = [regex]::Match($migrationPaths[0], '/V(\d+)__').Groups[1].Value
if (@($occupiedOutsideFeature | Where-Object { $_ -match "/V${migrationVersion}__" }).Count -ne 0) { throw 'BLOCKED: migration collision; execute the correction/re-gate/rename/full-retest flow below' }
$changedPaths = @(git diff --name-only "$gatedDev...HEAD")
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: pre-refresh scope diff failed' }
$missing = @($livePaths | Where-Object { $_ -notin $changedPaths })
$extra = @($changedPaths | Where-Object { $_ -notin $livePaths })
if ($missing.Count -ne 0 -or $extra.Count -ne 0) { throw "BLOCKED: pre-refresh scope changed; missing=$($missing -join ','); extra=$($extra -join ','); execute the correction flow below" }
if ($gatedDev -ne $liveDev) {
  git merge-base --is-ancestor $gatedDev origin/dev
  if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: live dev is not a descendant of gated dev; execute the correction flow below' }
  $advancedPaths = @(git diff --name-only "$gatedDev..origin/dev")
  if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: dev advance overlap analysis failed' }
  $overlap = @($advancedPaths | Where-Object { $_ -in $livePaths })
  if ($overlap.Count -ne 0) { throw "BLOCKED: dev advanced across #52 scope: $($overlap -join ','); execute the correction flow below" }
  $preSync = git rev-parse --short=12 HEAD
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($preSync)) { throw 'BLOCKED: pre-sync HEAD query failed' }
  $safetyBranch = "feature/issue-52-reservation-fulfillment-pre-final-dev-$preSync-$([guid]::NewGuid().ToString('N').Substring(0,8))"
  git branch $safetyBranch HEAD
  if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final refresh safety branch creation failed' }
  git merge --no-edit origin/dev
  if ($LASTEXITCODE -ne 0) {
    git merge --abort
    if ($LASTEXITCODE -ne 0) { throw "BLOCKED: merge and abort failed; recover from $safetyBranch" }
    throw "BLOCKED: dev merge conflict; branch restored and $safetyBranch preserved"
  }
  $gatePattern = '(?s)### 구현 시작 게이트.*?(?=## 확정된 제품 경계)'
  $gateMatches = [regex]::Matches($issue.body, $gatePattern)
  if ($gateMatches.Count -ne 1) { throw 'BLOCKED: current implementation gate section is not unique' }
  $oldGate = $gateMatches[0].Value
  $shaPattern = '(?m)^- 재게이트 `dev` SHA: `[0-9a-f]{40}`$'
  if ([regex]::Matches($oldGate, $shaPattern).Count -ne 1) { throw 'BLOCKED: current gate SHA line is not unique' }
  $newGate = [regex]::Replace($oldGate, $shaPattern, "- 재게이트 ``dev`` SHA: ``$liveDev``")
  $updatedBody = $issue.body.Remove($gateMatches[0].Index, $gateMatches[0].Length).Insert($gateMatches[0].Index, $newGate)
  $issueBodyPath = Join-Path $env:TEMP "miriyum-issue-52-final-refresh-$([guid]::NewGuid().ToString('N')).md"
  [System.IO.File]::WriteAllText($issueBodyPath, $updatedBody, [System.Text.UTF8Encoding]::new($false))
  gh issue edit 52 --repo $repoSlug --body-file $issueBodyPath
  if ($LASTEXITCODE -ne 0) { throw "BLOCKED: final Issue gate SHA update failed; preserve $issueBodyPath" }
  $issueAfterRaw = gh issue view 52 --repo $repoSlug --json title,body,state,assignees,labels,milestone,url
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($issueAfterRaw)) { throw 'BLOCKED: final Issue readback failed' }
  try { $issueAfter = $issueAfterRaw | ConvertFrom-Json -ErrorAction Stop } catch { throw 'BLOCKED: final Issue readback returned invalid JSON' }
  if ($issueAfter.body -ne $updatedBody -or $issueAfter.title -ne $issue.title -or $issueAfter.state -ne $issue.state) { throw 'BLOCKED: final Issue readback body/title/state mismatch' }
  if ([string]::Join(',', @($issueAfter.assignees.login | Sort-Object)) -ne [string]::Join(',', @($issue.assignees.login | Sort-Object))) { throw 'BLOCKED: final Issue assignees changed' }
  if ([string]::Join(',', @($issueAfter.labels.name | Sort-Object)) -ne [string]::Join(',', @($issue.labels.name | Sort-Object))) { throw 'BLOCKED: final Issue labels changed' }
  $beforeMilestone = if ($null -eq $issue.milestone) { '' } else { $issue.milestone.title }
  $afterMilestone = if ($null -eq $issueAfter.milestone) { '' } else { $issueAfter.milestone.title }
  if ($beforeMilestone -ne $afterMilestone) { throw 'BLOCKED: final Issue milestone changed' }
  Remove-Item -LiteralPath $issueBodyPath -Force
  $issue = $issueAfter
  $gatedDev = $liveDev
  $advancedPaths
}
git merge-base --is-ancestor origin/dev HEAD
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: feature branch does not contain current origin/dev' }
$changedPaths = @(git diff --name-only origin/dev...HEAD)
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: post-refresh scope diff failed' }
$missing = @($livePaths | Where-Object { $_ -notin $changedPaths })
$extra = @($changedPaths | Where-Object { $_ -notin $livePaths })
if ($missing.Count -ne 0 -or $extra.Count -ne 0) { throw "BLOCKED: post-refresh scope changed; missing=$($missing -join ','); extra=$($extra -join ','); execute the correction flow below" }
git diff --name-only origin/dev...HEAD
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final diff listing failed' }
git diff --check origin/dev...HEAD
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final diff check failed' }
backend\gradlew.bat -p backend --version
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: Gradle/Java runtime verification failed' }
```

Expected: tracked/staged clean, PR #213 exact head가 live `origin/dev` ancestry에 있고, open PR과 migration 충돌이 없으며, branch diff가 live exact allowlist와 정확히 일치한다. `origin/dev`가 그대로면 다음 step으로 진행한다. 전진했지만 scope overlap·migration 충돌이 없으면 safety branch를 만든 뒤 최신 `dev`를 merge하고 현재 **구현 시작 게이트 section의 SHA 한 줄만** 갱신·readback한다. 초기 generator patch와 `READY FOR PLAN` parser는 재사용하지 않는다. 이 경우에도 아래 Steps 2–5 전체가 mandatory라 모든 #52 focused test, MySQL fixture, current-source generator, full backend build를 새 merge 결과에서 다시 실행한다.

Migration collision, dev/scope overlap, allowlist 변화가 발생하면 즉시 `BLOCKED`이며 merge나 Issue 갱신을 계속하지 않는다. correction flow는 (1) 최신 `dev`와 모든 open PR migration을 위와 같은 fail-closed query로 다시 계산하고, (2) 아직 적용되지 않은 #52 migration만 `git mv -- $oldMigrationPath $newMigrationPath`로 다음 유일 버전에 rename하고 코드·Issue exact allowlist의 그 한 경로를 함께 수정하며, (3) overlap된 contract/scope를 소유 문서와 Issue에서 승인받아 새 doc-only correction commit으로 기록하고 전용 current implementation section parser로 재게이트하며, (4) clean 상태에서 최신 `dev`를 merge하고, (5) Task 2 clean-install/previous-version-upgrade migration tests, 영향받은 task의 focused tests, 아래 Steps 2–5 전체를 모두 새로 실행하는 순서다. 어느 단계든 query failure·invalid JSON/path·migration 재충돌이면 version을 고르지 않고 계속 `BLOCKED`다.

- [ ] **Step 2: 모든 focused unit·slice tests를 실행한다**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.ReservationFulfillmentRequestTest" --tests "com.miriyum.domain.reservation.entity.ReservationFulfillmentAuditTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentCommandFacadeTest" --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest" --tests "com.miriyum.domain.reservation.ReservationProductionDependencyTest" --rerun-tasks
```

Expected: 모든 지정 class PASS, failure/error/skip 0.

- [ ] **Step 3: class-filtered MySQL tests를 실행한다**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest" --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentIT" --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --rerun-tasks
```

Expected: migration, fulfillment, cancellation integration tests PASS, failure/error/skip 0.

- [ ] **Step 4: generator clean을 재검증한다**

Run from repository root:

```powershell
$generatedRoot = 'frontend/src/shared/api/generated'
$beforeGenerated = @(git status --porcelain --untracked-files=all -- $generatedRoot)
if ($LASTEXITCODE -ne 0 -or $beforeGenerated.Count -ne 0) { throw 'BLOCKED: generated paths are not clean before final generation' }
pnpm --dir frontend generate:api
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: final API generation failed' }
$afterGenerated = @(git status --porcelain --untracked-files=all -- $generatedRoot)
if ($LASTEXITCODE -ne 0 -or $afterGenerated.Count -ne 0) { throw "BLOCKED: current OpenAPI source generates drift: $($afterGenerated -join ',')" }
git diff --check origin/dev...HEAD
if ($LASTEXITCODE -ne 0) { throw 'BLOCKED: diff check failed after generation' }
```

Expected: generator 실행 뒤 새 diff가 없다. 새 generated diff가 생기면 Issue allowlist와 Task 1 commit 누락을 먼저 수정하고 관련 frontend typecheck를 실행한다.

- [ ] **Step 5: 전체 backend build를 실행한다**

Run from repository root:

```powershell
backend\gradlew.bat -p backend build --rerun-tasks
```

Expected: compilation, unit/slice, 모든 integration tests, packaging이 exit 0으로 PASS한다.

- [ ] **Step 6: daemon을 종료하고 결과 XML을 집계한다**

```powershell
backend\gradlew.bat -p backend --stop
```

현재 실행이 생성한 `backend/build/test-results/test/*.xml`과 `backend/build/test-results/integrationTest/*.xml`만 집계해 tests/failures/errors/skipped를 기록한다. stale shard XML을 전체 합계에 섞지 않는다.

- [ ] **Step 7: placeholder·dependency·migration·scope를 감사한다**

Run from repository root:

```powershell
$unfinishedPattern = ('TO' + 'DO|T' + 'BD|Unsupported' + 'OperationException|return ' + 'null')
rg -n $unfinishedPattern backend/src/main/java/com/miriyum/domain/reservation
rg -n "com\.miriyum\.domain\.(auth|store|menuhold)\..*(entity|repository)" backend/src/main/java/com/miriyum/domain/reservation
git diff --name-only origin/dev...HEAD
git diff --check origin/dev...HEAD
git status --short --branch
```

Expected: 신규 placeholder 0, 타 도메인 내부 import 0, migration version 유일, live Issue allowlist missing/extra 0, tracked/staged clean. 기존 의도적 null guard는 diff로 신규 추가되지 않았음을 확인한다.

- [ ] **Step 8: 독립 code review를 요청한다**

Reviewer에게 Issue #52, 승인 설계, 이 plan, `origin/dev...HEAD` 전체 diff, 실제 test evidence를 제공한다. 다음을 명시적으로 검토한다.

```text
ownership preflight outside idempotent callback
store-scoped Reservation FOR UPDATE
CONFIRMED-only transition
NO_HOLD and already-FULFILLED MenuHold contracts
no capacity/allocation/inventory access or restore
same occurredAt in Reservation and audit
both policy version snapshots
fixed RESERVATION_FULFILL reason without free-form field
91-character correlation and trimmed command_id check
replay mutation-free behavior
rollback at MenuHold, audit, and idempotency finalize failures
controller @Positive and strict COMMON_002 body failures
security matcher isolation and existing route regressions
exact allowlist and generated drift
```

Critical/Important finding은 live allowlist 안에서 최소 수정하고 해당 focused gate와 Step 5 전체 build를 다시 실행한 뒤 재리뷰한다.

- [ ] **Step 9: 완료 증거를 정리한다**

다음 네 필드를 PR 준비 보고에 포함한다.

```text
명령: 실제 실행한 literal commands
결과: PASS | FAIL | BLOCKED | NOT RUN | NOT CONFIGURED | NOT APPLICABLE
위험: 남은 불확실성, 실행하지 않은 범위, rollback 또는 후속 위험
증거: exit code, test counts, CI/PR 링크 또는 review artifact
```

API smoke와 조합된 cross-end runtime은 현재 `NOT CONFIGURED`이므로 문서·MockMvc·generator만으로 E2E PASS를 주장하지 않는다. 사용자가 publish를 선택하기 전에는 push/PR을 수행하지 않는다.

## Plan Self-Review Checklist

- [x] 승인 설계의 목표·비목표·명명·감사·멱등·잠금·응답·오류·동시성·migration·stack gate가 Mandatory Execution Gate와 Tasks 1–8에 각각 매핑된다.
- [x] `ReservationFulfillmentRequest` → facade `fulfill` → service `fulfillStoreReservation` → `fulfillReservationWork` → audit `recordSuccess` signature가 모든 task에서 동일하다.
- [x] Store와 MenuHold는 현재 공개 Service/DTO signature만 소비하며 내부 Entity·Repository path가 File Responsibility Map에 없다.
- [x] `reservation_time_policy_version`은 `reservation.getTimeSnapshot().getReservationTimePolicyVersion()`에서, `capacity_policy_version`은 `reservation.getCapacityPolicyVersion()`에서 복사한다.
- [x] current-owner preflight가 idempotency callback 밖에 있고 replay mutation이 없다.
- [x] 취소의 capacity 복구·policy evaluator·Reservation rehydrate가 fulfillment plan에 없다.
- [x] Controller는 두 path ID에 `@Positive`, key에 `required=false`, request에 `@Valid @RequestBody`를 사용한다.
- [x] migration의 actor/time/transition/version/trimmed command ID constraints와 clean/upgrade/round-trip tests가 있다.
- [x] generator probe와 final generator clean, registered backend commands, class-filtered integration, full build가 literal command로 기재됐다.
- [x] 각 repository 변경 task가 exact stage paths와 non-amend commit을 가진다.
- [x] 최종 Issue allowlist는 Mandatory Execution Gate에서 계산하며 이 문서는 구현 전 경로 수를 확정했다고 주장하지 않는다.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-10-reservation-fulfillment.md`. Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, specification review and code-quality review between tasks.
2. **Inline Execution** — execute with `superpowers:executing-plans` in batches with checkpoints.

사용자의 기존 지시에 따라 실행 시에는 1번 Subagent-Driven 방식을 사용한다.
