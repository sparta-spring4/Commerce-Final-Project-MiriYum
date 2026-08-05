# Pickup Inventory Public Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 픽업 도메인이 메뉴 수량 내부 Entity·Repository를 참조하지 않고 온라인 가용량 조회, 조건부 확보, 최초 확보 기준 복구를 수행할 수 있는 공개 Service·DTO·테스트 fixture를 제공한다.

**Architecture:** 공개 `MenuInventoryTransactionService`는 소비자용 DTO만 노출하고, `MenuInventoryTransactionServiceRuntime`이 기존 package-private `MenuInventoryService`에 위임한다. 조회는 현재 정책 버킷의 온라인 가용량을 읽고, 확보·복구는 기존 #42의 PK 잠금·조건부 SQL·원장을 재사용한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, MySQL 8.0.40 Testcontainers, JUnit 5, AssertJ, Mockito, Gradle 9.6.1

## Global Constraints

- 작업 브랜치는 최신 `dev`에서 생성한 `feature/108-pickup-inventory-contract`를 사용한다.
- production 변경은 `backend/src/main/java/com/miriyum/domain/menuhold/**`에만 둔다.
- test 변경은 `backend/src/test/java/com/miriyum/domain/menuhold/**`에만 둔다.
- Store·Pickup Entity/Repository를 참조하지 않는다.
- `MenuInventoryBucket`, bucket ID, lock version, 실제 풀 배분과 원장 행을 공개 DTO에 노출하지 않는다.
- 새 HTTP API, Pickup aggregate, 공통 멱등 저장, Flyway migration을 추가하지 않는다.
- 확보·복구는 `Propagation.MANDATORY`, 조회는 read-only 트랜잭션을 사용한다.
- Testcontainers 통합 테스트에는 `integration`과 `integration-shard-a/b` 중 정확히 하나를 선언한다.
- 단위·contract 테스트에는 integration shard 태그를 붙이지 않는다.
- 모든 production 동작은 실패 테스트를 먼저 실행해 의도한 이유로 실패한 뒤 구현한다.
- `.idea/`와 허용 경로 밖의 사용자 변경은 stage·commit하지 않는다.
- 커밋 메시지는 `<type>(<scope>): <한글 요약>` 형식을 사용하고 작업 단위별로 나눈다.

---

## File Structure

### 공개 계약

- Create: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionService.java`
  - 픽업 소비자가 사용하는 조회·확보·복구 공개 메서드만 선언한다.
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAvailabilityQuery.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAvailability.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireCommand.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireSelection.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquiredItem.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryRestoreCommand.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryRestoreResult.java`
  - ID·시간 구간·정책 버전·수량을 검증하고 내부 영속 식별자를 숨긴다.

### 런타임

- Create: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceRuntime.java`
  - 공개 DTO를 내부 #42 요청으로 변환하고 결과를 공개 결과로 매핑한다.
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/inventory/dto/OnlineInventoryAvailabilityView.java`
  - Repository 조회 projection이며 public 계약으로 사용하지 않는다.
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/inventory/repository/MenuInventoryBucketRepository.java`
  - 현재 정책 버전의 지정 메뉴·구간 온라인 잔여와 상태를 메뉴 ID 순서로 조회한다.
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryService.java`
  - 공개 runtime이 호출할 package-private 조회·확보·복구 내부 메서드를 재사용하고 필요한 반환값만 제공한다.

### 테스트 계약

- Create: `backend/src/test/java/com/miriyum/domain/menuhold/contract/PickupMenuInventoryContractFixture.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/contract/MenuInventoryTransactionServiceConsumerContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/inventory/MenuInventoryRuntimeIT.java`
  - 기존 class-level `@Tag("integration")`, `@Tag("integration-shard-b")`를 유지한다.
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/MenuHoldProductionDependencyTest.java`

---

### Task 1: 공개 DTO·Service와 픽업 소비자 fixture

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionService.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAvailabilityQuery.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAvailability.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireCommand.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireSelection.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquireResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryAcquiredItem.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryRestoreCommand.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuInventoryRestoreResult.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/contract/PickupMenuInventoryContractFixture.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/contract/MenuInventoryTransactionServiceConsumerContractTest.java`

