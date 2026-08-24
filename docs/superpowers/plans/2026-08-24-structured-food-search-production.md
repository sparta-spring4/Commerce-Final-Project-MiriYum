# Structured Food Search Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자연어를 메뉴 계열과 음식 속성으로 분리하고, 같은 현재 공개 메뉴의 구조화 근거로 후보를 생성·정렬해 production acceptable Recall@20을 높인다.

**Architecture:** 결정적 `food-evidence-v1` 사전이 원문을 먼저 구조화하고 LLM은 비어 있는 차원만 허용 사전 안에서 보충한다. QueryDSL은 같은 current published visible 메뉴에서 메뉴 계열 또는 서로 다른 핵심 속성 2개 이상을 확인하고 `(structuredRelevance, legacyRelevance)`를 cursor-safe 정렬 키로 사용한다. 추천은 공용 `history-v1` 계산을 그대로 호출한 뒤 같은 음식 관련도 그룹 안에서만 그 순서를 사용한다.

**Tech Stack:** Java 21, Spring Boot, QueryDSL/JPA, MySQL Testcontainers, JUnit 5, AssertJ, Mockito, WireMock, Python 3.11, NumPy, unittest

**Spec:** `docs/superpowers/specs/2026-08-24-structured-food-search-production-design.md`

## Global Constraints

- 소유 Issue는 `#625`이며, Issue에 명시된 경로 밖 파일은 수정 전에 allowlist를 먼저 갱신한다.
- DB schema와 Flyway, 공개 요청·응답 shape, OpenAI 모델, embedding 및 외부 index/cache를 변경하지 않는다.
- `면`, `탕`, `국`, `밥` 같은 일반 형태 토큰 하나만으로 후보를 만들지 않는다.
- 메뉴명이 없는 질의는 재료·맛·국물·조리법·향·식감 중 서로 다른 차원 2개 이상이 같은 current published visible 메뉴에서 일치해야 한다.
- 결정적 메뉴명·별칭은 LLM 추론보다 우선하며 LLM menu family를 사용자 명시 메뉴로 승격하지 않는다.
- 폐점·미승인·비공개 매장과 retired·hidden·과거 메뉴 버전은 후보와 근거 모두에서 제외한다.
- `relevance,desc`와 `recommendation,desc`만 음식 관련도 우선순위를 적용하고 name/created-at 명시 정렬은 기존 계약을 유지한다.
- 공용 recommendation ranker 계산은 변경하지 않고 동일 음식 관련도 그룹 안에서만 `history-v1` 순서를 사용한다.
- 로컬에서는 관련 unit test와 `IntegratedStoreSearchRepositoryIT`만 실행하고 전체 backend suite는 GitHub CI를 authoritative 결과로 둔다.
- 기존 2,000×5 checkpoint를 재사용하며 신규 OpenAI chat/embedding 호출과 추가 비용은 0이어야 한다.
- simulated 91.20%와 actual application 결과를 같은 수치로 표기하지 않는다.

---

## File Map

### 새 production 타입

- `backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidence.java`: 차원별 정규화 근거, source, fill-only 병합 계약.
- `backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidenceSource.java`: `DETERMINISTIC`, `LLM` provenance.
- `backend/src/main/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabulary.java`: `food-evidence-v1` 메뉴/속성 사전과 별칭 정규화.
- `backend/src/main/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractor.java`: longest-first 결정적 추출.
- `backend/src/main/java/com/miriyum/domain/search/service/StructuredSearchRelevance.java`: 음식 관련도와 legacy tier의 추천 그룹 비교.

### 수정할 production 타입

- `SearchConceptExpansion`, `OpenAiSearchConceptInterpreter`, `SearchConceptExpansionService`: strict structured provider schema와 결정적 fill-only 병합.
- `SearchVocabularyProvider`: 응답 vocabulary version을 `catalog-v1+food-evidence-v1`로 고정.
- `IntegratedStoreSearchQuery`: 구조화 근거를 불변 조회 입력으로 전달.
- `IntegratedSearchCursor`, `IntegratedSearchCursorCodec`: `v2` cursor에 structured relevance를 인증.
- `IntegratedStoreSearchPredicates`, `IntegratedStoreSearchRepository`, `IntegratedStoreSearchCandidate`: 같은 메뉴 gate, 정적 점수, seek/order/projection.
- `IntegratedStoreSearchService`: 결정적 추출, LLM 보충, 음식 그룹 우선 추천, `food-evidence-v1+history-v1` 노출.

### 계약·평가

