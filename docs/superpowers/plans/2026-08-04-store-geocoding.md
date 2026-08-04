# Store Geocoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate every new or location-changing store address through Kakao before writing and atomically persist coordinates tied to the current address version.

**Architecture:** `StoreService` performs provider-neutral geocoding before opening a database transaction, captures success or failure, and enters a short transaction through `StoreCommandTransactionExecutor` so `IdempotencyExecutor` can either replay an old result or execute the new write. `KakaoLocalGeocodingAdapter` only maps HTTP data into provider-neutral candidates; `StoreGeocodingValidator` owns strict address, Region, cardinality, and coordinate checks; `Store` owns versioned coordinate invariants.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC `RestClient`, Spring Data JPA, Flyway V20, MySQL 8, JUnit 5, Mockito, WireMock 3.13.1 standalone, Testcontainers MySQL.

## Global Constraints

- Kakao calls occur only for create and PATCH requests containing `address` or `region`; search and recommendation never call the adapter.
- Use Kakao address search with `analyze_type=similar`, `size=2`, connect timeout 1000 ms, response timeout 2000 ms, and no automatic retry.
- Candidate/semantic failures map to `COMMON_001` / HTTP 400; provider, configuration, transport, timeout, 429, 5xx, or malformed-response failures map to `COMMON_012` / HTTP 503.
- A stale update preflight maps to existing `COMMON_008` / HTTP 409.
- API keys, Authorization headers, full provider payloads, and internal provider metadata must not be stored in idempotency payloads, logs, or HTTP responses.
- Existing rows migrate to address version 1 and `UNVERIFIED`; new creates and location-changing updates only persist `VERIFIED` coordinates.
- New secrets have no repository default. Use `MIRIYUM_KAKAO_LOCAL_REST_API_KEY`.
- Preserve Java 21, Spring Boot 4.1.0, the existing three-layer package convention, constructor injection, and existing public routes and roles.
- Run every Gradle command from the `backend` directory; run Git and repository-wide `rg` commands from the worktree root.

---

## File map

- `backend/src/main/resources/db/migration/V20__add_store_geocoding.sql`: columns, legacy backfill, coordinate/version CHECK constraints.
- `backend/src/main/java/com/miriyum/domain/store/core/enums/GeocodingStatus.java`: `UNVERIFIED` and `VERIFIED` persistence vocabulary.
- `backend/src/main/java/com/miriyum/domain/store/core/model/StoreGeocodingCandidate.java`: provider-neutral raw candidate strings.
- `backend/src/main/java/com/miriyum/domain/store/core/model/StoreGeocodingResult.java`: provider-neutral cardinality and candidate envelope.
- `backend/src/main/java/com/miriyum/domain/store/core/model/VerifiedStoreGeocoding.java`: validated coordinates and minimal internal metadata.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingPort.java`: outbound geocoding boundary.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingValidator.java`: strict semantic validation and normalization.
- `backend/src/main/java/com/miriyum/domain/store/core/config/StoreGeocodingProperties.java`: base URL, API key, and timeout properties.
- `backend/src/main/java/com/miriyum/domain/store/core/config/StoreGeocodingConfig.java`: bounded timeout configuration and `RestClient` construction.
- `backend/src/main/java/com/miriyum/domain/store/core/service/KakaoLocalGeocodingAdapter.java`: Kakao request/response mapping and provider-failure classification.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandTransactionExecutor.java`: five-second READ_COMMITTED write transaction boundary.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`: preflight orchestration, replay-safe failure capture, stale-preflight guard.
- `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`: coordinate fields and versioned location transitions.
- `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreGeocodingResponse.java`: safe nested operator response.
- `backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java`: exposes the nested safe response.
- `backend/src/main/resources/application.yml`: environment-backed geocoding configuration.
- `backend/build.gradle.kts`: WireMock standalone test dependency.
- `docs/specs/store-search/spec.md`, `docs/specs/store-search/openapi.yaml`, `docs/service-policies/02-store-onboarding.md`, `docs/service-policies/03-store-operation.md`: active contract and policy changes.

---

### Task 1: Versioned geocoding persistence invariant

**Files:**
- Create: `backend/src/main/resources/db/migration/V20__add_store_geocoding.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/GeocodingStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/model/VerifiedStoreGeocoding.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreGeocodingMigrationTest.java`