**Interfaces:**
- Produces:
  - `List<MenuInventoryAvailability> findOnlineAvailability(MenuInventoryAvailabilityQuery query)`
  - `MenuInventoryAcquireResult acquire(MenuInventoryAcquireCommand command)`
  - `MenuInventoryRestoreResult restore(MenuInventoryRestoreCommand command)`
- `findOnlineAvailability` is `@Transactional(readOnly = true)`.
- `acquire` and `restore` are `@Transactional(propagation = Propagation.MANDATORY)`.

- [ ] **Step 1: 공개 계약의 형태와 트랜잭션 annotation을 검증하는 실패 테스트 작성**

  `MenuInventoryTransactionServiceConsumerContractTest`에서 reflection으로 위 세 메서드의
  인자·반환 타입과 transaction 속성을 검증한다. DTO 테스트는 양수가 아닌 ID·수량,
  빈 operation ID, 빈 메뉴 목록, 증가하지 않는 서비스 구간을 거부하고 중복 메뉴·구간
  수량을 `Math.addExact`로 합산하는 동작을 검증한다.

- [ ] **Step 2: 실패 테스트 실행**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.contract.MenuInventoryTransactionServiceConsumerContractTest"`

  Expected: `MenuInventoryTransactionService`와 공개 DTO가 없어 test compilation이 실패한다.

- [ ] **Step 3: 최소 공개 DTO와 Service interface 구현**

  `MenuInventoryAvailability`는 `menuId`, `inventoryPolicyVersion`, `timeZoneId`, 시작·종료
  날짜/시각, `availableOnlineQuantity`, `AvailabilityStatus`를 제공한다. 공개 enum은 DTO
  내부 enum `AVAILABLE`, `SOLD_OUT`만 사용한다. 확보 결과는 operation ID와
  `MenuInventoryAcquiredItem(menuId, inventoryPolicyVersion, quantity)` 목록만 제공한다.
  복구 결과는 새 operation ID와 source acquire operation ID만 제공한다.

- [ ] **Step 4: 픽업 소비자 fixture 구현과 소비 계약 테스트 추가**

  fixture는 고정 조회 결과, 기록된 acquire/restore 명령, 지정 `ServiceException` 실패를
  제공한다. 테스트 내부 `PickupConsumer`는 idempotency key 결과를 먼저 확인해 replay에서
  fixture의 acquire/restore 호출 수가 증가하지 않고 논리 명령마다 새 operation ID를
  생성함을 검증한다.

- [ ] **Step 5: 공개 계약 테스트 통과 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.contract.MenuInventoryTransactionServiceConsumerContractTest"`

  Expected: PASS.

- [ ] **Step 6: 허용 파일만 stage하고 첫 구현 커밋**

  Stage: Task 1의 production DTO·Service와 두 contract test 파일만.

  Commit: `feat(menu-hold): 픽업 수량 공개 계약 추가`

---

### Task 2: 온라인 가용량 조회 runtime

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/inventory/dto/OnlineInventoryAvailabilityView.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/inventory/repository/MenuInventoryBucketRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceRuntime.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryService.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/inventory/MenuInventoryRuntimeIT.java`

**Interfaces:**
- Consumes: Task 1의 `MenuInventoryTransactionService`와 공개 DTO.
- Produces: 현재 정책 버전의 메뉴별 온라인 가용량을 메뉴 ID 오름차순으로 반환하는 runtime.

- [ ] **Step 1: 조회 service 실패 테스트 작성**

  mock Repository projection으로 `ONLINE_HOLD`, `SHARED`, `sharedOnlineAllowed`,
  `availabilityStatus` 조합을 제공한다. runtime이 `ONSITE` 값을 전혀 받지 않고,
  `ONLINE_HOLD + 허용된 SHARED`만 합산하며 `SOLD_OUT` 상태를 보존하고 menu ID
  오름차순으로 반환하는지 검증한다. 요청한 메뉴 하나가 빠지면 `MENU_HOLD_003`이어야 한다.

- [ ] **Step 2: 조회 service RED 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.service.MenuInventoryTransactionServiceTest"`

  Expected: runtime과 repository query가 없어 실패한다.

- [ ] **Step 3: Testcontainers 조회 실패 테스트 작성 및 RED 확인**

  실제 MySQL에 현재·과거 정책 버킷과 `AVAILABLE`·`SOLD_OUT`, shared 허용/비허용 버킷을
  저장한다. 현재 정책만 선택되고 `ONSITE`가 온라인 가용량에 포함되지 않는지 검증한다.
  기존 테스트 클래스의 `@Tag("integration")`과 `@Tag("integration-shard-b")`를 유지한다.

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.menuhold.service.MenuInventoryRuntimeIT.public*"`

  Expected: repository/runtime이 없어 test compilation 또는 조회 assertion이 실패한다.