- `docs/specs/store-search/spec.md`, `docs/specs/store-search/openapi.yaml`, `docs/specs/store-recommendation/spec.md`, `docs/adr/ADR-009-hybrid-semantic-search-qdrant.md`: 정본 계약.
- `performance/search-eval-v2/src/miriyum_search_eval/hybrid_search.py`, `cli.py`, 관련 tests: actual production 규칙 H 재현.
- `docs/performance/search-evaluation-v2.md`: simulated G와 actual H를 분리한 결과.

---

### Task 1: 구조화 근거 값과 결정적 음식 사전

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidence.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidenceSource.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabulary.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractor.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabularyTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractorTest.java`

**Interfaces:**
- Produces: `StructuredFoodEvidence.empty()`, `fillEmptyDimensionsFrom(...)`, `hasCandidateEvidence()`, `deterministicMenuTerms()`, `llmMenuTerms()`, `coreDimensionTerms()`.
- Produces: `FoodEvidenceVocabulary.VERSION = "food-evidence-v1"` and `resolve(Dimension, String, StructuredFoodEvidenceSource)`.
- Produces: `DeterministicFoodEvidenceExtractor.extract(String)`.

- [ ] **Step 1: Write failing vocabulary and extractor tests**

```java
@Test
void separatesMenuTasteAndIngredientWithoutLosingRawSpan() {
    StructuredFoodEvidence result = extractor.extract("칼칼한 해물 마라탕");
    assertThat(result.rawFoodSpans()).extracting(EvidenceTerm::surface)
            .containsExactly("칼칼한 해물 마라탕");
    assertThat(result.menuFamilies()).extracting(EvidenceTerm::id)
            .containsExactly("MALATANG");
    assertThat(result.tastes()).extracting(EvidenceTerm::id)
            .containsExactly("SPICY_SHARP");
    assertThat(result.ingredients()).extracting(EvidenceTerm::id)
            .containsExactly("SEAFOOD");
}

@Test
void normalizesBidirectionalAliasesButRejectsGenericFormAsCoreEvidence() {
    assertThat(extractor.extract("뼈다귀 해장국").menuFamilies())
            .extracting(EvidenceTerm::id).containsExactly("BONE_HANGOVER_SOUP");
    assertThat(extractor.extract("멸치국수").menuFamilies())
            .extracting(EvidenceTerm::id).containsExactly("BANQUET_NOODLES");
    assertThat(extractor.extract("면").hasCandidateEvidence()).isFalse();
}
```

- [ ] **Step 2: Run the new tests and confirm they fail because the types do not exist**

Run from `backend`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.interpreter.FoodEvidenceVocabularyTest" --tests "com.miriyum.domain.search.interpreter.DeterministicFoodEvidenceExtractorTest"
```

Expected: compilation failure for missing production types.

- [ ] **Step 3: Implement immutable evidence and vocabulary**

Use this public shape so later tasks consume one consistent contract:

```java
public record StructuredFoodEvidence(
        List<EvidenceTerm> rawFoodSpans,
        List<EvidenceTerm> menuFamilies,
        List<EvidenceTerm> ingredients,
        List<EvidenceTerm> tastes,
        List<EvidenceTerm> broths,
        List<EvidenceTerm> methods,
        List<EvidenceTerm> aromas,
        List<EvidenceTerm> textures,
        List<EvidenceTerm> forms) {

    public enum Dimension {
        RAW_FOOD_SPAN, MENU_FAMILY, INGREDIENT, TASTE, BROTH,
        METHOD, AROMA, TEXTURE, FORM
    }

    public record EvidenceTerm(
            String id,
            String surface,
            List<String> matchTerms,
            StructuredFoodEvidenceSource source) { }
}
```

`EvidenceTerm`은 blank/null을 거부하고 match term을 trim·공백 정규화·대소문자 비의존 중복 제거한다. `StructuredFoodEvidence`는 모든 list를 방어 복사하고 차원별 ID를 한 번만 유지한다. `fillEmptyDimensionsFrom`은 결정적 차원이 비어 있을 때만 동일 LLM 차원을 복사하며 raw span과 결정적 menu family를 교체하지 않는다.

`FoodEvidenceVocabulary`는 최소한 다음 검증 고정 항목을 포함한다.

```java
entry(MENU_FAMILY, "JJAMPPONG", "짬뽕", "불향 해물 짬뽕", "옛날 짬뽕");
entry(MENU_FAMILY, "BONE_HANGOVER_SOUP", "뼈해장국", "뼈다귀 해장국");
entry(MENU_FAMILY, "BANQUET_NOODLES", "잔치국수", "멸치국수");
entry(MENU_FAMILY, "MALATANG", "마라탕");
entry(TASTE, "SPICY_SHARP", "칼칼한", "얼큰한");
entry(INGREDIENT, "SEAFOOD", "해물", "해산물");
```

