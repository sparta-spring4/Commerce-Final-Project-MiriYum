# Issue #368 LLM Search Interpretation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve #378 partial reservation evaluation while adding a cost-bounded `gpt-4o-mini` interpreter that expands unresolved food language into current MySQL store and unavailable-menu alternative candidates.

**Architecture:** The existing rule interpreter and MySQL query execute first. A provider-neutral expansion service calls OpenAI Structured Outputs only for a non-cursor request that remains under-filled, validates at most eight concepts, and sends those concepts through separate current-version QueryDSL predicates; all candidates then pass current store and #378 reservation checks. Menu alternatives receive one expansion when the source is loaded and reuse the immutable concepts for same-store and nearby queries.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring `RestClient`, Jackson, Bean Validation, Micrometer, QueryDSL JPA, MySQL 8 Testcontainers, JUnit 5, Mockito, AssertJ, WireMock 3.13.

## Global Constraints

- Work only in `feature/368-partial-availability-semantic-search`, rebased onto the latest `origin/dev` before implementation.
- MySQL is the source of truth; OpenAI output only broadens candidate sourcing.
- Use `gpt-4o-mini` with strict Structured Outputs; return no more than eight concepts and approximately 100 output tokens.
- Do not call the LLM for a blank residual, a cursor page, a disabled feature, or an exact result that already fills the requested page.
- Do not retry a completion synchronously in a user request.
- Date, time, and party size are independent; no date means Reservation is not called.
- Search may import only the public #378 service and DTOs, never Reservation entities or repositories.
- Never send user identity, history, contact data, exact location, reservation identity, or allergy/dietary input to OpenAI.
- Never log raw queries, prompts, provider bodies, concepts, API keys, store IDs, menu IDs, or user IDs as metric labels.
- The feature defaults to disabled and the API key comes only from `OPENAI_API_KEY`/deployment Secret configuration.
- No Qdrant, embeddings, menu indexing events, index rebuild job, semantic deployment service, or persistent raw-query cache remains.
- Run focused unit tests and affected integration classes locally; GitHub CI is the full-suite authority.

---

### Task 1: Rebase and Lock the #378 Partial Reservation Baseline

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/IntegratedSearchInterpreter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/IntegratedSearchInterpreterTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java`

**Interfaces:**
- Consumes: `ReservationSearchAvailabilityService.getAvailabilities(List<Long>, ReservationSearchAvailabilityCondition)` from merged #378.
- Produces: partial `InterpretedSearchCondition` values and `availabilityById(...)` behavior used unchanged by later LLM supplementation.

- [ ] **Step 1: Rebase the two local commits onto current `origin/dev`**

```powershell
git fetch origin dev:refs/remotes/origin/dev
git rebase origin/dev
```

Resolve conflicts against current `dev`; preserve the design document and the partial-reservation portions of `d381d163`, but do not preserve Qdrant code merely to resolve a conflict.

- [ ] **Step 2: Add failing interpreter regression tests**

Add focused assertions equivalent to:

```java
@Test
void dateOnlyLeavesMenuKeywordAndDoesNotWarnAboutIncompleteReservation() {
    InterpretationResult result = interpreter.interpret("서울 내일 김치찌개");

    assertThat(result.condition().reservationDate()).isEqualTo(LocalDate.of(2026, 8, 19));
    assertThat(result.condition().reservationTime()).isNull();
    assertThat(result.condition().partySize()).isNull();
    assertThat(result.condition().remainingKeyword()).isEqualTo("김치찌개");
    assertThat(result.warnings()).extracting(InterpretationWarning::code)
            .doesNotContain(WarningCode.INCOMPLETE_RESERVATION_CONDITION);
}
```

Also cover date+time, date+party, date+time+party, and time/party without a date.

- [ ] **Step 3: Run the interpreter test and confirm the incomplete-condition behavior fails on `dev` semantics**

```powershell
cd backend
.\gradlew.bat test --tests "*IntegratedSearchInterpreterTest"
```

Expected before the minimal change: the date-only assertion fails because `interpretCompleteReservation` leaves the combined reservation expression unresolved or emits `INCOMPLETE_RESERVATION_CONDITION`.

- [ ] **Step 4: Switch integrated interpretation to independent parsing**

Use the non-complete-only rule entry point:

```java
return ruleInterpreter.interpret(
        new InterpretationRequest(searchInput, vocabulary, SEOUL_ZONE));