**Interfaces:**
- Consumes: the existing legacy-compatible `Store.create` factory, the general `Store.update` transition, and the Flyway-managed `stores` table.
- Produces: `VerifiedStoreGeocoding(BigDecimal latitude, BigDecimal longitude, String verifiedAddress, Instant verifiedAt, String provider, String providerApiVersion)` and these exact aggregate APIs:

```java
public static Store createVerified(
        long storeOperatorAccountId,
        String businessRegistrationNumber,
        BusinessType businessType,
        String name,
        String description,
        Region region,
        String address,
        String storeCategoryCode,
        Set<String> tagCodes,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled,
        String timeZoneId,
        LocalDateTime onboardingAcceptedAt,
        String requiredTermsVersion,
        VerifiedStoreGeocoding geocoding);

public void update(
        String name,
        String description,
        Region region,
        String address,
        String storeCategoryCode,
        Set<String> tagCodes,
        Boolean reservationEnabled,
        Boolean menuHoldEnabled,
        Boolean pickupEnabled,
        OperationStatus operationStatus,
        VerifiedStoreGeocoding geocoding);
```

- [ ] **Step 1: Write failing aggregate tests**

Add tests proving `createVerified` starts at `addressVersion == 1` with `VERIFIED`, the existing `create` factory produces the legacy-compatible `UNVERIFIED` shape, a location-changing update increments exactly once and replaces every coordinate field, a non-location update preserves the version, and `null` verified data is rejected when address or Region is supplied.

```java
VerifiedStoreGeocoding verified = new VerifiedStoreGeocoding(
        new BigDecimal("37.566826000000000"),
        new BigDecimal("126.978656700000000"),
        "서울 중구 세종대로 110",
        Instant.parse("2026-08-04T09:00:00Z"),
        "KAKAO_LOCAL",
        "v2");

Store store = createVerifiedStore(verified);
store.update(null, null, Region.SEOUL, "서울 중구 을지로 1", null, null,
        null, null, null, null, changedGeocoding);

assertThat(store.getAddressVersion()).isEqualTo(2L);
assertThat(store.getGeocodingAddressVersion()).isEqualTo(2L);
assertThat(store.getLatitude()).isEqualByComparingTo("37.566700000000000");
```

Add `createVerifiedStore(VerifiedStoreGeocoding geocoding)` as a private test helper that calls the exact `Store.createVerified` signature above with the existing valid Store fixture values.

- [ ] **Step 2: Run aggregate tests and confirm the red state**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.entity.StoreTest" --no-daemon --max-workers=1`

Expected: compilation fails because geocoding types, fields, and method parameters do not exist.

- [ ] **Step 3: Add the enum, verified value object, and aggregate transition**

Implement the exact value types and keep the mutation inside `Store`:

```java
public enum GeocodingStatus {
    UNVERIFIED,
    VERIFIED
}

public record VerifiedStoreGeocoding(
        BigDecimal latitude,
        BigDecimal longitude,
        String verifiedAddress,
        Instant verifiedAt,
        String provider,
        String providerApiVersion
) {
}
```

`Store.createVerified` must require verified data, initialize `addressVersion` to 1, and call a private `applyVerifiedGeocoding`. Preserve the existing public `Store.create` signature for legacy-compatible test fixtures and initialize it to `addressVersion = 1`, `UNVERIFIED`, and null coordinate metadata; `StoreService` must stop using this legacy factory in Task 4. Add the location-aware `Store.update` overload while retaining the current signature as a temporary compile-compatible delegate that passes null geocoding and therefore fails closed if old code attempts a location change. Task 4 switches `StoreService` to the verified overload and removes that delegate if no callers remain. The location-aware method must calculate `boolean locationTouched = region != null || address != null`; only that branch requires verified data, updates effective Region/address, increments `addressVersion`, and replaces all geocoding fields. Other field updates retain current geocoding unchanged.

- [ ] **Step 4: Write the failing V20 migration test**

Start MySQL with Flyway target 19, insert a legacy store, migrate to 20, and assert:

```java
assertThat(row.getLong("address_version")).isEqualTo(1L);
assertThat(row.getString("geocoding_status")).isEqualTo("UNVERIFIED");
assertThat(row.getBigDecimal("latitude")).isNull();
assertThat(row.getObject("geocoding_address_version")).isNull();
```

Also issue direct SQL that attempts `VERIFIED` with missing metadata, mismatched versions, latitude 91, and longitude 181; each statement must throw `SQLException`.