최소 예시만 넣고 끝내지 않는다. 평가 corpus의 사람이 검토한 고정 `BASE_DISHES` 50개에 대응하는 기본 메뉴 계열과 별칭 전부, 그리고 그 사전에서 사용하는 재료·맛·국물·조리법 값 전부를 production 상수로 옮긴다. `AROMAS` 15개와 `TEXTURES` 20개도 명시 상수로 옮긴다. 평가 Python 모듈이나 생성된 family ID를 runtime에서 import하지 않으며, `고소한/칼칼한/담백한/수제/직화 + 기본 메뉴` 조합은 prefix를 맛·조리·향 차원으로 분리해 약 300개 합성 계열을 50개 기본 메뉴 family로 정규화한다.

형태 denylist는 `Set.of("면", "탕", "국", "밥")`으로 고정한다. 추출은 차원별 alias 길이 내림차순으로 수행하고 같은 차원 안에서 긴 겹침을 먼저 채택한다. 서로 다른 차원의 span은 독립적으로 유지해 `불향 해물 짬뽕`에서 메뉴 계열·향·재료를 동시에 잃지 않는다.

- [ ] **Step 4: Run tests and confirm green**

Run the Step 2 command. Expected: both test classes PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidence.java backend/src/main/java/com/miriyum/domain/search/expansion/StructuredFoodEvidenceSource.java backend/src/main/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabulary.java backend/src/main/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractor.java backend/src/test/java/com/miriyum/domain/search/interpreter/FoodEvidenceVocabularyTest.java backend/src/test/java/com/miriyum/domain/search/interpreter/DeterministicFoodEvidenceExtractorTest.java
git commit -m "feat(search): 구조화 음식 근거 사전 추가"
```

### Task 2: LLM strict structured output와 fill-only 병합

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansion.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansionService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreterTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/expansion/SearchConceptExpansionServiceTest.java`

**Interfaces:**
- Consumes: Task 1 evidence/vocabulary/extractor.
- Produces: `SearchConceptExpansion(List<String>, StructuredFoodEvidence, long, long)` plus the existing three-argument compatibility constructor.
- Produces: `expand(SearchConceptRequest, StructuredFoodEvidence)`; existing `expand(SearchConceptRequest)` delegates with empty evidence.

- [ ] **Step 1: Write failing provider schema and merge tests**

```java
@Test
void storeSearchRequestsStrictFoodDimensionsAndPreservesUsage() {
    stubMatchable("""
        {"interpretation":"MATCHABLE","concepts":["마라탕"],
         "rawFoodSpans":["칼칼한 마라탕"],"menuFamilies":["마라탕"],
         "ingredients":[],"tastes":["칼칼한"],"broths":[],
         "methods":[],"aromas":[],"textures":[],"forms":["탕"]}
        """);
    SearchConceptExpansion result = interpreter.interpret(storeSearch("칼칼한 마라탕"));
    assertThat(result.foodEvidence().menuFamilies()).extracting(EvidenceTerm::source)
            .containsOnly(StructuredFoodEvidenceSource.LLM);
    assertThat(result.inputTokens()).isPositive();
}

@Test
void deterministicDimensionsWinAndLlmOnlyFillsEmptyDimensions() {
    StructuredFoodEvidence deterministic = extractor.extract("칼칼한 마라탕");
    given(interpreter.interpret(any())).willReturn(llmEvidence(
            menu("짬뽕"), taste("달콤한"), ingredient("해물")));
    SearchConceptExpansion result = service.expand(request(), deterministic);
    assertThat(result.foodEvidence().menuFamilies()).extracting(EvidenceTerm::id)
            .containsExactly("MALATANG");
    assertThat(result.foodEvidence().tastes()).extracting(EvidenceTerm::id)
            .containsExactly("SPICY_SHARP");
    assertThat(result.foodEvidence().ingredients()).extracting(EvidenceTerm::id)
            .containsExactly("SEAFOOD");
}
```

Also assert disabled/provider-error/AMBIGUOUS/NO_FOOD_SIGNAL returns deterministic evidence without a provider-derived explicit menu.