```

- [ ] **Step 5: Add failing search-service tests for the public #378 contract**

Cover these concrete calls:

```java
new ReservationSearchAvailabilityCondition(date, null, null, null, false);
new ReservationSearchAvailabilityCondition(date, time, null, null, false);
new ReservationSearchAvailabilityCondition(date, null, null, 2, false);
new ReservationSearchAvailabilityCondition(date, time, null, 2, false);
```

Assert date absence produces `NOT_REQUESTED` and no Reservation interaction; date presence allows `availableOnly=true`; missing only time or party is not rejected; mismatched result order/count fails the search batch closed.

- [ ] **Step 6: Run the service tests and confirm the old complete-only validation fails**

```powershell
.\gradlew.bat test --tests "*IntegratedStoreSearchServiceTest"
```

Expected before the minimal change: date-only/date+time/date+party cases fail validation or avoid the #378 service.

- [ ] **Step 7: Replace the old Reservation dependency and complete-only gate**

Inject `ReservationSearchAvailabilityService`, define reservation requested as `condition.reservationDate() != null`, and construct:

```java
new ReservationSearchAvailabilityCondition(
        condition.reservationDate(),
        condition.reservationTime(),
        null,
        condition.partySize(),
        includesInfants);
```

Validate `includesInfants` and `availableOnly` only require a date. Keep result count/order validation and current store-state reconciliation.

- [ ] **Step 8: Run the focused partial-reservation tests**

```powershell
.\gradlew.bat test --tests "*IntegratedSearchInterpreterTest" --tests "*IntegratedStoreSearchServiceTest"
```

Expected: PASS.

- [ ] **Step 9: Commit the partial-reservation baseline**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/service/IntegratedSearchInterpreter.java backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java backend/src/test/java/com/miriyum/domain/search/service/IntegratedSearchInterpreterTest.java backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java
git commit -m "feat(search): 부분 예약 조건을 공개 가용성 계약에 연동"
```