- [ ] **Step 5: Implement V20 and pass entity/migration tests**

Use `DECIMAL(18,15)` for both coordinates, `DATETIME(6)` for `geocoding_verified_at`, and CHECK constraints with these two valid shapes:

```sql
geocoding_status = 'UNVERIFIED'
AND latitude IS NULL
AND longitude IS NULL
AND verified_address IS NULL
AND geocoding_verified_at IS NULL
AND geocoding_address_version IS NULL
AND geocoding_provider IS NULL
AND geocoding_provider_api_version IS NULL
```

or:

```sql
geocoding_status = 'VERIFIED'
AND latitude BETWEEN -90 AND 90
AND longitude BETWEEN -180 AND 180
AND geocoding_address_version = address_version
AND verified_address IS NOT NULL
AND geocoding_verified_at IS NOT NULL
AND geocoding_provider IS NOT NULL
AND geocoding_provider_api_version IS NOT NULL
```

Run both focused test classes. Expected: PASS.

- [ ] **Step 6: Commit the persistence invariant**

```bash
git add backend/src/main/resources/db/migration/V20__add_store_geocoding.sql backend/src/main/java/com/miriyum/domain/store/core backend/src/test/java/com/miriyum/domain/store/core
git commit -m "feat(store): persist versioned geocoding coordinates"
```

---

### Task 2: Provider-neutral response validator

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/model/StoreGeocodingCandidate.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/model/StoreGeocodingResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingValidator.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreGeocodingValidatorTest.java`

**Interfaces:**
- Consumes: `Region`, `CommonErrorCode.VALIDATION_FAILED`, and `Clock`.
- Produces: `StoreGeocodingPort.geocode(String address)`, provider-neutral candidate/result records, and `StoreGeocodingValidator.validate(Region region, String requestedAddress, StoreGeocodingResult result, Instant verifiedAt)` returning `VerifiedStoreGeocoding`.

- [ ] **Step 1: Write validator cardinality and coordinate tests**

Cover `totalCount` 0 and 2, zero and two returned documents, nonnumeric coordinates, latitude outside `[-90, 90]`, longitude outside `[-180, 180]`, and missing provider metadata. Every case must assert `ServiceException` with `CommonErrorCode.VALIDATION_FAILED`.

```java
assertThatThrownBy(() -> validator.validate(
        Region.SEOUL,
        "서울 중구 세종대로 110",
        new StoreGeocodingResult(0, List.of(), "KAKAO_LOCAL", "v2"),
        VERIFIED_AT))
        .isInstanceOf(ServiceException.class)
        .extracting(error -> ((ServiceException) error).getErrorCode())
        .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
```

- [ ] **Step 2: Run validator tests and confirm the red state**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreGeocodingValidatorTest" --no-daemon --max-workers=1`

Expected: compilation fails because the validator contract is absent.

- [ ] **Step 3: Implement candidate/result contracts and numeric validation**

Use these signatures:

```java
public record StoreGeocodingCandidate(
        String roadAddress,
        String parcelAddress,
        String region1DepthName,
        String longitude,
        String latitude
) {
}

public record StoreGeocodingResult(
        int totalCount,
        List<StoreGeocodingCandidate> candidates,
        String provider,
        String providerApiVersion
) {
}

public interface StoreGeocodingPort {
    StoreGeocodingResult geocode(String address);
}
```

Parse with `new BigDecimal(value)` and reject null, blank, nonnumeric, or out-of-range values as `COMMON_001`.

- [ ] **Step 4: Add address normalization and Region tests**

Accept both road and parcel matches, repeated whitespace/punctuation differences, and safe detail suffixes such as `서울 중구 세종대로 110 3층`. Reject a fuzzy different road/number and Region mismatch. Map enum Regions to Kakao first-depth prefixes: 서울, 부산, 대구, 대전, 광주.

- [ ] **Step 5: Implement strict semantic matching and pass the suite**

Normalize with Unicode NFKC, lowercase, and removal of Unicode whitespace plus `-_,.()[]`. A provider address is valid only when the normalized request equals it or starts with it and the remaining suffix is nonempty detail text; never accept a provider address that merely contains the request. Validate Region against normalized `region1DepthName` before creating `VerifiedStoreGeocoding`.

Run the focused validator test. Expected: PASS.

- [ ] **Step 6: Commit the provider-neutral validator**