- [ ] **Step 2: Run focused tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.expansion.OpenAiSearchConceptInterpreterTest" --tests "com.miriyum.domain.search.expansion.SearchConceptExpansionServiceTest"
```

Expected: constructor/schema/merge assertions fail.

- [ ] **Step 3: Implement purpose-specific schema and bounded normalization**

For `STORE_SEARCH`, require all fields below with `additionalProperties=false`; every array has `maxItems=maxConcepts` and string `maxLength=60`.

```json
{
  "required": ["interpretation", "concepts", "rawFoodSpans", "menuFamilies",
    "ingredients", "tastes", "broths", "methods", "aromas", "textures", "forms"]
}
```

Keep `MENU_ALTERNATIVE` on its existing concepts-only schema. Parse MATCHABLE arrays into LLM evidence, discard every structured array for AMBIGUOUS/NO_FOOD_SIGNAL, and preserve provider usage on all valid responses. In `SearchConceptExpansionService`, resolve each LLM term through `FoodEvidenceVocabulary`; unknown terms and generic forms are discarded. Merge by calling `deterministic.fillEmptyDimensionsFrom(validatedLlm)`.

- [ ] **Step 4: Run focused tests and existing menu-alternative regression**

Run the Step 2 command plus:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.service.MenuAlternativeCandidateQueryServiceTest"
```

Expected: all PASS and the three-argument `SearchConceptExpansion` callers still compile.

- [ ] **Step 5: Commit Task 2**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansion.java backend/src/main/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreter.java backend/src/main/java/com/miriyum/domain/search/expansion/SearchConceptExpansionService.java backend/src/test/java/com/miriyum/domain/search/expansion/OpenAiSearchConceptInterpreterTest.java backend/src/test/java/com/miriyum/domain/search/expansion/SearchConceptExpansionServiceTest.java
git commit -m "feat(search): LLM 음식 근거를 빈 차원에만 병합"
```

### Task 3: Query와 v2 cursor에 구조화 정렬 키 전달

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/search/service/StructuredSearchRelevance.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/service/StructuredSearchRelevanceTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/query/IntegratedStoreSearchQuery.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/query/IntegratedSearchCursor.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/query/IntegratedSearchCursorCodec.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/query/IntegratedStoreSearchQueryTest.java`

**Interfaces:**
- Produces: `IntegratedStoreSearchQuery.foodEvidence()` and a new `from(..., List<String> explicitMenuNames, StructuredFoodEvidence evidence, ...)` overload.
- Produces: `IntegratedSearchCursor(int structuredRelevance, int relevanceTier, String sortValue, long storeId)`.
- Produces: `StructuredSearchRelevance.of(int structuredRelevance, int relevanceTier)`, natural descending food-group comparison, and cursor group comparison.

- [ ] **Step 1: Write failing query, cursor, and group-order tests**

```java
@Test
void v2CursorPreservesStructuredAndLegacyRelevance() {
    IntegratedStoreSearchQuery query = queryWith(extractor.extract("칼칼한 마라탕"));
    String cursor = codec.encode(query, 32, 2, "마라집", 7L);
    IntegratedStoreSearchQuery decoded = withCursor(query, cursor);
    assertThat(decoded.cursor().orElseThrow().structuredRelevance()).isEqualTo(32);
    assertThat(decoded.cursor().orElseThrow().relevanceTier()).isEqualTo(2);
}

@Test
void foodGroupAlwaysPrecedesHistoryWithinLowerGroup() {
    assertThat(StructuredSearchRelevance.of(20, 1)
            .compareTo(StructuredSearchRelevance.of(10, 4))).isLessThan(0);
}
```

Add a literal signed `v1` cursor fixture and assert it is rejected with `COMMON_001`/validation failure.

- [ ] **Step 2: Run focused tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.query.IntegratedStoreSearchQueryTest" --tests "com.miriyum.domain.search.service.StructuredSearchRelevanceTest"
```

- [ ] **Step 3: Implement immutable query evidence and cursor v2**

Set cursor `VERSION="v2"`; payload fields are:

```text
v2.fingerprint.sort.structuredRelevance.relevanceTier.base64(sortValue).storeId.signature
```

Validate `structuredRelevance` in `0..36`, legacy tier in `0..4`, and retain the 1,024-character cap and constant-time signature check. Old query factory overloads pass `StructuredFoodEvidence.empty()` so unaffected callers compile. The query fingerprint need not duplicate evidence because evidence is deterministically derived from `remainingKeyword`, which is already fingerprinted.

- [ ] **Step 4: Run focused tests and confirm green**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/service/StructuredSearchRelevance.java backend/src/test/java/com/miriyum/domain/search/service/StructuredSearchRelevanceTest.java backend/src/main/java/com/miriyum/domain/search/query/IntegratedStoreSearchQuery.java backend/src/main/java/com/miriyum/domain/search/query/IntegratedSearchCursor.java backend/src/main/java/com/miriyum/domain/search/query/IntegratedSearchCursorCodec.java backend/src/test/java/com/miriyum/domain/search/query/IntegratedStoreSearchQueryTest.java
git commit -m "feat(search): 구조화 관련도를 cursor 계약에 포함"
```