- [ ] **Step 4: 현재 정책 가용량 projection과 Repository query 구현**

  query는 지정된 메뉴 ID·정확한 시작/종료 구간에 대해 interval별 최대
  `inventoryPolicyVersion` 행만 선택하고 `order by bucket.menuId, bucket.id`를 적용한다.
  projection은 menu ID, policy version, timezone, interval, online remaining, shared
  remaining, shared-online flag, availability status만 제공한다.

- [ ] **Step 5: runtime 조회 매핑 구현**

  runtime은 요청 ID 집합과 결과 ID 집합이 정확히 일치하지 않으면 `BUCKET_NOT_FOUND`를
  던진다. `AVAILABLE`은 계산된 온라인 가용량을 반환하고 `SOLD_OUT`은 잔여량을 유지하되
  상태를 `SOLD_OUT`으로 반환해 소비자가 신규 후보에서 제외할 수 있게 한다.

- [ ] **Step 6: 조회 service GREEN 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.service.MenuInventoryTransactionServiceTest"`

  Expected: PASS.

- [ ] **Step 7: 조회 통합 테스트 GREEN 확인**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.menuhold.service.MenuInventoryRuntimeIT.public*"`

  Expected: PASS.

  Run: `.\gradlew.bat verifyIntegrationTestTags --rerun-tasks`

  Expected: 새 통합 테스트가 정확히 한 shard에 배정되고 task가 exit 0으로 성공한다.

- [ ] **Step 8: 허용 파일만 stage하고 조회 커밋**

  Commit: `feat(menu-hold): 픽업 온라인 가용량 조회 구현`

---

### Task 3: 조건부 확보와 원래 풀 복구 공개 runtime

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceRuntime.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuInventoryTransactionServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/inventory/MenuInventoryRuntimeIT.java`

**Interfaces:**
- Consumes: Task 1의 acquire/restore command.
- Produces: 내부 pool allocation을 숨기는 acquire/restore result.

- [ ] **Step 1: 공개 확보·복구 adapter 실패 테스트 작성**

  runtime이 공개 선택을 기존 `InventoryAcquireRequest.Selection`으로 정확히 매핑하고,
  내부 allocation 결과를 외부에 노출하지 않는지 검증한다. restore는 새 operation ID와
  source acquire operation ID만 내부 `InventoryRestoreRequest`로 전달해야 한다.

- [ ] **Step 2: adapter RED 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.service.MenuInventoryTransactionServiceTest"`

  Expected: acquire/restore가 구현되지 않아 실패한다.

- [ ] **Step 3: Testcontainers 확보·복구·경합 실패 테스트 추가**

  다음 시나리오를 barrier/latch와 `TransactionTemplate`로 검증한다.

  - 복수 메뉴 중 하나가 부족하면 모든 수량과 원장이 롤백된다.
  - 두 거래가 같은 마지막 온라인 수량을 경합하면 하나만 성공한다.
  - 상위 트랜잭션이 확보 뒤 실패하면 수량·원장이 롤백된다.
  - 복구는 실제 최초 `ONLINE_HOLD`·`SHARED` 배분으로 돌아간다.
  - 같은 source acquire를 다른 restore operation으로 반복해도 한 번만 증가한다.
  - 상위 트랜잭션이 복구 뒤 실패하면 복구 수량·원장이 롤백된다.

- [ ] **Step 4: 통합 RED 확인**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.menuhold.service.MenuInventoryRuntimeIT.public*"`

  Expected: 공개 runtime command가 없어 새 시나리오가 실패한다.

- [ ] **Step 5: 최소 acquire/restore adapter 구현**

  기존 `MenuInventoryService.acquireInventory`와 `restoreInventory`를 그대로 사용한다.
  새 transaction을 시작하거나 `ServiceException`을 변환하지 않는다. 공개 결과는 입력에서
  확정 가능한 operation·menu·policy·quantity만 복사한다.

- [ ] **Step 6: adapter GREEN 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.service.MenuInventoryTransactionServiceTest"`

  Expected: PASS.