```bash
git add backend/src/main/java/com/miriyum/domain/store/core/model backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingPort.java backend/src/main/java/com/miriyum/domain/store/core/service/StoreGeocodingValidator.java backend/src/test/java/com/miriyum/domain/store/core/service/StoreGeocodingValidatorTest.java
git commit -m "feat(store): validate provider neutral geocoding results"
```

---

### Task 3: Kakao Local HTTP adapter

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/config/StoreGeocodingProperties.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/config/StoreGeocodingConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/KakaoLocalGeocodingAdapter.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/core/service/KakaoLocalGeocodingAdapterTest.java`

**Interfaces:**
- Consumes: `StoreGeocodingPort` and the provider-neutral result records from Task 2.
- Produces: a primary `KakaoLocalGeocodingAdapter`, validated properties, and a timeout-bounded `RestClient`.

- [ ] **Step 1: Add WireMock and write the successful adapter test**

Add:

```kotlin
testImplementation("org.wiremock:wiremock-standalone:3.13.1")
```

Start `WireMockServer` on a dynamic port, construct the adapter against that base URL, return one Kakao document, and verify query/header details:

```java
wireMock.verify(getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
        .withQueryParam("query", equalTo("서울 중구 세종대로 110"))
        .withQueryParam("analyze_type", equalTo("similar"))
        .withQueryParam("size", equalTo("2"))
        .withHeader("Authorization", equalTo("KakaoAK test-key")));
```

- [ ] **Step 2: Run the adapter test and confirm the red state**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.KakaoLocalGeocodingAdapterTest" --no-daemon --max-workers=1`

Expected: compilation fails because properties/config/adapter do not exist.

- [ ] **Step 3: Implement configuration and successful response mapping**

Define `StoreGeocodingProperties(String baseUrl, String restApiKey, long connectTimeoutMs, long responseTimeoutMs)` and register it through `@EnableConfigurationProperties(StoreGeocodingProperties.class)`. Require positive bounded timeout values at startup, but allow an empty API key so local/test application contexts still boot and an actual geocoding command returns `COMMON_012` without making a request. Configure a `JdkClientHttpRequestFactory` with `Duration.ofMillis(connectTimeoutMs)` and `Duration.ofMillis(responseTimeoutMs)`, then build the dedicated `RestClient`. The adapter maps `meta.total_count`, `address.address_name`, `road_address.address_name`, the available nested `region_1depth_name`, `x`, and `y` without logging the body.

Add these YAML keys:

```yaml
miriyum:
  store:
    geocoding:
      base-url: ${MIRIYUM_STORE_GEOCODING_BASE_URL:https://dapi.kakao.com}
      rest-api-key: ${MIRIYUM_KAKAO_LOCAL_REST_API_KEY:}
      connect-timeout-ms: ${MIRIYUM_STORE_GEOCODING_CONNECT_TIMEOUT_MS:1000}
      response-timeout-ms: ${MIRIYUM_STORE_GEOCODING_RESPONSE_TIMEOUT_MS:2000}
```

- [ ] **Step 4: Write provider-failure tests**

Cover delayed response beyond 2 seconds, 429, 500, 401, malformed JSON, missing `meta`, an empty configured key, and a connection failure. Assert every case throws `ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE)`. Add WireMock responses for zero candidates, two candidates, and an out-of-range coordinate; feed the mapped result into `StoreGeocodingValidator` and assert `COMMON_001`. Verify one request only in timeout and 5xx tests to prove no retry, and zero requests for the empty-key test.

- [ ] **Step 5: Implement failure classification and pass adapter tests**

Reject an empty key before request creation. Catch transport, timeout, decoding, non-2xx, and invalid-envelope failures at the adapter boundary and replace them with `COMMON_012`. Keep semantically parseable zero/multiple/invalid-coordinate results provider-neutral so the validator maps them to `COMMON_001`. Do not include URI query, key, Authorization header, or response body in exception text.

Run the focused adapter test. Expected: PASS.

- [ ] **Step 6: Commit the Kakao adapter**

```bash
git add backend/build.gradle.kts backend/src/main/resources/application.yml backend/src/main/java/com/miriyum/domain/store/core/config backend/src/main/java/com/miriyum/domain/store/core/service/KakaoLocalGeocodingAdapter.java backend/src/test/java/com/miriyum/domain/store/core/service/KakaoLocalGeocodingAdapterTest.java
git commit -m "feat(store): add timeout bounded Kakao geocoding adapter"
```