### Task 2: Replace the Vector Draft with Provider-neutral LLM Contracts

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/search/config/OpenAiSearchInterpretationProperties.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/config/SearchInterpretationHttpConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptPurpose.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansion.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptInterpreter.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptFailureReason.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptProviderException.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansionService.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/config/OpenAiSearchInterpretationPropertiesTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/expansion/SearchConceptExpansionServiceTest.java`
- Delete: `backend/src/main/java/com/miriyum/domain/search/config/OpenAiEmbeddingProperties.java`
- Delete: `backend/src/main/java/com/miriyum/domain/search/config/QdrantSemanticSearchProperties.java`
- Delete: `backend/src/main/java/com/miriyum/domain/search/config/SemanticRebuildSchedulingConfig.java`
- Delete: `backend/src/main/java/com/miriyum/domain/search/config/SemanticSearchHttpConfig.java`
- Delete: `backend/src/main/java/com/miriyum/domain/search/semantic/**`
- Delete: `backend/src/test/java/com/miriyum/domain/search/semantic/**`
- Restore to current `dev`: `backend/src/main/java/com/miriyum/domain/menu/service/MenuCommandService.java`
- Restore to current `dev`: `backend/src/main/java/com/miriyum/domain/menu/service/MenuScheduleActivator.java`
- Restore to current `dev`: `backend/src/test/java/com/miriyum/domain/menu/service/MenuCommandServiceTest.java`
- Restore to current `dev`: `backend/src/test/java/com/miriyum/domain/menu/service/MenuScheduleActivatorTest.java`
- Restore to current `dev`: `deploy/local/docker-compose.dev.yml`
- Restore to current `dev`: `deploy/.env.example`

**Interfaces:**
- Produces: `SearchConceptExpansionService.expand(SearchConceptRequest)` returning an immutable, validated expansion or `SearchConceptExpansion.empty()`.
- Consumes later: a concrete `SearchConceptInterpreter` OpenAI adapter from Task 3.

- [ ] **Step 1: Write failing value/configuration tests**

Test immutability, validation, and normalization:

```java
assertThatThrownBy(() -> new SearchConceptRequest(" ", STORE_SEARCH))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(new SearchConceptExpansion(
        List.of(" 김치찌개 ", "김치찌개", "찌개"), 12, 4).concepts())
        .containsExactly("김치찌개", "찌개");
assertThatThrownBy(() -> enabledPropertiesWithBlankKey())
        .isInstanceOf(IllegalArgumentException.class);
```

Properties use prefix `miriyum.store-search.llm` and validate positive timeouts, `1..8` concepts, a small positive output-token limit, and a positive supplemental candidate limit.

- [ ] **Step 2: Write failing orchestration and metrics tests**

With `SimpleMeterRegistry`, assert:

```java
assertThat(service.expand(request).concepts()).containsExactly("김치찌개", "찌개");
assertThat(registry.counter("miriyum.search.llm.calls", "purpose", "store_search").count())
        .isEqualTo(1.0);
assertThat(registry.counter("miriyum.search.llm.outcomes",
        "purpose", "store_search", "outcome", "timeout").count()).isEqualTo(1.0);
```

Also assert disabled mode returns empty without invoking the port, provider exceptions return empty, and token summaries record input/output counts without text labels.

- [ ] **Step 3: Run the new tests and confirm missing types**

```powershell
.\gradlew.bat test --tests "*OpenAiSearchInterpretationPropertiesTest" --tests "*SearchConceptExpansionServiceTest"
```

Expected: compilation fails because the new contracts do not exist.

- [ ] **Step 4: Implement the minimal contracts and validation**

Use these signatures:

```java
public enum SearchConceptPurpose { STORE_SEARCH, MENU_ALTERNATIVE }

public record SearchConceptRequest(String text, SearchConceptPurpose purpose) { }

public record SearchConceptExpansion(
        List<String> concepts,
        long inputTokens,
        long outputTokens
) {
    public static SearchConceptExpansion empty() {
        return new SearchConceptExpansion(List.of(), 0, 0);
    }
}

public interface SearchConceptInterpreter {
    SearchConceptExpansion interpret(SearchConceptRequest request);
}

public enum SearchConceptFailureReason {
    REFUSAL, TIMEOUT, MALFORMED_RESPONSE, HTTP_ERROR, PROVIDER_ERROR
}

public final class SearchConceptProviderException extends RuntimeException {
    private final SearchConceptFailureReason reason;

    public SearchConceptProviderException(SearchConceptFailureReason reason) {
        super("search concept provider unavailable");
        this.reason = Objects.requireNonNull(reason);
    }

    public SearchConceptFailureReason reason() {
        return reason;
    }
}
```

Normalize concepts in the expansion constructor: trim, collapse whitespace, discard blanks, preserve first occurrence case-insensitively, cap individual length, and copy the list. Enforce the configured final count in `SearchConceptExpansionService`.

- [ ] **Step 5: Implement bounded observability and fallback**

`SearchConceptExpansionService` checks `properties.enabled()`, starts a Micrometer timer, increments one call counter, catches `SearchConceptProviderException` by its enum reason, catches remaining runtime failures as `provider_error`, and returns `empty()`. Record token counts only after success.

- [ ] **Step 6: Remove the vector/index lifecycle**

Delete the listed semantic/config/test files. Remove menu after-commit index events and scheduling hooks by restoring those four menu files to the current `origin/dev` behavior. Restore Qdrant deployment files to current `origin/dev`; no Qdrant service, port, volume, or credential remains in the branch diff.

- [ ] **Step 7: Run core tests and compile**

```powershell
.\gradlew.bat test --tests "*OpenAiSearchInterpretationPropertiesTest" --tests "*SearchConceptExpansionServiceTest"
.\gradlew.bat compileJava
```

Expected: PASS.

- [ ] **Step 8: Commit the provider-neutral core**

```powershell
git add -A -- backend/src/main/java/com/miriyum/domain/search backend/src/test/java/com/miriyum/domain/search backend/src/main/java/com/miriyum/domain/menu/service backend/src/test/java/com/miriyum/domain/menu/service deploy/local/docker-compose.dev.yml deploy/.env.example
git commit -m "refactor(search): 벡터 초안을 LLM 해석 계약으로 교체"
```

### Task 3: Implement the OpenAI Structured Outputs Adapter

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreter.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreterTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/config/SearchInterpretationHttpConfig.java`

**Interfaces:**
- Consumes: `OpenAiSearchInterpretationProperties` and `SearchConceptRequest`.
- Produces: `SearchConceptInterpreter.interpret(...)` over `POST /v1/chat/completions`.

- [ ] **Step 1: Write WireMock success and request-minimization tests**

Stub a response shaped as:

```json
{
  "choices": [{
    "finish_reason": "stop",
    "message": {"content": "{\"concepts\":[\"김치찌개\",\"찌개\"]}", "refusal": null}
  }],
  "usage": {"prompt_tokens": 130, "completion_tokens": 20, "total_tokens": 150}
}
```

Verify the request contains model `gpt-4o-mini`, `response_format.type=json_schema`, `strict=true`, `additionalProperties=false`, `maxItems=8`, the configured output-token ceiling, and only the supplied residual text. Verify the bearer key is sent as a header and absent from captured application logs.

- [ ] **Step 2: Write failure-shape tests**

Cover refusal, empty choices, `finish_reason=length`, malformed content JSON, more than eight concepts, HTTP 429/500, and response timeout. Assert each becomes `SearchConceptProviderException` with the matching bounded reason.

- [ ] **Step 3: Run the adapter tests and confirm failure**

```powershell
.\gradlew.bat test --tests "*OpenAiSearchConceptInterpreterTest"
```

Expected: compilation fails because the adapter does not exist.

- [ ] **Step 4: Configure the short-lived RestClient**

Build a named `RestClient` with a `java.net.http.HttpClient` connect timeout and `JdkClientHttpRequestFactory` read timeout. Do not configure automatic completion retries.

- [ ] **Step 5: Implement strict Chat Completions parsing**

Send a fixed system instruction that permits only Korean menu, ingredient, taste, and cooking-form concepts and explicitly forbids allergy/dietary/availability inference. Parse `message.content` with `ObjectMapper` into:

```java
private record ConceptDocument(List<String> concepts) { }
```

Reject refusal, non-stop finish, null usage, invalid schema, and oversized arrays. Return provider token usage in `SearchConceptExpansion`.

- [ ] **Step 6: Run the adapter and orchestration tests**

```powershell
.\gradlew.bat test --tests "*OpenAiSearchConceptInterpreterTest" --tests "*SearchConceptExpansionServiceTest"
```

Expected: PASS.

- [ ] **Step 7: Commit the adapter**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/config/SearchInterpretationHttpConfig.java backend/src/main/java/com/miriyum/domain/search/expansion backend/src/test/java/com/miriyum/domain/search/expansion
git commit -m "feat(search): OpenAI 구조화 검색어 해석기를 추가"
```

### Task 4: Add Current-MySQL Concept Candidate Queries

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicatesTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java`

**Interfaces:**
- Produces: `List<IntegratedStoreSearchCandidate> searchExpanded(IntegratedStoreSearchQuery query, List<String> concepts, int limit)`.
- Consumes later: `IntegratedStoreSearchService` in Task 5.

- [ ] **Step 1: Add failing predicate and MySQL integration tests**

Create current published fixtures for `김치찌개`, an obsolete unpublished `김치찌개` version, a closed store, and an unrelated menu. With residual `얼큰한 국물` and concepts `김치찌개`, `찌개`, assert only the current public kimchi-stew store is returned. Also assert region/category/price filters still apply and `%`, `_`, `!` remain literal LIKE input.

- [ ] **Step 2: Run the affected tests and confirm no expanded query exists**

```powershell
.\gradlew.bat test --tests "*IntegratedStoreSearchPredicatesTest"
.\gradlew.bat integrationTest --tests "*IntegratedStoreSearchRepositoryIT"
```

Expected: compilation fails on `searchExpanded`.

- [ ] **Step 3: Implement a structural predicate without the original residual restriction**

Add a predicate builder that applies approved/public store state and the query's region, store category, menu category, tag, and price conditions, then requires at least one current published visible menu matching any validated concept in name, description, primary category, secondary category, or local tag. Do not use the unresolved original phrase as an AND predicate for this query.

- [ ] **Step 4: Implement a bounded deterministic candidate pool**

Use the same `IntegratedStoreSearchCandidate` projection and current-coordinate rules. Order expanded candidates by first matching concept position, then store name and ID; limit to `min(limit, properties.supplementCandidateLimit())` at the caller. Return the full bounded pool without truncating to remaining page slots before refresh/reservation filtering.

- [ ] **Step 5: Run the focused repository tests**

```powershell
.\gradlew.bat test --tests "*IntegratedStoreSearchPredicatesTest"
.\gradlew.bat integrationTest --tests "*IntegratedStoreSearchRepositoryIT"
```

Expected: PASS.

- [ ] **Step 6: Commit the MySQL expansion query**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicatesTest.java backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java
git commit -m "feat(search): LLM 개념을 최신 MySQL 후보로 조회"
```

### Task 5: Supplement Under-filled Store Search Results

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java`

**Interfaces:**
- Consumes: `SearchConceptExpansionService.expand(new SearchConceptRequest(residual, STORE_SEARCH))` and `repository.searchExpanded(...)`.
- Produces: exact-first, first-response-only `IntegratedStoreSearchData` with unchanged exact cursor semantics.

- [ ] **Step 1: Write failing call-gate tests**

Assert no expansion interaction for blank residual, non-null cursor, exact page already filled, disabled/empty expansion, and a candidate scan stopped by the security limit. Assert exactly one expansion call after the exact path is exhausted and the first page remains under-filled.

- [ ] **Step 2: Write failing merge and revalidation tests**

Use exact store 1 and expanded stores 1, 2, and 3. Make store 2 stale after refresh and store 3 available through #378. Assert the final IDs are exact store 1 then expanded store 3; duplicate 1 is removed; the raw expanded pool is not truncated before refresh; and the response cursor remains the exact-path cursor value (`null` after exhaustion).

Also cover date-only, date+time, and date+party supplemental candidates and fail-closed reservation result mismatch.

- [ ] **Step 3: Run the service test and confirm failure**

```powershell
.\gradlew.bat test --tests "*IntegratedStoreSearchServiceTest"
```

Expected: new expansion interaction assertions fail.

- [ ] **Step 4: Implement post-exact supplementation**

Track whether the exact scan exhausted normally. After exact filtering, call expansion only when:

```java
cursor == null
        && exactExhausted
        && items.size() < requestedSize
        && !condition.remainingKeyword().isBlank();
```

Fetch the bounded expanded pool, deduplicate against existing store IDs, refresh current state, evaluate #378 once for the remaining pool, refresh again, apply `availableOnly`, and append until the page is full.

- [ ] **Step 5: Preserve recommendation behavior**

If `RECOMMENDATION_DESC` exact ranking under-fills after exhausting exact candidates, rank only the validated supplemental pool with the existing `StoreRecommendationService`, append that ranked tier after exact ranked items, and keep exact-first precedence. Do not make a second LLM call.

- [ ] **Step 6: Run focused search tests**

```powershell
.\gradlew.bat test --tests "*IntegratedStoreSearchServiceTest" --tests "*IntegratedSearchInterpreterTest"
```

Expected: PASS.

- [ ] **Step 7: Commit integrated supplementation**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java
git commit -m "feat(search): 부족한 검색 결과를 LLM 개념으로 보완"
```

### Task 6: Reuse One Expansion for Unavailable-menu Alternatives

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/dto/contract/MenuAlternativeSourceView.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/MenuAlternativeCandidateRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/MenuAlternativeCandidateQueryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/contract/MenuAlternativeCandidatePublicContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/MenuAlternativeCandidateQueryServiceTest.java`

**Interfaces:**
- Produces: `MenuAlternativeSourceView.searchConcepts()` while retaining an overload compatible with existing callers.
- Produces: concept-aware same-store and nearby repository queries against current published versions.
- Consumes: `SearchConceptExpansionService` exactly once from `findSource(...)`.

- [ ] **Step 1: Write failing one-call and merge tests**

When `findSource` loads a sold-out or paused `김치찌개`, assert one `MENU_ALTERNATIVE` request is built from current catalog-owned name, description, primary/secondary categories, and local tags. Then call both same-store and nearby methods and verify the interpreter still has exactly one interaction because both reuse `source.searchConcepts()`.

Assert concept candidates are placed before generic fallback candidates, deduplicated by `(storeId, menuId)`, and bounded by the shared candidate limit.

- [ ] **Step 2: Write failing current-version MySQL tests**

Add same-store and nearby fixtures containing a selling current `돼지고기 김치찌개`, an obsolete matching version, a paused candidate, and a mismatched store/menu tuple. Assert only the current selling tuple is returned as an alternative. Assert a source menu may be `SOLD_OUT` or `PAUSED`, while candidates must be `SELLING`.

- [ ] **Step 3: Run focused tests and confirm failure**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeCandidateQueryServiceTest" --tests "*MenuAlternativeCandidatePublicContractTest"
.\gradlew.bat integrationTest --tests "*IntegratedStoreSearchRepositoryIT"
```

Expected: source concepts and concept candidate methods do not exist.

- [ ] **Step 4: Extend the immutable source contract compatibly**

Add `List<String> searchConcepts` as the final record component, defensively copy it, and retain the existing constructor signature:

```java
public MenuAlternativeSourceView(
        long storeId, String storeName, long menuId, String menuName, int unitPrice,
        String primaryCategoryCode, List<String> secondaryCategoryCodes,
        String allergenInformationStatus, List<MenuAlternativeAllergenView> allergens,
        BigDecimal latitude, BigDecimal longitude
) {
    this(storeId, storeName, menuId, menuName, unitPrice, primaryCategoryCode,
            secondaryCategoryCodes, allergenInformationStatus, allergens,
            latitude, longitude, List.of());
}
```

- [ ] **Step 5: Add source text and concept candidate repository methods**

Return an internal `MenuAlternativeInterpretationText` record containing current name, description, primary category, secondary categories, and local tags. Concept predicates must include `(menuId, storeId, publishedVersionNumber)` through the current-version join, exclude the source menu, require current public/open/eligible stores and `SELLING` candidates, and retain same-store or bounding-box constraints.

- [ ] **Step 6: Expand once and reuse**

`findSource` loads the source and interpretation text, calls expansion once, and returns a copied source view containing the concepts. `findSameStoreCandidates` and `findNearbyCandidates` query concept matches first, append generic existing candidates, deduplicate, and cap after merging. Empty/failing expansion preserves the existing generic behavior.

- [ ] **Step 7: Run focused alternative tests**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeCandidateQueryServiceTest" --tests "*MenuAlternativeCandidatePublicContractTest"
.\gradlew.bat integrationTest --tests "*IntegratedStoreSearchRepositoryIT"
```

Expected: PASS.

- [ ] **Step 8: Commit menu alternative integration**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/dto/contract/MenuAlternativeSourceView.java backend/src/main/java/com/miriyum/domain/search/repository/MenuAlternativeCandidateRepository.java backend/src/main/java/com/miriyum/domain/search/service/MenuAlternativeCandidateQueryService.java backend/src/test/java/com/miriyum/domain/search/contract/MenuAlternativeCandidatePublicContractTest.java backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java backend/src/test/java/com/miriyum/domain/search/service/MenuAlternativeCandidateQueryServiceTest.java
git commit -m "feat(search): 품절 메뉴 대체 후보를 LLM 개념으로 보완"
```

### Task 7: Wire Configuration, Contracts, Documentation, and Live Diagnostic

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/ai/implementation-guardrails.md`
- Create: `backend/src/test/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreterLiveTest.java`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/07-data-and-api-contracts.md`
- Modify: `docs/09-quality-operations-and-rules.md`
- Modify: `docs/service-policies/13-ad-recommendation.md`
- Modify: `docs/service-policies/17-privacy-security.md`
- Modify: `docs/service-policies/18-scale-reliability.md`
- Modify: `docs/adr/ADR-002-staged-technology-adoption.md`
- Modify: `docs/adr/ADR-007-unified-search-mysql.md`
- Modify: `docs/adr/ADR-009-hybrid-semantic-search-qdrant.md`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`

**Interfaces:**
- Produces: disabled-by-default environment configuration and the final written operational/API contract.
- Consumes: all implementation behavior from Tasks 1-6.

- [ ] **Step 1: Add disabled-by-default application settings**

```yaml
miriyum:
  store-search:
    llm:
      enabled: ${MIRIYUM_STORE_SEARCH_LLM_ENABLED:false}
      base-url: ${MIRIYUM_STORE_SEARCH_LLM_BASE_URL:https://api.openai.com}
      api-key: ${OPENAI_API_KEY:}
      model: ${MIRIYUM_STORE_SEARCH_LLM_MODEL:gpt-4o-mini}
      connect-timeout-ms: ${MIRIYUM_STORE_SEARCH_LLM_CONNECT_TIMEOUT_MS:1000}
      response-timeout-ms: ${MIRIYUM_STORE_SEARCH_LLM_RESPONSE_TIMEOUT_MS:2000}
      max-output-tokens: ${MIRIYUM_STORE_SEARCH_LLM_MAX_OUTPUT_TOKENS:100}
      max-concepts: ${MIRIYUM_STORE_SEARCH_LLM_MAX_CONCEPTS:8}
      supplement-candidate-limit: ${MIRIYUM_STORE_SEARCH_LLM_CANDIDATE_LIMIT:200}
```

- [ ] **Step 2: Add an opt-in live diagnostic**

Use `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")` and assert an actual `gpt-4o-mini` response for `얼큰한 국물` intersects the approved retrieval keys used by the MySQL fixture test (`김치찌개`, `찌개`, or an equivalent normalized stew concept). The repository integration test from Task 4 separately proves those keys retrieve the current fixture, so the two tests form one end-to-end acceptance chain without making a live provider test start MySQL. Do not print the prompt, response, or key. Keep this test outside the default CI requirement.

- [ ] **Step 3: Update the canonical documents**

Record independent reservation fields, date-absent `NOT_REQUESTED`, exact-first MySQL behavior, first-response-only non-pageable LLM supplementation, strict eight-concept schema, current-version revalidation, one-call alternative reuse, cost metrics, privacy exclusions, fallback, and feature-flag rollback. Rewrite ADR-009's decision content to reject Qdrant for this stage based on the observed Korean retrieval mismatch even though its historical filename remains unchanged.

- [ ] **Step 4: Run contract and configuration-focused tests**

```powershell
.\gradlew.bat test --tests "*StoreSearchControllerTest" --tests "*StoreSearchOpenApiContractTest" --tests "*OpenAiSearchInterpretationPropertiesTest"
```

Expected: PASS.

- [ ] **Step 5: Run the live diagnostic explicitly when `OPENAI_API_KEY` is present**

```powershell
.\gradlew.bat test --tests "*OpenAiSearchConceptInterpreterLiveTest"
```

Expected with a key: PASS and no raw provider content in output. Expected without a key: SKIPPED.

- [ ] **Step 6: Commit configuration and contracts**

```powershell
git add -- backend/src/main/resources/application.yml backend/ai/implementation-guardrails.md backend/src/test/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreterLiveTest.java docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/09-quality-operations-and-rules.md docs/service-policies/13-ad-recommendation.md docs/service-policies/17-privacy-security.md docs/service-policies/18-scale-reliability.md docs/adr/ADR-002-staged-technology-adoption.md docs/adr/ADR-007-unified-search-mysql.md docs/adr/ADR-009-hybrid-semantic-search-qdrant.md docs/specs/store-search/spec.md docs/specs/store-search/openapi.yaml
git commit -m "docs(search): LLM 보완 검색 운영 계약을 반영"
```

### Task 8: Focused Verification and Review Gate

**Files:**
- Verify only; modify the relevant task-owned files if a focused failure exposes a defect.

**Interfaces:**
- Consumes: complete implementation.
- Produces: evidence for review and later GitHub CI.

- [ ] **Step 1: Run all affected non-integration tests**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.search.*"
```

Expected: PASS. This remains a domain-focused subset, not the full backend suite.

- [ ] **Step 2: Run the affected MySQL integration class**

```powershell
.\gradlew.bat integrationTest --tests "*IntegratedStoreSearchRepositoryIT"
```

Expected: PASS.

- [ ] **Step 3: Compile and inspect the final diff**

```powershell
.\gradlew.bat compileJava
cd ..
git diff --check origin/dev...HEAD
git status --short
git diff --name-only origin/dev...HEAD
```

Expected: compile succeeds, diff check is clean, worktree is clean after commits, and no Qdrant/embedding/menu-index/deploy artifact appears in the final feature diff.

- [ ] **Step 4: Search for forbidden residuals and sensitive logging**

```powershell
rg -n "Qdrant|text-embedding|SemanticMenuIndex|MenuSemanticIndex|OPENAI_API_KEY.*(log|print)|prompt.*(log|print)|response.*(log|print)" backend docs deploy
```

Expected: only ADR evidence explaining the rejected vector approach is present; no production vector code or raw provider logging remains.

- [ ] **Step 5: Request code review against `origin/dev`**

Use `superpowers:requesting-code-review` and require reviewers to check partial reservation semantics, one-call gates, MySQL current-version predicates, cursor behavior, alternative candidate tuple integrity, privacy, token metrics, and provider fallback.

- [ ] **Step 6: Address Important/Critical findings with focused red-green tests**

For each accepted finding, add a reproducing focused test, run it red, make the smallest implementation correction, and rerun only the affected unit/integration class.

- [ ] **Step 7: Record verification status**

Report focused local commands and results. Report the full backend suite as pending until GitHub CI runs; do not substitute a local `build`, `check`, full `integrationTest`, or all integration shards.