### Task 4: 같은 메뉴 2차원 후보 gate와 QueryDSL 정렬

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchCandidate.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicatesTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java`

**Interfaces:**
- Consumes: query evidence and v2 cursor from Task 3.
- Produces: `IntegratedStoreSearchCandidate.structuredRelevance()` in range `0..36`.
- Produces: `searchExpanded(query, concepts, foodEvidence, limit)`; the existing three-argument overload delegates with empty evidence.

- [ ] **Step 1: Add failing predicate and MySQL integration cases**

```java
@Test
void attributesMustMatchTwoDistinctDimensionsOnTheSameCurrentMenu() {
    Store valid = storeWithCurrentMenu("칼칼한 해물탕", "칼칼한 해물 국물", tags("해물"));
    Store split = storeWithMenus(
            currentMenu("매운 국수", "칼칼한"),
            currentMenu("해물 튀김", "해물"));
    Store hidden = storeWithHiddenMenu("칼칼한 해물탕", "칼칼한 해물");
    List<IntegratedStoreSearchCandidate> result = repository.search(
            queryWith(evidence(taste("칼칼한"), ingredient("해물")))).content();
    assertThat(result).extracting(IntegratedStoreSearchCandidate::storeId)
            .contains(valid.getId())
            .doesNotContain(split.getId(), hidden.getId());
}

@Test
void explicitAliasThenFamilyThenDimensionCountThenLegacyTierDefinesOrder() {
    List<IntegratedStoreSearchCandidate> result = repository.search(
            queryWith(extractor.extract("칼칼한 뼈다귀 해장국"))).content();
    assertThat(result).extracting(IntegratedStoreSearchCandidate::structuredRelevance)
            .isSortedAccordingTo(Comparator.reverseOrder());
}
```

Add cases for retired/past version, closed/unapproved store, region/price/category filters, literal `%`/`_`, generic form-only input, and page 1/page 2 containing every tie exactly once.

- [ ] **Step 2: Run repository tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.repository.IntegratedStoreSearchPredicatesTest" --tests "com.miriyum.domain.search.repository.IntegratedStoreSearchRepositoryIT"
```

- [ ] **Step 3: Implement same-row evidence and score**

Inside one correlated current-menu subquery, build per-dimension boolean expressions against `name`, `description`, `primaryCategoryCode`, `secondaryCategoryCodes`, and `localTags`. Candidate eligibility is:

```text
explicit deterministic menu match
OR normalized menu-family match
OR ((ingredient+taste+broth+method+aroma+texture) distinct match count >= 2)
```

Never use reverse substring matching for attributes. Compute the maximum score on one current menu row with:

```text
menuRank * 10 + distinctCoreDimensionCount
menuRank: explicit exact/approved alias=3, explicit forward=2, LLM family=1, none=0
dimension count: 0..6
```

For `RELEVANCE_DESC` and repository scanning for `RECOMMENDATION_DESC`, order and seek by `structuredRelevance DESC`, `relevanceTier DESC`, `name ASC`, `storeId ASC`. Keep NAME/CREATED_AT order unchanged. Projection, refresh, supplemental forward/reverse paths, and cursor encoding must preserve the structured score.

- [ ] **Step 4: Run repository tests and confirm green**

Run the Step 2 command. Expected: PASS with Testcontainers MySQL.

- [ ] **Step 5: Commit Task 4**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchCandidate.java backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicates.java backend/src/main/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepository.java backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchPredicatesTest.java backend/src/test/java/com/miriyum/domain/search/repository/IntegratedStoreSearchRepositoryIT.java
git commit -m "feat(search): 같은 메뉴 구조화 근거로 후보 정렬"
```

### Task 5: 검색 서비스와 음식 우선 추천 연결

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java`

**Interfaces:**
- Consumes: extractor, structured expansion, repository score, relevance group.
- Produces: recommendation `rankingRuleVersion="food-evidence-v1+history-v1"`.

- [ ] **Step 1: Write failing service behavior tests**