---

### Task 4: Replay-safe service preflight and atomic write

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandTransactionExecutor.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreGeocodingTransactionIT.java`

**Interfaces:**
- Consumes: `StoreGeocodingPort`, `StoreGeocodingValidator`, `VerifiedStoreGeocoding`, existing `IdempotencyExecutor` with `Propagation.MANDATORY`.
- Produces: `StoreCommandTransactionExecutor.execute(Supplier<T>)` and create/update orchestration with a private captured preflight value.

- [ ] **Step 1: Write service tests for create and conditional update calls**

Assert create calls `port.geocode(request.address())`; address-only PATCH uses the current Region; Region-only PATCH uses the current address; address+Region PATCH uses both requested values; PATCH with neither makes no port call. Configure the mocked transaction executor to run its supplier.

```java
given(transactionExecutor.execute(any())).willAnswer(invocation -> {
    Supplier<?> work = invocation.getArgument(0);
    return work.get();
});
```

- [ ] **Step 2: Run service tests and confirm the red state**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest" --no-daemon --max-workers=1`

Expected: compilation fails because the new collaborators and constructor do not exist.

- [ ] **Step 3: Implement the short transaction executor and preflight capture**

`StoreCommandTransactionExecutor` constructs a `TransactionTemplate` with READ_COMMITTED and timeout 5 seconds and exposes:

```java
public <T> T execute(Supplier<T> work) {
    return transactionOperations.execute(status -> work.get());
}
```

Remove `@Transactional` from `StoreService.create/update`. Before calling the executor, call the port and validator and capture either verified data or the `ServiceException` in a private `GeocodingPreflight` record. Inside `idempotencyExecutor.execute` supplier, rethrow captured failure for a new claim; a replay skips the supplier and returns the stored success.

- [ ] **Step 4: Add failure, replay, and stale-preflight tests**

Prove geocoding failure reaches the transaction/idempotency layer but never saves; update failure preserves the old aggregate; replay returns its stored response even when the current preflight captured `COMMON_012`; and a locked Store whose effective address/Region differs from the preflight throws `COMMON_008` before mutation.

For call order, verify:

```java
InOrder order = inOrder(geocodingPort, transactionExecutor, idempotencyExecutor);
order.verify(geocodingPort).geocode("서울 중구 세종대로 110");
order.verify(transactionExecutor).execute(any());
order.verify(idempotencyExecutor).execute(any(), any());
```

- [ ] **Step 5: Implement effective-location and stale guards**

For update preflight, load and authorize a snapshot outside the write transaction, derive effective values, and record both. Inside the new-claim supplier, lock with `findByIdForUpdate`, derive effective values again, compare exact Region and address strings with the preflight source, and throw `COMMON_008` on mismatch. Pass verified data to `Store.update` only after this check.

- [ ] **Step 6: Add transaction integration evidence**

In `StoreGeocodingTransactionIT`, use a test `StoreGeocodingPort` that records `TransactionSynchronizationManager.isActualTransactionActive()` and assert it is false during the HTTP-boundary call. Assert the persisted address, Region, `address_version`, coordinates, and `geocoding_address_version` are visible together after commit; force validator failure and assert the row is unchanged.

Run focused service and integration tests. Expected: PASS.

- [ ] **Step 7: Commit the service orchestration**

```bash
git add backend/src/main/java/com/miriyum/domain/store/core/service backend/src/test/java/com/miriyum/domain/store/core/service
git commit -m "feat(store): validate geocoding before atomic store writes"
```

---

### Task 5: Safe operator response and active contracts

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreGeocodingResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/dto/ManagedStoreResponseTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/service-policies/03-store-operation.md`

**Interfaces:**
- Consumes: Store geocoding getters and existing `ManagedStore` OpenAPI schema.
- Produces: `StoreGeocodingResponse(GeocodingStatus status, BigDecimal latitude, BigDecimal longitude, String verifiedAddress, Instant verifiedAt, long addressVersion)` nested as required `geocoding`.

- [ ] **Step 1: Write DTO and MockMvc response tests**

Assert a verified Store maps all six public fields and excludes provider metadata. Assert a reflected legacy `UNVERIFIED` Store maps null coordinate/address/time fields with addressVersion 1. Extend create/get/PATCH MockMvc assertions:

```java
.andExpect(jsonPath("$.data.geocoding.status").value("VERIFIED"))
.andExpect(jsonPath("$.data.geocoding.latitude").value(37.566826))
.andExpect(jsonPath("$.data.geocoding.longitude").value(126.9786567))
.andExpect(jsonPath("$.data.geocoding.addressVersion").value(1))
.andExpect(jsonPath("$.data.geocoding.provider").doesNotExist());
```

- [ ] **Step 2: Run DTO/controller tests and confirm the red state**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.dto.ManagedStoreResponseTest" --tests "com.miriyum.domain.store.core.controller.StoreControllerTest" --no-daemon --max-workers=1`

