# Store Transaction Eligibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reservation과 Pickup이 `storeId`만으로 Store의 신규 거래 자격을 검증하는 목적별 공개 Service·DTO 계약을 추가한다.

**Architecture:** `StoreTransactionEligibilityService`가 비잠금 단건 Store 조회 후 승인·운영·목적별 mode·Pickup 중앙 자격을 fail-closed로 검증한다. 성공 시 목적별 marker DTO를 반환하고 외부 공개 형식에는 Store Entity·Repository·관리 DTO·내부 enum이 나타나지 않는다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, AssertJ, Mockito, Gradle

## Global Constraints

- 기준은 PR #86 병합 후 최신 `origin/dev` 커밋 `5e94791`이다.
- #85/PR #86의 `MenuTransactionEligibility`, Menu Entity·Repository와 잠금 기반 메뉴 거래 메서드는 변경하지 않는다.
- #43, #48, #49 소비 코드는 구현하지 않는다.
- Store Entity·Repository·enum·오류 코드를 변경하지 않는다.
- 조회는 `@Transactional(readOnly = true)`이며 잠금·저장·상태 변경을 만들지 않는다.
- 기존 `STORE_001/005/007/008`만 재사용한다.
- 사용자 지시에 따라 commit, push, PR을 만들지 않는다.

---

### Task 1: 목적별 거래 자격 서비스 행동

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreTransactionEligibilityServiceTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreTransactionEligibilityService.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreReservationTransactionEligibility.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StorePickupTransactionEligibility.java`

**Interfaces:**
- Consumes: `StoreRepository.findById(Long)`, `StoreErrorCode.STORE_NOT_FOUND`, `VERIFICATION_STATE_CONFLICT`, `STORE_STATE_CONFLICT`, `PICKUP_NOT_ELIGIBLE`
- Produces: `requireReservationTransactionEligibility(long)`, `requirePickupTransactionEligibility(long)`과 목적별 DTO

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

  다음 테스트를 작성한다. 각 오류 assertion은 `ServiceException.getErrorCode()`의 실제 값을 확인하고, 성공 assertion은 literal `storeId`를 확인한다.

  - 매장 없음 → `STORE_NOT_FOUND`
  - 승인 상태 아님 → `VERIFICATION_STATE_CONFLICT`
  - `TEMPORARILY_CLOSED` → `STORE_STATE_CONFLICT`
  - `CLOSED` → `STORE_STATE_CONFLICT`
  - 예약 mode 비활성 → `STORE_STATE_CONFLICT`
  - Pickup mode 비활성 → `STORE_STATE_CONFLICT`
  - Pickup 중앙 자격 없음 → `PICKUP_NOT_ELIGIBLE`
  - 예약 성공 → `new StoreReservationTransactionEligibility(STORE_ID)`
  - Pickup 성공 → `new StorePickupTransactionEligibility(STORE_ID)`

- [ ] **Step 2: RED 확인**

  Run:

  ```powershell
  .\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreTransactionEligibilityServiceTest" --no-daemon --max-workers=1
  ```

  Expected: 새 Service·DTO 타입이 없어 `compileTestJava`가 실패한다.

- [ ] **Step 3: 최소 DTO와 서비스 구현**

  두 DTO는 `long storeId`만 가진 public record로 작성한다. 서비스는 public class와 package-private Repository 생성자를 사용한다. 두 공개 메서드는 `@Transactional(readOnly = true)`로 선언하고 `findById` 조회 후 설계 문서의 순서대로 오류를 던진다.

- [ ] **Step 4: GREEN 확인**

  Run:

  ```powershell
  .\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreTransactionEligibilityServiceTest" --no-daemon --max-workers=1
  ```

  Expected: 서비스 테스트 전부 PASS.

- [ ] **Step 5: 범위와 diff 확인**

  Run:

  ```powershell
  git status --short
  git diff -- backend/src/main/java/com/miriyum/domain/store/core backend/src/test/java/com/miriyum/domain/store/core
  ```

  Expected: 새 Service·DTO·테스트 외 production 변경 없음.

### Task 2: 외부 소비 공개 경계

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/store/core/contract/StoreTransactionEligibilityPublicContractTest.java`
- Verify: Task 1의 Service·DTO 세 파일

**Interfaces:**
- Consumes: Task 1이 만든 두 공개 메서드와 두 DTO
- Produces: Repository 없이 컴파일 가능한 외부 소비 경계의 회귀 테스트

- [ ] **Step 1: 실패하는 공개 경계 테스트 작성**

  외부 소비자 fixture가 `StoreTransactionEligibilityService`와 목적별 DTO만 타입으로 사용하도록 작성한다. Reflection으로 두 public 메서드가 각각 `long` 하나만 받고 목적별 DTO를 반환하는지, DTO record component가 `storeId` 하나인지, public constructor가 Repository를 노출하지 않는지 확인한다.

- [ ] **Step 2: RED 또는 기존 구현의 계약 차이 확인**

  Run:

  ```powershell
  .\gradlew.bat test --tests "com.miriyum.domain.store.core.contract.StoreTransactionEligibilityPublicContractTest" --no-daemon --max-workers=1
  ```

  Expected: 구현이 경계 조건을 아직 만족하지 않으면 해당 literal signature assertion이 실패한다. 이미 최소 구현이 만족하면 테스트를 mutation-check하여 public constructor 또는 DTO 내부 enum 추가가 실제로 실패를 만드는지 확인한다.

- [ ] **Step 3: 필요한 최소 경계 조정**

  Service의 Repository 생성자가 public이면 package-private로 낮춘다. 공개 메서드·DTO에서 Entity·Repository·`ManagedStoreResponse`·내부 enum이 발견되면 제거한다.

- [ ] **Step 4: 공개 경계 GREEN 확인**

  Run:

  ```powershell
  .\gradlew.bat test --tests "com.miriyum.domain.store.core.contract.StoreTransactionEligibilityPublicContractTest" --no-daemon --max-workers=1
  ```

  Expected: 공개 경계 테스트 전부 PASS.

### Task 3: 집중·전체 회귀 검증

**Files:**
- Verify: 이 계획에서 추가된 모든 파일

**Interfaces:**
- Consumes: Task 1·2의 최종 계약
- Produces: 완료 판단을 위한 Gradle·Git 증거

- [ ] **Step 1: Store Core 집중 테스트 실행**

  Run:

  ```powershell
  .\gradlew.bat test --tests "com.miriyum.domain.store.core.*" --no-daemon --max-workers=1
  ```

  Expected: PASS, failures 0.

- [ ] **Step 2: 전체 backend 테스트 실행**

  Run:

  ```powershell
  .\gradlew.bat clean test --no-daemon --max-workers=1
  ```

  Expected: `BUILD SUCCESSFUL`, failures 0.

- [ ] **Step 3: 결과 XML 합계 확인**

  `backend/build/test-results/test/TEST-*.xml`의 tests, failures, errors, skipped 합계를 계산한다. failures와 errors가 모두 0이어야 한다.

- [ ] **Step 4: 형식·범위 확인**

  Run:

  ```powershell
  git diff --check
  git status --short
  git diff --name-only origin/dev...HEAD
  git diff --name-only
  ```

  Expected: 공백 오류 없음. #85와 #43/#48/#49 소비 파일 변경 없음. commit은 만들지 않으므로 구현 파일은 working tree 변경으로 남는다.
