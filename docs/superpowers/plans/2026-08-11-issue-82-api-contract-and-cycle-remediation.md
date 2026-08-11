# Issue #82 API Contract and Cycle Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자 유형별 API를 `/api/v1/consumers/**`와 `/api/v1/store-operators/**`로 통일하고, URL 종속 계약과 기존 도메인 순환을 함께 제거한다.

**Architecture:** 기능별 Controller·OpenAPI·Service 소유권은 유지하면서 namespace 문자열과 그 파생 계약을 원자적으로 바꾼다. Reservation은 MenuHold 협력을 자기 소유 Port로 역전하고 MenuHold adapter가 이를 구현하며, 예약 시간 판정은 Reservation의 좁은 Service로 추출한다. 기능별 OpenAPI는 단일 원본으로 유지하고 공개·consumer·store-operator 진입 파일은 `$ref`만 소유한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security, JUnit 5, Mockito, MockMvc, Gradle 9.6.1, OpenAPI 3.1, Node.js 24, pnpm 11.17.0, openapi-typescript 6.7.6

## Global Constraints

- 기준 브랜치는 최신 `dev`, 작업 브랜치는 `feature/82-api-contract-restructure`다.
- Issue #82의 2단계와 승인 설계만 구현한다.
- 공개 `/api/v1/stores/**`·catalog 경로, JSON 형상, 오류 코드, 권한 정책, DB schema와 Flyway는 바꾸지 않는다.
- 구 URL 호환 Controller·redirect·이중 mapping을 만들지 않는다.
- 일반 사용자 전용 경로는 `/api/v1/consumers/**`, 매장 운영자 전용 경로는 `/api/v1/store-operators/**`만 사용한다.
- 다른 도메인의 Entity·Repository를 직접 import하지 않는다.
- 새 production 동작은 실패하는 테스트를 먼저 관찰한 뒤 최소 구현으로 통과시킨다.
- frontend 기능 코드는 수정하지 않는다. OpenAPI 변경으로 생성되는 `frontend/src/shared/api/generated/*.ts`만 generator 출력 그대로 갱신한다.
- 제품·아키텍처·품질 사실은 활성 정본만 갱신하며 이 계획 문서를 완료 증거로 사용하지 않는다.

---