```java
@Test
void deterministicEvidenceIsInEveryStaticScanAndLlmOnlySupplementsShortFirstPage() {
    service.search("칼칼한 해물 음식", false, false, "relevance,desc", null, 20);
    then(repository).should().search(argThat(query ->
            query.foodEvidence().tastes().size() == 1
                    && query.foodEvidence().ingredients().size() == 1));
}

@Test
void recommendationCannotMoveLowerFoodGroupAboveHigherGroup() {
    given(repository.search(any())).willReturn(slice(
            candidate(1L, 31, 2), candidate(2L, 10, 4)));
    given(recommendationService.rank(any(), any(), any(), any()))
            .willReturn(List.of(highHistoryStore2(), lowHistoryStore1()));
    IntegratedStoreSearchData result = service.search(
            9L, "칼칼한 마라탕", false, false, "recommendation,desc", null, 20);
    assertThat(result.items()).extracting(IntegratedStoreSearchItem::storeId)
            .containsExactly("1", "2");
    assertThat(result.rankingRuleVersion())
            .isEqualTo("food-evidence-v1+history-v1");
}
```

Add a cursor test whose first page ends inside a food group and verify the next page has no duplicate/skip. Add provider-error and unknown LLM term cases that return deterministic candidates.

- [ ] **Step 2: Run service tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.service.IntegratedStoreSearchServiceTest"
```

- [ ] **Step 3: Implement deterministic-first orchestration and stable grouping**

Inject `DeterministicFoodEvidenceExtractor`. Extract once from `condition.remainingKeyword()` and pass the same evidence to request and scan queries. On underfilled first page call `expand(request, deterministicEvidence)` and pass its structured result to `searchExpanded`.

Call `StoreRecommendationService.rank` once for the bounded candidate set. Apply a stable sort by `StructuredSearchRelevance.of(candidate.structuredRelevance(), candidate.relevanceTier())`; stability preserves the existing `history-v1` order inside equal groups. Recommendation cursor carries the group in its structured/legacy fields and the unchanged serialized `RecommendationCursorKey` in `sortValue`. Filter after-cursor candidates by group first, then by existing recommendation key.

Do not change `StoreRecommendationService`, `HistoryRecommendationRanker`, `RecommendationReason`, or their score limits.

- [ ] **Step 4: Run service plus repository regression tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.service.IntegratedStoreSearchServiceTest" --tests "com.miriyum.domain.search.repository.IntegratedStoreSearchRepositoryIT"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java backend/src/test/java/com/miriyum/domain/search/service/IntegratedStoreSearchServiceTest.java
git commit -m "feat(search): 음식 관련도 안에서만 개인화 정렬"
```

### Task 6: vocabulary/ranking 계약과 정본 문서

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/SearchVocabularyProvider.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/SearchVocabularyProviderTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/dto/publicapi/IntegratedStoreSearchDataTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchOpenApiContractTest.java`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/specs/store-recommendation/spec.md`
- Modify: `docs/adr/ADR-009-hybrid-semantic-search-qdrant.md`

**Interfaces:**
- Produces: `vocabularyVersion="catalog-v1+food-evidence-v1"`.
- Produces: recommendation-only `rankingRuleVersion="food-evidence-v1+history-v1"`; other sorts remain `null`.

- [ ] **Step 1: Update contract tests first**

```java
assertThat(vocabulary.version()).isEqualTo("catalog-v1+food-evidence-v1");
mockMvc.perform(get("/api/v1/stores/search")
        .param("q", "칼칼한 마라탕")
        .param("sort", "recommendation,desc"))
    .andExpect(jsonPath("$.data.rankingRuleVersion")
        .value("food-evidence-v1+history-v1"));
```

OpenAPI contract test must assert the schema description contains the exact new version and still marks the property nullable.

- [ ] **Step 2: Run contract tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.service.SearchVocabularyProviderTest" --tests "com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchDataTest" --tests "com.miriyum.domain.search.controller.publicapi.StoreSearchControllerTest" --tests "com.miriyum.domain.search.controller.publicapi.StoreSearchOpenApiContractTest"
```

- [ ] **Step 3: Update version constants and canonical contracts**

Document:

- exact/alias → family → same-menu distinct dimension count → legacy relevance order;
- non-relevance explicit sorts remain unchanged;
- recommendation uses food group first and `history-v1` inside equal groups;
- v1 cursor expiration and v2 HMAC fields without exposing raw user data;
- current visible published menu and hard filter requirements;
- no embedding/index/cache/DB migration and production metadata limitation.

- [ ] **Step 4: Run contract tests and confirm green**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 6**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/service/SearchVocabularyProvider.java backend/src/test/java/com/miriyum/domain/search/service/SearchVocabularyProviderTest.java backend/src/test/java/com/miriyum/domain/search/dto/publicapi/IntegratedStoreSearchDataTest.java backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchControllerTest.java backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchOpenApiContractTest.java docs/specs/store-search/spec.md docs/specs/store-search/openapi.yaml docs/specs/store-recommendation/spec.md docs/adr/ADR-009-hybrid-semantic-search-qdrant.md
git commit -m "docs(search): 구조화 음식 정렬 계약 반영"
```