- [ ] **Step 7: 기존 #42 runtime을 통한 통합 GREEN 확인**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.menuhold.service.MenuInventoryRuntimeIT.public*"`

  Expected: PASS.

- [ ] **Step 8: 허용 파일만 stage하고 명령 커밋**

  Commit: `feat(menu-hold): 픽업 수량 확보와 복구 공개`

---

### Task 4: 공개 경계·Javadoc·회귀 검증

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/MenuHoldProductionDependencyTest.java`
- Modify: Task 1~3에서 생성한 공개 Service·DTO와 runtime 파일의 Javadoc

**Interfaces:**
- Consumes: 완성된 공개 계약과 runtime.
- Produces: 다른 도메인 직접 의존 금지와 공개 세부사항 은닉에 대한 구조 증거.

- [ ] **Step 1: 구조 실패 테스트 작성**

  production `menuhold` source가 `domain.pickup` 또는 다른 도메인의 Entity·Repository를
  import하지 않는지 검사한다. 공개 Service 메서드의 parameter·return type graph가
  `menuhold.entity`, `menuhold.repository`, `menuhold.inventory.entity`,
  `menuhold.inventory.repository`를 노출하지 않는지 reflection으로 검증한다.

- [ ] **Step 2: 구조 검사 결과 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.MenuHoldProductionDependencyTest"`

  Expected: PASS이면 현재 공개 경계가 요구를 만족하므로 production 변경 없이 유지한다.
  FAIL이면 출력된 내부 타입 노출 또는 타 도메인 직접 의존만 허용 범위에서 제거한다.

- [ ] **Step 3: 공개 계약 중심 Javadoc과 경계 정리**

  공개 Service·메서드·DTO에는 의미, 사전 조건, 트랜잭션 참여, 부작용, 실패 코드를
  기록한다. 내부 bucket ID나 원장 schema를 계약 설명으로 노출하지 않는다.

- [ ] **Step 4: 구조 및 메뉴홀드 집중 테스트**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.menuhold.*" --rerun-tasks`

  Expected: PASS.

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.menuhold.*" --rerun-tasks`

  Expected: PASS.

  Run: `.\gradlew.bat verifyIntegrationTestTags --rerun-tasks`

  Expected: 모든 통합 테스트가 `integration`과 shard 태그 하나를 가지며 PASS.

- [ ] **Step 5: 허용 파일만 stage하고 정리 커밋**

  Commit: `test(menu-hold): 픽업 수량 공개 경계 검증`

---

### Task 5: 전체 검증과 Draft PR

**Files:**
- No new production files.
- PR body only on GitHub after local verification.

- [ ] **Step 1: Wrapper와 전체 backend clean build 실행**

  Run: `.\gradlew.bat --version`

  Expected: Gradle 9.6.1, exit 0.

  Run: `.\gradlew.bat clean build --rerun-tasks`

  Expected: `BUILD SUCCESSFUL`, exit 0.

- [ ] **Step 2: 최종 범위와 공백 검사**

  Run: `git diff --check origin/dev...HEAD`

  Expected: no output.

  Run: `git diff --name-only origin/dev...HEAD`

  Expected: Issue #108의 menuhold source/test와 승인된 두 design/plan artifact만 출력된다.

  Run: `git status --short`

  Expected: `.idea/`만 미추적이고 staged index는 비어 있다.

- [ ] **Step 3: 브랜치 push와 Draft PR 생성**

  Push: `git push -u origin feature/108-pickup-inventory-contract`

  PR title: `feat(menu-hold): 픽업 수량 공개 계약 구현`

  PR link: `Refs #108`, `Blocks #43`를 명시한다. 실제 명령 결과, 실행하지 않은 검사,
  위험, rollback, 다른 도메인 직접 의존 0건과 Human 이해도 질문을 저장소 PR template에
  맞춰 기록한다.

## Self-Review Result

- Issue #108의 조회·확보·복구·트랜잭션·operation ID·fixture·오류·검증 조건이 Task 1~5에 모두 대응한다.
- 공개 타입 이름과 메서드 signature는 모든 Task에서 동일하다.
- production fake, HTTP API, Pickup aggregate, 새 migration 또는 내부 영속 타입 노출 단계가 없다.
- 각 production 동작은 test 작성 → 의도한 RED 확인 → 최소 구현 → GREEN 확인 순서다.
- 설계·공개 계약·조회·명령·구조 검증이 서로 다른 한글 커밋으로 분리된다.