### Task 1: Consumer namespace, JWT와 쿠키 계약

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/consumer/controller/auth/ConsumerAuthControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/consumer/controller/account/ConsumerAccountControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/consumer/ReservationControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/pickup/controller/consumer/PickupReservationControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/auth/logindelay/LoginDelayHttpTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/auth/ratelimit/RateLimitFilterTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/controller/auth/ConsumerAuthController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/controller/account/ConsumerAccountController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/consumer/ReservationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/controller/consumer/PickupReservationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/auth/jwt/TokenNamespace.java`
- Modify: `backend/src/main/java/com/miriyum/domain/auth/ratelimit/RateLimitFilter.java`
- Modify: `backend/src/main/java/com/miriyum/global/security/SecurityConfig.java`

**Interfaces:**
- Consumes: `TokenNamespace.CONSUMER`, 기존 Consumer 인증·계정·예약·픽업 Service와 DTO
- Produces: `/api/v1/consumers/auth/**`, `/api/v1/consumers/me/**`, `/api/v1/consumers/reservations/**`, `/api/v1/consumers/pickup-reservations/**`, consumer cookie Path `/api/v1/consumers/auth`

- [ ] **Step 1: 새 consumer URL과 구 URL 거부 테스트를 작성한다**

각 기존 MockMvc 테스트의 성공 URL을 새 namespace로 바꾸고 대표 구 URL이 더 이상 해당 Controller에서 성공하지 않는 검사를 추가한다.

```java
private static final String AUTH_ROOT = "/api/v1/consumers/auth";
private static final String ACCOUNT_ROOT = "/api/v1/consumers";
private static final String RESERVATION_ROOT = "/api/v1/consumers/reservations";
private static final String PICKUP_ROOT = "/api/v1/consumers/pickup-reservations";

@Test
void doesNotMapLegacyConsumerAuthRoot() throws Exception {
    mockMvc.perform(post("/api/v1/consumer-auth/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson()))
            .andExpect(status().isNotFound());
}
```

로그인·재발급·CSRF·로그아웃 테스트의 `Set-Cookie` assertion은 다음 Path를 고정한다.

```java
.andExpect(header().string(HttpHeaders.SET_COOKIE,
        containsString("Path=/api/v1/consumers/auth")))
```

- [ ] **Step 2: consumer Controller 테스트가 예상한 이유로 실패하는지 확인한다**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.consumer.controller.*" --tests "com.miriyum.domain.reservation.controller.consumer.ReservationControllerTest" --tests "com.miriyum.domain.pickup.controller.consumer.PickupReservationControllerTest" --console=plain
```

Expected: 새 URL은 `404` 또는 Security 미매칭으로 실패하고 쿠키 Path는 기존 `/api/v1/consumer-auth`라 assertion이 실패한다.

- [ ] **Step 3: consumer Controller base path와 cookie Path를 최소 변경한다**

다음 mapping을 적용한다.

```java
// ConsumerAuthController
@RequestMapping("/api/v1/consumers/auth")

// ConsumerAccountController
@RequestMapping("/api/v1/consumers")

// ReservationController
@RequestMapping("/api/v1/consumers/reservations")

// PickupReservationController
@RequestMapping("/api/v1/consumers/pickup-reservations")
```

`TokenNamespace.CONSUMER`의 cookie path를 다음 값으로 바꾼다.

```java
CONSUMER(
        "consumer",
        "MIRIYUM_CONSUMER_REFRESH",
        "MIRIYUM_CONSUMER_XSRF_TOKEN",
        "/api/v1/consumers/auth"
)
```

`SecurityConfig`와 auth rate-limit/login-delay 경로도 `/api/v1/consumers/auth/**`를 사용한다.

- [ ] **Step 4: consumer namespace 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.consumer.controller.*" --tests "com.miriyum.domain.reservation.controller.consumer.ReservationControllerTest" --tests "com.miriyum.domain.pickup.controller.consumer.PickupReservationControllerTest" --tests "com.miriyum.domain.auth.logindelay.LoginDelayHttpTest" --tests "com.miriyum.domain.auth.ratelimit.RateLimitFilterTest" --console=plain
```

Expected: PASS. 새 consumer URL과 cookie Path assertion이 통과하고 대표 구 URL은 허용되지 않는다.

- [ ] **Step 5: Task 1을 커밋한다**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor(api): 일반 사용자 경로를 consumers로 통일"
```

---

### Task 2: Store-operator namespace와 Security matcher 계약

**Files:**
- Create: `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/storeoperator/controller/auth/StoreOperatorAuthControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/storeoperator/controller/account/StoreOperatorAccountControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/MenuControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/schedule/controller/storeoperator/StoreScheduleControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/schedule/controller/storeoperator/StoreClosureControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/storeoperator/ReservationCapacityControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/storeoperator/ReservationTimePolicyControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/storeoperator/StoreReservationControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/controller/storeoperator/MenuInventoryAdminControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/pickup/controller/storeoperator/PickupStoreManagementControllerTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/controller/auth/StoreOperatorAuthController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/controller/account/StoreOperatorAccountController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menu/controller/storeoperator/MenuController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/schedule/controller/storeoperator/StoreScheduleController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/schedule/controller/storeoperator/StoreClosureController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/storeoperator/ReservationCapacityController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/storeoperator/ReservationTimePolicyController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/storeoperator/StoreReservationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/controller/storeoperator/MenuInventoryAdminController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/controller/storeoperator/PickupStoreManagementController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/auth/jwt/TokenNamespace.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/config/StoreManagementSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/config/PickupSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/global/security/SecurityConfig.java`

**Interfaces:**
- Consumes: `TokenNamespace.STORE_OPERATOR`, 기존 운영자 Service·DTO와 store ownership 검증
- Produces: `/api/v1/store-operators/auth/**`, `/api/v1/store-operators/me/**`, `/api/v1/store-operators/stores/**`, 운영자 cookie Path `/api/v1/store-operators/auth`

- [ ] **Step 1: 운영자 새 URL·구 URL 거부·교차 JWT 테스트를 먼저 바꾼다**

운영자 Controller 테스트의 base path를 다음 상수로 바꾸고 구 singular 경로를 대표적으로 거부한다.

```java
private static final String OPERATOR_ROOT = "/api/v1/store-operators";
private static final String STORE_ROOT = OPERATOR_ROOT + "/stores";

@Test
void consumerTokenCannotUseStoreOperatorRoute() throws Exception {
    mockMvc.perform(get(STORE_ROOT + "/7")
                    .header(HttpHeaders.AUTHORIZATION, consumerBearerToken()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_004"));
}
```

StoreOperator auth 테스트는 `Path=/api/v1/store-operators/auth`를 기대한다.

`HttpApiNamespaceContractTest`는 `src/main/java/com/miriyum/domain` 아래 Controller source의 class-level `@RequestMapping`을 수집해 구 root가 없고 audience package의 root가 일치하는지 검사한다.

```java
private static final Set<String> LEGACY_ROOTS = Set.of(
        "/api/v1/consumer-auth",
        "/api/v1/consumer-accounts",
        "/api/v1/reservations",
        "/api/v1/pickup-reservations",
        "/api/v1/store-operator-auth",
        "/api/v1/store-operator-accounts",
        "/api/v1/store-operator");

@Test
void audienceControllersUseOnlyCanonicalRoots() {
    List<ControllerMapping> mappings = controllerMappings();
    assertThat(mappings).isNotEmpty();
    assertThat(mappings).noneMatch(mapping -> LEGACY_ROOTS.stream()
            .anyMatch(legacy -> mapping.path().equals(legacy)
                    || mapping.path().startsWith(legacy + "/")));
    assertThat(mappings.stream().filter(ControllerMapping::isConsumer))
            .allMatch(mapping -> mapping.path().startsWith("/api/v1/consumers"));
    assertThat(mappings.stream().filter(ControllerMapping::isStoreOperator))
            .allMatch(mapping -> mapping.path().startsWith("/api/v1/store-operators"));
}
```

`isConsumer`는 top-level `consumer`의 `controller.auth|account`와 `controller.consumer` package를, `isStoreOperator`는 top-level `storeoperator`의 `controller.auth|account`와 `controller.storeoperator` package를 판별한다. `controller.publicapi`는 이 두 assertion 대상에서 제외한다.

- [ ] **Step 2: 운영자 관련 slice와 Security 테스트의 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.architecture.HttpApiNamespaceContractTest" --tests "com.miriyum.domain.storeoperator.controller.*" --tests "com.miriyum.domain.store.controller.storeoperator.*" --tests "com.miriyum.domain.menu.controller.storeoperator.*" --tests "com.miriyum.domain.schedule.controller.storeoperator.*" --tests "com.miriyum.domain.reservation.controller.storeoperator.*" --tests "com.miriyum.domain.menuhold.controller.storeoperator.*" --tests "com.miriyum.domain.pickup.controller.storeoperator.*" --console=plain
```

Expected: 새 plural namespace mapping과 cookie Path가 아직 없어 실패한다.

- [ ] **Step 3: 운영자 Controller와 matcher를 plural namespace로 바꾼다**

```java
@RequestMapping("/api/v1/store-operators/auth")
@RequestMapping("/api/v1/store-operators")
@RequestMapping("/api/v1/store-operators/stores")
@RequestMapping("/api/v1/store-operators/stores/{storeId}")
```

각 Controller의 기존 상대 path는 유지한다. Security matcher 상수와 `securityMatcher`/`requestMatchers`는 `/api/v1/store-operators/**`를 사용한다. `TokenNamespace.STORE_OPERATOR.cookiePath()`는 `/api/v1/store-operators/auth`로 바꾼다.

- [ ] **Step 4: 운영자 관련 테스트를 통과시킨다**

Run the Task 2 Step 2 command again.

Expected: PASS. 정상 운영자 토큰만 접근하고 consumer 토큰은 `AUTH_004`, 대표 구 URL은 비허용이다.

- [ ] **Step 5: Task 2를 커밋한다**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor(api): 매장 운영자 경로를 plural namespace로 통일"
```

---

### Task 3: 새 HTTP 경로로 멱등성 fingerprint 정렬

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreCommandFingerprintTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/schedule/service/StoreScheduleFingerprintTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacadeTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacadeTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationFulfillmentIT.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/controller/account/ConsumerAccountController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/controller/account/StoreOperatorAccountController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreCommandFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/schedule/service/StoreScheduleFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacade.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCapacityPublicationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationFulfillmentCommandFacade.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/service/PickupReservationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/service/PickupStoreManagementService.java`

**Interfaces:**
- Consumes: 새 consumer/operator canonical HTTP paths, 기존 `RequestFingerprint.of(String)`
- Produces: 새 URL을 포함하면서 body normalization·actor·operation semantics가 동일한 fingerprint

- [ ] **Step 1: fingerprint 기대값을 새 namespace로 바꾼다**

대표 기대값은 다음과 같다.

```java
assertThat(fingerprint).startsWith("POST|/api/v1/store-operators/stores|");
assertThat(fingerprint).startsWith("POST|/api/v1/consumers/reservations|");
assertThat(fingerprint).contains("/api/v1/store-operators/stores/{storeId}/reservations/");
```

Account Controller 테스트의 captured `IdempotencyCommand.fingerprint()`도 `/api/v1/consumers/me`, `/api/v1/store-operators/me`를 기대하게 한다.

- [ ] **Step 2: fingerprint 테스트의 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "*FingerprintTest" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest" --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentCommandFacadeTest" --console=plain
```

Expected: production canonical 문자열이 구 URL이라 실패한다.

- [ ] **Step 3: production canonical route 문자열만 교체한다**

예:

```java
new StringBuilder("POST|/api/v1/store-operators/stores|");
new StringBuilder("POST|/api/v1/consumers/reservations|");
private static final String UPDATE_ROUTE = "PATCH /api/v1/consumers/me";
```

method, path parameter 표기, 정렬 순서, body field와 delimiter는 바꾸지 않는다.

- [ ] **Step 4: unit 및 DB fingerprint 회귀를 검증한다**

Run:

```powershell
.\gradlew.bat test --tests "*FingerprintTest" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest" --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentCommandFacadeTest" --console=plain
.\gradlew.bat integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --tests "com.miriyum.domain.reservation.service.ReservationFulfillmentIT" --console=plain
```

Expected: PASS. Docker를 사용할 수 없으면 두 번째 명령은 `BLOCKED` 근거를 보존하고 최종 CI에서 다시 확인한다.

- [ ] **Step 5: 구 URL fingerprint 잔존을 검사하고 커밋한다**

```powershell
rg -n 'api/v1/(consumer-auth|consumer-accounts|reservations|pickup-reservations|store-operator-auth|store-operator-accounts|store-operator)' backend/src/main/java
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor(api): 멱등 지문을 새 경로와 정렬"
```

Expected: `rg` 결과가 없다.

---

### Task 4: 예약 내역 HTTP 경계를 Reservation으로 이동

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/consumer/controller/account/ConsumerAccountControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/consumer/ReservationControllerTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/controller/account/ConsumerAccountController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/consumer/ReservationController.java`

**Interfaces:**
- Consumes: `ConsumerAccountService.getMe(long)`, `ReservationService.getConsumerReservationHistory(long, ReservationHistorySearchRequest)`
- Produces: Reservation 소유 `GET /api/v1/consumers/me/reservations`, `consumer → reservation` import 제거

- [ ] **Step 1: Reservation Controller에 예약 내역 계약 테스트를 옮긴다**

`ConsumerAccountControllerTest`의 예약 내역 사례를 `ReservationControllerTest`로 이동하고 다음 순서를 검증한다.

```java
@Test
void getsReservationHistoryAfterCheckingActiveConsumer() throws Exception {
    mockMvc.perform(get("/api/v1/consumers/me/reservations")
                    .with(authentication(consumerAuthentication())))
            .andExpect(status().isOk());

    InOrder inOrder = inOrder(consumerAccountService, reservationService);
    inOrder.verify(consumerAccountService).getMe(CONSUMER_ID);
    inOrder.verify(reservationService).getConsumerReservationHistory(
            eq(CONSUMER_ID), any(ReservationHistorySearchRequest.class));
}
```

ConsumerAccount Controller slice에서는 Reservation bean/import 없이 context가 생성되는지 검증한다.

- [ ] **Step 2: 이동 테스트의 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.consumer.controller.account.ConsumerAccountControllerTest" --tests "com.miriyum.domain.reservation.controller.consumer.ReservationControllerTest" --console=plain
```

Expected: Reservation Controller에 `/me/reservations` mapping과 `ConsumerAccountService` 주입이 없어 실패한다.

- [ ] **Step 3: method와 imports를 Reservation Controller로 이동한다**

`ReservationController`는 class-level `/api/v1/consumers`를 사용하고 예약 CRUD method에는 `/reservations` 상대 path를 붙인다. history method는 `/me/reservations`를 사용한다.

```java
@RestController
@RequestMapping("/api/v1/consumers")
@RequiredArgsConstructor
class ReservationController {
    private final ReservationService reservationService;
    private final ReservationCreationCommandFacade reservationCreationCommandFacade;
    private final ReservationCancellationCommandFacade reservationCancellationCommandFacade;
    private final ConsumerAccountService consumerAccountService;

    @GetMapping("/me/reservations")
    ApiResponse<ReservationHistoryPageResponse> getReservationHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        consumerAccountService.getMe(principal.accountId());
        ReservationHistorySearchRequest query =
                ReservationHistorySearchRequest.from(status, page, size, sort);
        return ApiResponse.success("조회했습니다.",
                reservationService.getConsumerReservationHistory(principal.accountId(), query));
    }

    @PostMapping("/reservations")
    ResponseEntity<ApiResponse<ReservationDetailResponse>> createReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ReservationCreateRequest request
    ) {
        ReservationCreationCommandResult result = reservationCreationCommandFacade.create(
                principal.accountId(), IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약이 생성되었습니다.", result.data()));
    }

    @GetMapping("/reservations/{reservationId}")
    ApiResponse<ReservationDetailResponse> getReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationId
    ) {
        ReservationDetailResponse response = reservationService.getConsumerReservation(
                principal.accountId(), reservationId);
        return ApiResponse.success("조회되었습니다.", response);
    }

    @PostMapping("/reservations/{reservationId}/cancellations")
    ResponseEntity<ApiResponse<ReservationDetailResponse>> cancelReservation(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long reservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @Valid @RequestBody ConsumerCancellationRequest request
    ) {
        ReservationCancellationCommandResult result =
                reservationCancellationCommandFacade.cancelByConsumer(
                        principal.accountId(), reservationId,
                        IdempotencyKey.parse(rawKey), request);
        return ResponseEntity.status(result.httpStatus())
                .body(ApiResponse.success("예약이 취소되었습니다.", result.data()));
    }
}
```

ConsumerAccount Controller에서 Reservation DTO·Service imports, field와 history method를 삭제한다.

- [ ] **Step 4: Controller와 import 방향을 검증한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.consumer.controller.account.ConsumerAccountControllerTest" --tests "com.miriyum.domain.reservation.controller.consumer.ReservationControllerTest" --console=plain
rg -n 'com\.miriyum\.domain\.reservation' backend/src/main/java/com/miriyum/domain/consumer
```

Expected: tests PASS, `rg` 결과 없음.

- [ ] **Step 5: Task 4를 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/consumer backend/src/main/java/com/miriyum/domain/reservation/controller backend/src/test/java/com/miriyum/domain/consumer backend/src/test/java/com/miriyum/domain/reservation/controller
git commit -m "refactor(reservation): 예약 내역 HTTP 경계 이동"
```

---

### Task 5: MenuTransactionService를 Facade로 승격

**Files:**
- Rename: `backend/src/main/java/com/miriyum/domain/menu/service/MenuTransactionService.java` → `backend/src/main/java/com/miriyum/domain/menu/service/MenuTransactionFacade.java`
- Rename: `backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionServiceTest.java` → `backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionFacadeTest.java`
- Rename: `backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionServiceIT.java` → `backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionFacadeIT.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuHoldServiceRuntime.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryAdminCommandService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/service/PickupReservationService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/inventory/MenuInventoryRuntimeIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldCreateServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldRuntimeIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldTerminalServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuInventoryAdminCommandServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/pickup/service/PickupCreationIntegrationTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/pickup/service/PickupReservationServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`

**Interfaces:**
- Consumes: `StoreTransactionEligibilityService`, `MenuRepository`
- Produces: `MenuTransactionFacade.requireTransactionEligibility(long storeId, long menuId)` with unchanged `MANDATORY` transaction and Store→Menu lock order

- [ ] **Step 1: 테스트 타입과 기대 bean 이름을 Facade로 먼저 바꾼다**

```java
class MenuTransactionFacadeTest {
    private MenuTransactionFacade facade;

    @BeforeEach
    void setUp() {
        facade = new MenuTransactionFacade(storeEligibilityService, menuRepository);
    }
}
```

통합 테스트도 실제 `MenuTransactionFacade` bean을 주입한다. 소비자 테스트 mock 역시 Facade 타입으로 바꾼다.

- [ ] **Step 2: compile RED를 확인한다**

Run:

```powershell
.\gradlew.bat compileTestJava --console=plain
```

Expected: `MenuTransactionFacade`가 아직 없어 compile 실패한다.

- [ ] **Step 3: production 타입을 rename하고 소비자를 전환한다**

```java
@Service
public class MenuTransactionFacade {
    private final StoreTransactionEligibilityService storeEligibilityService;
    private final MenuRepository menuRepository;

    public MenuTransactionFacade(
            StoreTransactionEligibilityService storeEligibilityService,
            MenuRepository menuRepository
    ) {
        this.storeEligibilityService = storeEligibilityService;
        this.menuRepository = menuRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MenuTransactionEligibility requireTransactionEligibility(long storeId, long menuId) {
        StoreMenuTransactionEligibility store =
                storeEligibilityService.requireMenuTransactionEligibility(storeId);
        Menu menu = menuRepository.findByIdForUpdate(menuId)
                .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
        if (menu.getStoreId() != store.storeId()) {
            throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
        }
        MenuVersion published = menu.requireTransactionVersion();
        boolean menuHoldEligible = store.reservationEnabled()
                && store.menuHoldEnabled()
                && published.isHoldSelectionAllowed();
        boolean pickupEligible = store.pickupEnabled()
                && published.isPickupSelectionAllowed();
        return new MenuTransactionEligibility(
                storeId,
                menuId,
                published.getVersionNumber(),
                published.getName(),
                published.getPrice(),
                menuHoldEligible,
                pickupEligible);
    }
}
```

새 pass-through Service를 만들지 않고 모든 production/test import와 field type을 새 이름으로 바꾼다.

- [ ] **Step 4: 거래 단위·통합 계약을 검증한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.menu.service.MenuTransactionFacadeTest" --tests "com.miriyum.domain.menuhold.service.*" --tests "com.miriyum.domain.pickup.service.PickupReservationServiceTest" --console=plain
.\gradlew.bat integrationTest --tests "com.miriyum.domain.menu.service.MenuTransactionFacadeIT" --tests "com.miriyum.domain.menuhold.inventory.MenuInventoryRuntimeIT" --tests "com.miriyum.domain.pickup.service.PickupCreationIntegrationTest" --console=plain
```

Expected: unit PASS, Docker 사용 가능 시 Store→Menu lock 및 rollback integration PASS.

- [ ] **Step 5: 구 타입 잔존을 검사하고 커밋한다**

```powershell
rg -n 'MenuTransactionService' backend/src/main backend/src/test
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor(menu): 공통 거래 서비스를 facade로 승격"
```

Expected: `rg` 결과 없음.

---

### Task 6: ReservationMenuHoldPort와 시간 판정 Service로 순환 역전

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/ReservationMenuHoldPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/dto/ReservationMenuHoldCreateCommand.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/dto/ReservationMenuHoldSelection.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/dto/ReservationMenuHoldResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/dto/ReservationMenuHoldItemSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/port/dto/ReservationMenuHoldTerminationPresence.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/adapter/ReservationMenuHoldAdapter.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationTimeResolutionService.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/adapter/ReservationMenuHoldAdapterTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationTimeResolutionServiceTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/dto/response/ReservationMenuSelectionResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuHoldAvailabilityQueryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldAvailabilityQueryServiceTest.java`

**Interfaces:**
- Consumes: 기존 `MenuHoldService`, `MenuHoldSnapshotQueryService`, Reservation 시간 정책·schedule 공개 계약
- Produces: Reservation 소유 Port, MenuHold adapter, `ReservationTimeResolutionService.resolveReservationTimes(List<Long>, ReservationTimeRequest)`, 최종 `menuhold → reservation` 단방향

- [ ] **Step 1: Reservation 소유 Port 계약 테스트를 작성한다**

Port는 Reservation이 실제 사용하는 다섯 연산만 제공한다.

```java
public interface ReservationMenuHoldPort {
    ReservationMenuHoldTerminationPresence lockForTermination(long reservationId);
    ReservationMenuHoldResult create(ReservationMenuHoldCreateCommand command);
    ReservationMenuHoldResult release(long reservationId, String operationId);
    ReservationMenuHoldResult fulfill(long reservationId, String operationId);
    List<ReservationMenuHoldItemSnapshot> findSnapshots(long reservationId);
}
```

Port DTO는 다음 필드와 enum을 고정한다.

```java
public record ReservationMenuHoldSelection(long menuId, int quantity) { }

public record ReservationMenuHoldCreateCommand(
        long reservationId,
        long storeId,
        long consumerAccountId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        Instant startAt,
        Instant serviceEndAt,
        String operationId,
        List<ReservationMenuHoldSelection> menuSelections
) { }

public record ReservationMenuHoldResult(
        long reservationId,
        Outcome outcome
) {
    public enum Outcome { NO_HOLD, CONFIRMED, RELEASED, FULFILLED }
}

public record ReservationMenuHoldItemSnapshot(
        long menuId,
        String menuName,
        long unitPrice,
        int quantity
) { }

public enum ReservationMenuHoldTerminationPresence {
    NO_HOLD,
    HOLD_PRESENT
}
```

각 record의 compact constructor는 현재 MenuHold 계약과 같은 양수 ID·수량, 비어 있지 않은 operation ID, 증가하는 local/instant 구간, null 금지와 중복 menu ID 수량 합산을 Reservation 소유 경계에서 검증한다. `ReservationMenuHoldResult`는 양수 reservation ID와 non-null outcome을 검증한다.

Adapter 테스트는 MenuHold DTO를 Port DTO로 변환하고 reservation ID, outcome, snapshot 순서와 오류 전파를 보존하는지 검증한다.

- [ ] **Step 2: Port·Adapter RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.menuhold.adapter.ReservationMenuHoldAdapterTest" --console=plain
```

Expected: Port와 adapter가 없어 compile/test 실패한다.

- [ ] **Step 3: Port DTO와 MenuHold adapter를 최소 구현한다**

`ReservationMenuHoldAdapter`는 `MenuHoldService`와 `MenuHoldSnapshotQueryService`만 주입받는다. 각 method에서 MenuHold command/result를 Reservation 소유 record로 변환한다. `ServiceException`은 변환하지 않고 그대로 전파한다.

```java
@Component
@RequiredArgsConstructor
public class ReservationMenuHoldAdapter implements ReservationMenuHoldPort {
    private final MenuHoldService menuHoldService;
    private final MenuHoldSnapshotQueryService snapshotQueryService;

    @Override
    public List<ReservationMenuHoldItemSnapshot> findSnapshots(long reservationId) {
        return snapshotQueryService.findByReservationId(reservationId).stream()
                .map(ReservationMenuHoldAdapter::toSnapshot)
                .toList();
    }
}
```

- [ ] **Step 4: ReservationService가 Port만 사용하도록 테스트와 production을 바꾼다**

`ReservationService`에서 모든 `com.miriyum.domain.menuhold.*` import, `MenuHoldService`와 `MenuHoldSnapshotQueryService` field를 제거하고 `ReservationMenuHoldPort` 하나를 주입한다. 생성·취소·이행·상세 응답의 값과 검증 순서는 유지한다.

```java
private final ReservationMenuHoldPort menuHoldPort;

ReservationMenuHoldResult result = menuHoldPort.create(command);
List<ReservationMenuHoldItemSnapshot> snapshots =
        menuHoldPort.findSnapshots(reservation.getId());
```

Reservation response DTO는 `ReservationMenuHoldItemSnapshot`을 받아 기존 JSON과 같은 `ReservationMenuSelectionResponse`로 변환한다.

- [ ] **Step 5: 시간 판정 추출 테스트를 작성하고 RED를 확인한다**

`ReservationServiceTest`의 `resolveReservationTimes` 사례를 `ReservationTimeResolutionServiceTest`로 이동한다. 동일 입력 순서, unavailable fail-closed, offset·timezone·운영 시간 검증을 유지한다.

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.service.ReservationTimeResolutionServiceTest" --console=plain
```

Expected: 새 Service가 없어 실패한다.

- [ ] **Step 6: 실제 시간 판정 로직을 새 Service로 이동한다**

`ReservationService.resolveReservationTimes`와 전용 private helper를 `ReservationTimeResolutionService`로 이동한다. Reservation 내부 소비자는 새 Service를 호출하고, MenuHoldAvailabilityQueryService도 `ReservationService` 대신 새 Service를 주입한다.

```java
@Service
@RequiredArgsConstructor
public class ReservationTimeResolutionService {
    private final StoreScheduleService storeScheduleService;
    private final StoreServiceIntervalValidationService storeServiceIntervalValidationService;
    private final ReservationTimePolicyVersionRepository timePolicyRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ReservationTimeResolutionResult> resolveReservationTimes(
            List<Long> storeIds,
            ReservationTimeRequest request
    ) {
        List<Long> candidates = validateRequest(storeIds, request);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<StoreReservationWindowResult> windows =
                storeScheduleService.resolveReservationWindows(
                        candidates, request.serviceDate(), request.startTime());
        if (!matchesInput(candidates, windows)) {
            return unavailableResults(candidates);
        }
        Set<Long> acceptingStoreIds = windows.stream()
                .filter(window -> window.status() == StoreReservationWindowStatus.ACCEPTING)
                .map(StoreReservationWindowResult::storeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (acceptingStoreIds.isEmpty()) {
            return unavailableResults(candidates);
        }
        Instant evaluatedAt = clock.instant();
        Map<Long, List<ReservationTimePolicyVersion>> policiesByStore = new HashMap<>();
        for (ReservationTimePolicyVersion policy :
                timePolicyRepository.findResolutionCandidatesByStoreIds(
                        acceptingStoreIds,
                        ReservationTimePolicyStatus.ACTIVE,
                        ReservationTimePolicyStatus.SCHEDULED,
                        evaluatedAt)) {
            policiesByStore.computeIfAbsent(policy.getStoreId(), ignored -> new ArrayList<>())
                    .add(policy);
        }
        LocalDateTime requestedAt = LocalDateTime.of(
                request.serviceDate(), request.startTime());
        List<ReservationTimeResolutionResult> provisionalResults =
                new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            long storeId = candidates.get(index);
            provisionalResults.add(resolveTime(
                    storeId,
                    windows.get(index),
                    singleEffectivePolicy(policiesByStore.get(storeId), storeId, evaluatedAt),
                    requestedAt,
                    request));
        }
        List<StoreServiceIntervalRequest> intervalRequests = provisionalResults.stream()
                .filter(result -> result.status() == ReservationTimeResolutionStatus.RESOLVED)
                .map(result -> new StoreServiceIntervalRequest(
                        result.storeId(), result.time().startAt(), result.time().serviceEndAt()))
                .toList();
        if (intervalRequests.isEmpty()) {
            return List.copyOf(provisionalResults);
        }
        List<StoreServiceIntervalResult> intervalResults =
                storeServiceIntervalValidationService.validateServiceIntervals(intervalRequests);
        if (!matchesServiceIntervals(intervalRequests, intervalResults)) {
            return unavailableResults(candidates);
        }
        List<ReservationTimeResolutionResult> results = new ArrayList<>(candidates.size());
        int intervalIndex = 0;
        for (ReservationTimeResolutionResult provisionalResult : provisionalResults) {
            if (provisionalResult.status() != ReservationTimeResolutionStatus.RESOLVED) {
                results.add(provisionalResult);
                continue;
            }
            StoreServiceIntervalResult intervalResult = intervalResults.get(intervalIndex++);
            results.add(intervalResult.status() == StoreServiceIntervalStatus.ACCEPTING
                    ? provisionalResult
                    : ReservationTimeResolutionResult.unavailable(
                            provisionalResult.storeId()));
        }
        return List.copyOf(results);
    }
}
```

이 body가 사용하는 전용 private helper `validateRequest(List<Long>, ReservationTimeRequest)`, `matchesInput`, `singleEffectivePolicy`, `resolveTime`, `isSlotAligned`, `unavailableResults`, `matchesServiceIntervals`도 구현과 테스트를 바꾸지 않고 같은 클래스로 이동한다. `ReservationService`에 남아 있는 availability 전용 overload와 `CapacityWindow` helper는 이동하지 않는다.

MenuHold의 status·time·offset 검증과 기존 `SERVICE_UNAVAILABLE`, `OUTSIDE_RESERVATION_WINDOW` 동작은 바꾸지 않는다.

- [ ] **Step 7: Port/Adapter와 시간 판정 회귀를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.menuhold.adapter.ReservationMenuHoldAdapterTest" --tests "com.miriyum.domain.reservation.service.ReservationTimeResolutionServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.menuhold.service.MenuHoldAvailabilityQueryServiceTest" --console=plain
rg -n 'com\.miriyum\.domain\.menuhold' backend/src/main/java/com/miriyum/domain/reservation
```

Expected: tests PASS, Reservation production source의 MenuHold import 없음.

- [ ] **Step 8: Task 6을 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation backend/src/main/java/com/miriyum/domain/menuhold backend/src/test/java/com/miriyum/domain/reservation backend/src/test/java/com/miriyum/domain/menuhold
git commit -m "refactor(reservation): menu hold 협력을 port로 역전"
```

---

### Task 7: 순환 baseline을 0건으로 고정

**Files:**
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

**Interfaces:**
- Consumes: Task 4와 Task 6의 최종 domain import graph
- Produces: cyclic domain pair와 cyclic edge 기대값 모두 `Set.of()`

- [ ] **Step 1: 구조 테스트 기대값을 빈 집합으로 바꾼다**

```java
private static final Set<DomainPair> ALLOWED_CYCLIC_DOMAIN_PAIRS = Set.of();
private static final Set<DependencyEdge> ALLOWED_CYCLIC_EDGES = Set.of();

@Test
void domainsDoNotHaveDependencyCycles() {
    Map<String, Set<String>> dependencies = domainDependencies(javaSources());
    assertThat(cyclicDomainPairs(dependencies)).isEmpty();
    assertThat(cyclicDependencyEdges(dependencies)).isEmpty();
}
```

장거리 순환 반례 테스트는 유지하되 이름을 `cycleDetectionFindsALongCycle`로 바꾸고 baseline 용어를 제거한다.

- [ ] **Step 2: 구조 테스트를 실행한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.architecture.DomainPackageArchitectureTest" --console=plain
```

Expected: PASS. 실패하면 출력된 실제 edge의 소유 방향을 Task 4/6 계약에 맞게 고치고 테스트 허용 목록을 늘리지 않는다.

- [ ] **Step 3: Task 7을 커밋한다**

```powershell
git add -- backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java
git commit -m "test(global): 도메인 순환 baseline 제거"
```

---

### Task 8: 기능별 OpenAPI와 소비자별 진입점

**Files:**
- Create: `docs/specs/public-openapi.yaml`
- Create: `docs/specs/consumer-openapi.yaml`
- Create: `docs/specs/store-operator-openapi.yaml`
- Create: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- Modify: `docs/specs/auth-account/openapi.yaml`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/specs/reservation/openapi.yaml`
- Modify: `docs/specs/menu-hold-pickup/openapi.yaml`
- Modify: `docs/specs/mvp1-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/MenuOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/controller/storeoperator/MenuInventoryOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/pickup/PickupOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/schedule/controller/storeoperator/StoreClosureOpenApiContractTest.java`
- Modify: `frontend/src/shared/api/generated/auth-account.ts`
- Modify: `frontend/src/shared/api/generated/store-search.ts`
- Modify: `frontend/src/shared/api/generated/reservation.ts`
- Modify: `frontend/src/shared/api/generated/menu-hold-pickup.ts`

**Interfaces:**
- Consumes: 기능별 OpenAPI path item과 common components
- Produces: 공개·consumer·store-operator 진입 명세, 세 진입점의 합과 동일한 `mvp1-openapi.yaml`, generator와 동기화된 TypeScript 계약

- [ ] **Step 1: 소비자별 path 집합 계약 테스트를 작성한다**

`AudienceOpenApiContractTest`는 YAML `paths` key를 읽어 다음을 검사한다.

```java
@Test
void audienceEntrypointsPartitionTheAggregatePaths() {
    Set<String> publicPaths = paths("docs/specs/public-openapi.yaml");
    Set<String> consumerPaths = paths("docs/specs/consumer-openapi.yaml");
    Set<String> operatorPaths = paths("docs/specs/store-operator-openapi.yaml");
    Set<String> aggregatePaths = paths("docs/specs/mvp1-openapi.yaml");

    assertThat(consumerPaths).allMatch(path -> path.startsWith("/api/v1/consumers/"));
    assertThat(operatorPaths).allMatch(path -> path.startsWith("/api/v1/store-operators/"));
    assertThat(intersection(publicPaths, consumerPaths)).isEmpty();
    assertThat(intersection(publicPaths, operatorPaths)).isEmpty();
    assertThat(intersection(consumerPaths, operatorPaths)).isEmpty();
    assertThat(union(publicPaths, consumerPaths, operatorPaths))
            .isEqualTo(aggregatePaths);
}
```

추가 assertion으로 모든 path item이 `$ref` 하나만 소유하고, 구 URL prefix가 세 파일과 aggregate에 없음을 확인한다.

- [ ] **Step 2: OpenAPI partition 테스트의 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.architecture.AudienceOpenApiContractTest" --console=plain
```

Expected: 세 진입 파일이 없어 실패한다.

- [ ] **Step 3: 기능별 원본 URL을 새 namespace로 바꾼다**

`auth-account`, `store-search`, `reservation`, `menu-hold-pickup`의 consumer/operator path key와 내부 path `$ref`를 새 URL로 바꾼다. operationId, schema, response와 error reference는 보존한다.

- [ ] **Step 4: 소비자별 진입점과 aggregate 참조를 작성한다**

예:

```yaml
# docs/specs/consumer-openapi.yaml
openapi: 3.1.0
info:
  title: MiriYum MVP1 Consumer API
  version: 1.0.0
paths:
  /api/v1/consumers/reservations:
    $ref: "./reservation/openapi.yaml#/paths/~1api~1v1~1consumers~1reservations"
```

`mvp1-openapi.yaml`의 각 path는 대응 audience file을 참조한다.

```yaml
/api/v1/consumers/reservations:
  $ref: "./consumer-openapi.yaml#/paths/~1api~1v1~1consumers~1reservations"
```

- [ ] **Step 5: 기존 기능별 OpenAPI 계약 테스트를 새 path로 갱신한다**

모든 `ContractTest`의 path key와 `$ref` JSON pointer를 새 namespace로 바꾼다. 공개 Store Search 경로 assertion은 변경하지 않는다.

- [ ] **Step 6: OpenAPI 계약 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "*OpenApiContractTest" --tests "com.miriyum.architecture.AudienceOpenApiContractTest" --console=plain
```

Expected: PASS. audience 집합이 중복 없이 aggregate 전체와 일치한다.

- [ ] **Step 7: TypeScript 계약을 generator로 갱신한다**

Run from `frontend/`:

```powershell
pnpm install --frozen-lockfile
pnpm run generate:api
pnpm run typecheck
pnpm test
pnpm run build
```

Expected: 네 generated 기능 파일만 URL key 변경을 포함하고 typecheck·test·build가 PASS한다. `src/shared/api/generated/common.ts`에 semantic diff가 없어야 하며 frontend 기능 source에는 diff가 없어야 한다.

- [ ] **Step 8: OpenAPI와 generated artifacts만 커밋한다**

```powershell
git status --short
git add -- docs/specs backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java backend/src/test/java/com/miriyum/domain frontend/src/shared/api/generated
git commit -m "refactor(api): 소비자별 OpenAPI 계약 분리"
```

---

### Task 9: 활성 문서 정렬과 전체 검증

**Files:**
- Modify: `docs/02-users-and-permissions.md`
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/07-data-and-api-contracts.md`
- Modify: `docs/09-quality-operations-and-rules.md`
- Modify: `docs/specs/auth-account/spec.md`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/reservation/spec.md`
- Modify: `docs/specs/menu-hold-pickup/spec.md`
- Modify: `docs/specs/mvp1-common/ownership.md`
- Modify: `backend/ai/implementation-guardrails.md`

**Interfaces:**
- Consumes: 실제 구현된 URL, Port/Adapter, Facade, OpenAPI와 검증 결과
- Produces: 활성 정본과 backend guardrail의 현재 구현 정렬, 최종 완료 증거

- [ ] **Step 1: 활성 정본의 구 URL과 임시 baseline 문구를 새 사실로 바꾼다**

문서는 다음 사실만 기록한다.

- consumer와 store-operator의 새 plural namespace
- 공개 API 경로 유지와 구 URL 미지원
- 소비자별 OpenAPI 진입점과 기능별 단일 원본
- 예약 내역 HTTP 경계의 Reservation 소유
- `ReservationMenuHoldPort`/MenuHold adapter와 `ReservationTimeResolutionService`
- 도메인 cyclic pair/edge baseline 0건
- `MenuTransactionFacade`의 Store→Menu 잠금·검증 소유

`backend/ai/implementation-guardrails.md`의 예시 Service 이름도 `MenuTransactionFacade`로 바꾼다.

- [ ] **Step 2: 구 URL과 구 타입 잔존을 저장소 전체에서 검사한다**

Run from repository root:

```powershell
rg -n 'api/v1/(consumer-auth|consumer-accounts|reservations|pickup-reservations|store-operator-auth|store-operator-accounts|store-operator)' backend docs/specs frontend/src/shared/api/generated
rg -n 'MenuTransactionService|LEGACY_CYCLIC' backend docs/02-users-and-permissions.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/09-quality-operations-and-rules.md
```

Expected: 두 명령 모두 결과 없음. `/api/v1/stores/**` 공개 경로는 첫 정규식에 해당하지 않는다.

- [ ] **Step 3: 빠른 backend 전체 테스트를 실행한다**

Run from `backend/`:

```powershell
.\gradlew.bat test --console=plain
```

Expected: PASS, failures/errors/skips 0.

- [ ] **Step 4: DB 통합 테스트를 실행한다**

Run:

```powershell
.\gradlew.bat integrationTest --console=plain
```

Expected: Docker 사용 가능 시 PASS. Docker daemon 또는 image pull이 불가능하면 `BLOCKED`로 기록하고 GitHub Actions `integration-test-a`, `integration-test-b`에서 동일 HEAD를 확인한다.

- [ ] **Step 5: frontend 생성 계약과 build를 재검증한다**

Run from `frontend/`:

```powershell
pnpm run generate:api
git diff --exit-code -- src/shared/api/generated
pnpm run typecheck
pnpm test
pnpm run build
```

Expected: generated diff 0, typecheck·test·build PASS.

- [ ] **Step 6: diff 범위와 whitespace를 검증한다**

Run from repository root:

```powershell
git diff --check origin/dev...HEAD
git diff --name-only origin/dev...HEAD
git status --short --branch
```

Expected: whitespace 오류 없음. 변경 파일은 Issue #82의 backend 코드·테스트, 기능/OpenAPI·활성 문서, generated API 계약과 이 작업의 design/plan 문서에 한정된다.

- [ ] **Step 7: 활성 문서 변경과 최종 보완을 커밋한다**

```powershell
git add -- docs/02-users-and-permissions.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/09-quality-operations-and-rules.md docs/specs/*/spec.md docs/specs/mvp1-common/ownership.md backend/ai/implementation-guardrails.md
git commit -m "docs(api): 사용자 유형별 계약과 의존 방향 정렬"
```

- [ ] **Step 8: 최종 branch 상태와 commit 목록을 확인한다**

```powershell
git status --short --branch
git log --oneline origin/dev..HEAD
```

Expected: working tree clean. 각 Task의 의도적 커밋과 design/plan 커밋만 존재한다.
