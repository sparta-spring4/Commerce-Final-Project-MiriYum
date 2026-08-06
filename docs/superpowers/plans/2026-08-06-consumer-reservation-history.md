# Consumer Reservation History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증된 소비자가 마이페이지 경로에서 자신의 예약 내역을 최신 예약 시간 계약으로 조회하게 한다.

**Architecture:** `ConsumerAccountController`가 인증 principal과 공개 query 값을 예약 도메인의 기존 `ReservationHistorySearchRequest`로 변환한 뒤 `ReservationService.getConsumerReservationHistory()`를 호출한다. 예약 조회·계정 상태 확인·본인 범위·정렬은 기존 예약 서비스와 저장소가 담당한다. 인증 OpenAPI는 기존 마이페이지 스키마 이름을 유지하면서 예약 도메인의 최신 공개 DTO 필드와 공용 시간 스키마를 반영한다.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring Security, Spring Data JPA, JUnit 5, MockMvc, MockitoBean, SnakeYAML, OpenAPI 3.1

## Global Constraints

- 작업은 `C:\Users\leeji\Java_4\fianlProject\Commerce-Final-Project-MiriYum` 원본 IntelliJ 프로젝트에서만 수행한다.
- 별도 worktree를 만들지 않는다.
- 기존 `.idea/` 파일을 수정하거나 스테이징하지 않는다.
- 프로덕션 코드는 해당 동작을 증명하는 실패 테스트를 먼저 확인한 뒤 작성한다.
- 커밋은 사용자에게 변경·검증 결과를 보여주고 명시적으로 허락받은 뒤에만 수행한다.
- DB 스키마와 Flyway migration은 변경하지 않는다.

---

### Task 1: 예약 내역 OpenAPI 소유권과 시간 계약 정렬

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java`
- Modify: `docs/specs/auth-account/openapi.yaml`

**Interfaces:**
- Consumes: 예약 정책의 `ReservationTimeStatus`, `OffsetDateTime`, `PageMetadata`
- Produces: 최신 시간 필드를 사용하는 기존 `ReservationHistoryItem`, `ReservationHistoryPageData` OpenAPI schemas

- [x] **Step 1: 실패하는 계약 테스트 작성**

`ReservationOpenApiContractTest`에 인증 OpenAPI 문서를 파싱해 다음을 검증하는 테스트를 추가한다.

```java
@Test
void consumerHistoryKeepsAuthOwnedSchemaNamesAndCanonicalTimeShape() throws IOException {
    Map<String, Object> auth = load(Path.of(
            "..", "docs", "specs", "auth-account", "openapi.yaml"));

    Map<String, Object> authSchemas = map(map(auth.get("components")).get("schemas"));
    assertThat(authSchemas).containsKeys(
            "ReservationHistoryStatus",
            "ReservationHistoryItem",
            "ReservationHistoryPageData"
    );
    Map<String, Object> item = map(authSchemas.get("ReservationHistoryItem"));
    assertThat(map(item.get("properties")))
            .containsKeys("timeStatus", "startAt", "serviceEndAt", "timeZoneId")
            .doesNotContainKeys("startTime", "endTime", "cancelledBy");
    assertThat(map(map(authSchemas.get("ReservationHistoryPageSuccessResponse"))
            .get("properties")).get("data"))
            .containsEntry("$ref", "#/components/schemas/ReservationHistoryPageData");
}
```

- [x] **Step 2: 계약 테스트가 올바른 이유로 실패하는지 확인**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
```

Expected: 인증 문서의 기존 예약 내역 스키마가 과거 시간 필드를 사용해 FAIL.

- [x] **Step 3: 인증 OpenAPI의 기존 예약 내역 스키마 갱신**

`ReservationHistoryItem`은 실제 `ReservationHistoryItemResponse`와 동일한 필드를 선언한다. `startAt`, `serviceEndAt`, `timeZoneId`는 `LEGACY_UNRESOLVED`에서 null을 허용하고, 기존 `ReservationHistoryPageData.items`가 이 스키마를 계속 참조한다.

- [x] **Step 4: 인증 OpenAPI가 예약 스키마를 참조하도록 수정**

팀원이 먼저 정의한 `ReservationHistoryStatus`, `ReservationHistoryItem`, `ReservationHistoryPageData` 이름과 소유 문서는 유지한다. 예약 도메인의 `StoreLocalDate`, `ReservationTimeStatus`만 `$ref`로 재사용하고 경로와 성공 응답은 기존 인증 스키마 이름을 계속 사용한다.

- [x] **Step 5: 계약 테스트 통과 확인**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
```

Expected: PASS.

### Task 2: 마이페이지 예약 내역 HTTP 경계 구현

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/consumer/controller/ConsumerAccountControllerTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/controller/ConsumerAccountController.java`