Expected: compilation or JSON assertion failure because `geocoding` is absent.

- [ ] **Step 3: Implement the nested safe response**

Add the exact record and map from `Store`. Always return the nested object. For `UNVERIFIED`, status and addressVersion remain nonnull while latitude, longitude, verifiedAddress, and verifiedAt are null. Do not add internal provider fields.

- [ ] **Step 4: Update spec, policy, and OpenAPI contracts**

Keep the 1차 MVP search scope intact and add a labeled 2차 MVP geocoding section. Document strict create/location-PATCH failures, legacy fallback, no search-time provider call, and stale-preflight behavior. Add `StoreGeocoding` schema and make `ManagedStore.geocoding` required; coordinates and verification fields are nullable only for `UNVERIFIED`. Reuse documented `Common400`, `Common409`, and `Common503` responses without creating new codes.

- [ ] **Step 5: Run contract-focused tests and inspect schema references**

Run the DTO/controller test command again. Expected: PASS.

Run: `rg -n "StoreGeocoding|geocoding|COMMON_008|COMMON_012|UNVERIFIED" docs/specs/store-search docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md`

Expected: each behavior has one active owner statement and OpenAPI references resolve to `StoreGeocoding`.

- [ ] **Step 6: Commit the public contract**

```bash
git add backend/src/main/java/com/miriyum/domain/store/core/dto backend/src/test/java/com/miriyum/domain/store/core/dto backend/src/test/java/com/miriyum/domain/store/core/controller docs/specs/store-search docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md
git commit -m "docs(store): expose safe geocoding verification contract"
```

---

### Task 6: Full verification and allowlist audit

**Files:**
- Modify only files already listed in Tasks 1-5 when a failing verification demonstrates a defect.

**Interfaces:**
- Consumes: all #117 implementation and tests.
- Produces: reproducible verification evidence and an allowlist-clean branch.

- [ ] **Step 1: Run all Store core tests**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.core.*" --no-daemon --max-workers=1`

Expected: PASS with zero failures and errors.

- [ ] **Step 2: Run the complete backend suite from a clean test output**

Run: `.\gradlew.bat clean test --no-daemon --max-workers=1`

Expected: `BUILD SUCCESSFUL`; aggregate XML counts show zero failures and errors.

- [ ] **Step 3: Run static diff and secret checks**

Run: `git diff --check origin/dev...HEAD`

Expected: no output.

Run: `rg -n "KakaoAK [A-Za-z0-9]|MIRIYUM_KAKAO_LOCAL_REST_API_KEY:|Authorization.*KakaoAK" backend/src/main backend/src/test docs --glob '!**/KakaoLocalGeocodingAdapterTest.java'`

Expected: no committed literal key or sensitive header value.

- [ ] **Step 4: Audit changed paths against #117**

Run: `git diff --name-only origin/dev...HEAD`

Expected: every path is one of the exact issue paths; V20 is the only new migration and no frontend path appears.

- [ ] **Step 5: Request independent code review and remediate important findings**

Review the diff against #117 acceptance criteria, transaction boundaries, idempotent replay, secret handling, coordinate/version CHECK constraints, and active OpenAPI compatibility. Apply each validated important correction through a new failing focused test, rerun its focused suite, and commit with a message describing the correction.

- [ ] **Step 6: Re-run completion gates after review changes**

Run the complete backend suite, `git diff --check origin/dev...HEAD`, secret scan, and changed-path audit again. Expected: all pass with the same zero-failure conditions.

- [ ] **Step 7: Prepare the draft PR handoff**

Summarize exact test counts, provider timeout/no-retry behavior, 400/409/503 contracts, migration rollback rule, absence of a real Kakao credential test, and the fact that frontend/search consumers remain outside this PR. Keep the PR Draft until review evidence is accepted.