### Task 7: 기존 simulated D/E/F/G 평가기를 production 브랜치로 이식

**Files:**
- Create/Modify from known commits: `performance/search-eval-v2/src/miriyum_search_eval/hybrid_search.py`
- Modify: `performance/search-eval-v2/src/miriyum_search_eval/cli.py`
- Create/Modify: `performance/search-eval-v2/tests/test_hybrid_search.py`
- Modify: `performance/search-eval-v2/tests/test_workflow.py`
- Modify: `docs/performance/search-evaluation-v2.md`

**Interfaces:**
- Consumes: committed evaluation commits `fb4a514e`, `4ed515cc`, `50bb146a`, `1e636ace` from `feature/625-structured-search-eval`.
- Produces: reproducible D/E/F/G baseline on the production branch before actual H is added.

- [ ] **Step 1: Cherry-pick the four already verified evaluation commits in order**

```powershell
git cherry-pick fb4a514e 4ed515cc 50bb146a 1e636ace
```

Expected: only Issue #625 evaluation paths and `docs/performance/search-evaluation-v2.md` change.

- [ ] **Step 2: Run the complete evaluation harness unit suite**

Run from repository root:

```powershell
uv run --project performance/search-eval-v2 python -m unittest discover -s performance/search-eval-v2/tests -v
```

Expected: 98 tests PASS, 0 failures/errors, no provider call.

- [ ] **Step 3: Verify the recorded hybrid artifact hashes without regenerating paid checkpoints**

```powershell
uv run --project performance/search-eval-v2 python -m miriyum_search_eval hash-artifact --artifact-dir performance/search-eval-v2/artifacts/eval-20260824-gold-v2-1-cutoffs/hybrid-reanalysis
```

Expected: `sha256.json` is recreated locally; `results.jsonl` and `aggregate.json` remain the D/E/F/G source artifact. Do not stage ignored artifacts.

### Task 8: actual production H 규칙의 무과금 재평가

**Files:**
- Modify: `performance/search-eval-v2/src/miriyum_search_eval/hybrid_search.py`
- Modify: `performance/search-eval-v2/src/miriyum_search_eval/cli.py`
- Modify: `performance/search-eval-v2/tests/test_hybrid_search.py`
- Modify: `performance/search-eval-v2/tests/test_workflow.py`
- Modify: `docs/performance/search-evaluation-v2.md`

**Interfaces:**
- Consumes: Task 1 vocabulary IDs, Task 4 candidate gate/score, existing 2,000×5 checkpoint.
- Produces: variant `H_ACTUAL_FOOD_EVIDENCE_V1` labeled `actual-application-predicate-food-evidence-v1`, never `simulated`.

- [ ] **Step 1: Write failing evaluator parity tests**

```python
def test_actual_h_requires_two_dimensions_on_the_same_current_visible_menu(self):
    result = evaluate_actual_food_evidence(
        query=evidence(tastes=["칼칼한"], ingredients=["해물"]),
        menus=[
            current_menu("매운 국수", tastes=["칼칼한"]),
            current_menu("해물 튀김", ingredients=["해물"]),
        ],
    )
    self.assertEqual(result, [])

def test_actual_h_orders_explicit_alias_family_dimensions_then_legacy(self):
    ranked = rank_actual_food_evidence(candidates_fixture())
    self.assertEqual([item["evidenceKind"] for item in ranked[:4]],
                     ["explicit_alias", "family", "dimensions_3", "dimensions_2"])
```

Add negative tests for closed/unapproved store, hidden/retired/past menu, general token-only query, and filter violation; add cutoff assertions for @1/@3/@5/@8/@20/@50/all.

- [ ] **Step 2: Run evaluator tests and confirm red**

```powershell
uv run --project performance/search-eval-v2 python -m unittest discover -s performance/search-eval-v2/tests -p "test_hybrid_search.py" -v
uv run --project performance/search-eval-v2 python -m unittest discover -s performance/search-eval-v2/tests -p "test_workflow.py" -v
```

Expected: at least one assertion fails because variant H and its production-parity functions do not exist yet.

- [ ] **Step 3: Implement H with exact production parity and provenance**

H must use only menu name/description/primary category/secondary categories/tags, current visibility/version/store status, the same aliases and denylist as production, and the exact score `menuRank*10+dimensionCount`. Do not use synthetic family IDs or gold labels as candidate evidence. Write metadata fields:

```python
metadata = {
    "variant": "H_ACTUAL_FOOD_EVIDENCE_V1",
    "actualApplication": True,
    "productionCommitSha": subprocess.run(
        ["git", "rev-parse", "HEAD"], check=True,
        capture_output=True, text=True,
    ).stdout.strip(),
    "newProviderCalls": 0,
    "newEmbeddingCalls": 0,
}
```