**Interfaces:**
- Consumes: `ReservationHistorySearchRequest.from(String, Integer, Integer, String)` and `ReservationService.getConsumerReservationHistory(Long, ReservationHistorySearchRequest)`
- Produces: `GET /api/v1/consumer-accounts/me/reservations`

- [x] **Step 1: 실패하는 MockMvc 테스트 작성**

`ConsumerAccountControllerTest`에 `@MockitoBean ReservationService`를 추가하고 다음 실제 HTTP 결과를 검증한다.

```java
@Test
@DisplayName("인증된 소비자는 자신의 예약 내역 페이지를 조회한다")
void getReservationHistoryReturnsReservationPage() throws Exception {
    String token = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);
    ReservationHistoryPageResponse page = new ReservationHistoryPageResponse(
            List.of(new ReservationHistoryItemResponse(
                    "91", "7", "미리윰 식당", LocalDate.of(2026, 8, 10),
                    CustomerReservationTimeStatus.RESOLVED,
                    OffsetDateTime.parse("2026-08-10T18:00:00+09:00"),
                    OffsetDateTime.parse("2026-08-10T19:00:00+09:00"),
                    "Asia/Seoul", 2, "CONFIRMED",
                    OffsetDateTime.parse("2026-08-01T00:00:00Z"))),
            new PageMetadata(0, 20, 1, 1, false));
    given(reservationService.getConsumerReservationHistory(
            eq(accountId), any(ReservationHistorySearchRequest.class)))
            .willReturn(page);

    mockMvc.perform(get("/api/v1/consumer-accounts/me/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].reservationId").value("91"))
            .andExpect(jsonPath("$.data.items[0].timeStatus").value("RESOLVED"))
            .andExpect(jsonPath("$.data.items[0].serviceEndAt")
                    .value("2026-08-10T19:00:00+09:00"));
}
```

별도 테스트에서 빈 페이지가 `200/items: []`로 반환되고, `sort=status,asc`가 `400/COMMON_001`로 거절되는지 확인한다.

- [x] **Step 2: 경로 부재로 실패하는지 확인**

Run:

```powershell
.\gradlew.bat integrationTestShardA --tests "com.miriyum.domain.consumer.controller.ConsumerAccountControllerTest"
```

Expected: 새 GET 경로가 없어 404 또는 기대 응답 불일치로 FAIL.

- [x] **Step 3: 컨트롤러 메서드 최소 구현**

```java
@GetMapping("/me/reservations")
public ApiResponse<ReservationHistoryPageResponse> getReservationHistory(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        @RequestParam(required = false) String sort
) {
    ReservationHistorySearchRequest request =
            ReservationHistorySearchRequest.from(status, page, size, sort);
    return ApiResponse.success(
            "조회되었습니다.",
            reservationService.getConsumerReservationHistory(principal.accountId(), request)
    );
}
```

클래스 JavaDoc의 BLOCKED 설명도 실제 구현 상태로 바꾼다.

- [x] **Step 4: 컨트롤러 테스트 통과 확인**

Run:

```powershell
.\gradlew.bat integrationTestShardA --tests "com.miriyum.domain.consumer.controller.ConsumerAccountControllerTest"
```

Expected: PASS.

### Task 3: 기존 예약 조회 보장과 변경 검토

**Files:**
- Verify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`
- Verify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationQueryRepositoryTest.java`

**Interfaces:**
- Consumes: 구현된 HTTP 경계와 기존 예약 조회 계약
- Produces: #62 인수 조건에 대한 회귀 검증 결과

- [x] **Step 1: 예약 서비스 단위 테스트 실행**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
```

Expected: 상태 필터, 결정적 정렬, 정지 계정 차단 테스트 PASS.

- [x] **Step 2: 예약 저장소 통합 테스트 실행**

```powershell
.\gradlew.bat integrationTestShardB --tests "com.miriyum.domain.reservation.repository.ReservationQueryRepositoryTest"
```

Expected: 다른 소비자 예약 미노출, 빈 결과, 상태 필터, 페이지 정렬 테스트 PASS.

- [x] **Step 3: 변경 파일과 공백 오류 확인**

```powershell
git diff --check
git status --short
```

Expected: `.idea/` 외에는 계획된 파일만 변경되고 `git diff --check` 오류가 없음.

- [x] **Step 4: 사용자 확인 전 커밋 보류**

변경 파일, 테스트 결과와 권장 커밋 메시지를 사용자에게 제시한다. 사용자가 명시적으로 허락하기 전에는 `git add`, `git commit`, `git push`를 실행하지 않는다.