The runtime fills `productionCommitSha`; it is not hard-coded in source.

- [ ] **Step 4: Run all 98+ evaluator tests**

Run the Task 7 discovery command. Expected: all PASS.

- [ ] **Step 5: Run the no-cost H reanalysis on the exact artifact**

```powershell
uv run --project performance/search-eval-v2 python -m miriyum_search_eval hybrid-reanalyze --artifact-dir performance/search-eval-v2/artifacts/eval-20260824-gold-v2-1-cutoffs --source-artifact-dir performance/search-eval-v2/artifacts/eval-20260824-post-merge
```

Expected: 2,000 unique queries, 10,000 reused calls, new provider calls 0, output under `hybrid-reanalysis`, and H separated from D/E/F/G.

- [ ] **Step 6: Inspect hard safety gates before accepting H**

Read `hybrid-reanalysis/aggregate.json` and verify:

- true-no-answer false positives remain 0;
- all negative false positives do not exceed the recorded baseline 3;
- closed/unapproved store, hidden/past menu, and region/price/category/filter leakage remain 0;
- explicit-menu and filter-defense query types do not regress;
- actual acceptable @20 improves over actual baseline; report the observed value even if below 90%.

If any hard gate fails, stop production activation and retain the result as failed actual evidence.

- [ ] **Step 7: Update evaluation report and commit**

Record D/E/F/G simulated and H actual in separate rows, all cutoffs, MRR/nDCG, stability, failures, new call count/cost, commit SHA, artifact hashes, and production metadata limitation.

```powershell
git add -- performance/search-eval-v2/src/miriyum_search_eval/hybrid_search.py performance/search-eval-v2/src/miriyum_search_eval/cli.py performance/search-eval-v2/tests/test_hybrid_search.py performance/search-eval-v2/tests/test_workflow.py docs/performance/search-evaluation-v2.md
git commit -m "test(search): production 구조화 검색 actual 재평가"
```

### Task 9: 범위·비밀값·최종 targeted 검증

**Files:**
- Verify only; edit only an already allowed file when a check exposes a defect.

**Interfaces:**
- Produces: merge-ready evidence or an explicit blocked report.

- [ ] **Step 1: Run all affected backend unit tests together**

Run from `backend`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.interpreter.*" --tests "com.miriyum.domain.search.expansion.*Test" --tests "com.miriyum.domain.search.query.IntegratedStoreSearchQueryTest" --tests "com.miriyum.domain.search.service.StructuredSearchRelevanceTest" --tests "com.miriyum.domain.search.service.SearchVocabularyProviderTest" --tests "com.miriyum.domain.search.service.IntegratedStoreSearchServiceTest" --tests "com.miriyum.domain.search.dto.publicapi.IntegratedStoreSearchDataTest" --tests "com.miriyum.domain.search.controller.publicapi.StoreSearchControllerTest" --tests "com.miriyum.domain.search.controller.publicapi.StoreSearchOpenApiContractTest"
```

Expected: PASS.

- [ ] **Step 2: Run the affected MySQL integration class only**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.search.repository.IntegratedStoreSearchRepositoryIT"
```

Expected: PASS. Do not run `build`, `check`, full `integrationTest`, or shards A-D locally.

- [ ] **Step 3: Run the full evaluation harness tests once more**

Run the Task 7 unittest discovery command. Expected: all PASS.

- [ ] **Step 4: Verify scope, whitespace, and secret hygiene**

Run from repository root:

```powershell
git diff origin/dev...HEAD --name-only
git diff origin/dev...HEAD --check
rg -n "sk-[A-Za-z0-9_-]+|Bearer [A-Za-z0-9._-]+|OPENAI_API_KEY=" backend/src performance/search-eval-v2 docs
```

Expected: every changed path appears in Issue #625; diff check is empty; secret scan finds no real key/header/value. Test literals such as `Bearer test-secret` are acceptable only inside existing WireMock assertions.

- [ ] **Step 5: Capture final evidence**

Record branch, HEAD, base SHA, targeted test counts, integration result, H call count, retry/failure count, cost 0, strict/acceptable cutoffs, safety gates, artifact SHA-256, and CI status. State full backend verification as `PENDING GitHub CI` until CI completes.

- [ ] **Step 6: Request code review before push/PR**

Use `superpowers:requesting-code-review` on `origin/dev...HEAD`. Resolve findings with `superpowers:receiving-code-review`, rerun the smallest affected tests, and do not push or create a PR without explicit user authorization.
