# RuleInterpreter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한국어 통합 검색 입력을 외부 AI 없이 승인된 조건, warning과 남은 키워드로 결정적으로 변환하는 순수 Java `RuleInterpreter`를 구현한다.

**Architecture:** 공개 불변 record가 입력·사전·결과 계약을 소유하고, package-private 정규화기와 종류별 parser가 원문 span 후보를 생성한다. `RuleInterpreter`가 후보를 결정적으로 해결해 승인된 span만 제거하며 조회·HTTP·Spring runtime 책임은 갖지 않는다.

**Tech Stack:** Java 21, JUnit 5, AssertJ, committed Gradle 9.6.1 Wrapper. Spring context, Testcontainers, QueryDSL과 새 Gradle 의존성은 사용하지 않는다.

## Global Constraints

- 변경 경로는 GitHub Issue #110의 `backend/src/main/java/com/miriyum/domain/store/search/interpreter/**`, 대응 테스트, 이 계획 문서로 제한한다.
- `RuleInterpreter`는 `Clock`을 생성자로 받고 요청의 명시적 `ZoneId`를 사용하며 JVM 기본 시간대를 읽지 않는다.
- 지역·매장 카테고리·메뉴 카테고리·태그 code와 alias는 버전된 `SearchVocabulary` 입력만 사용한다.
- SQL, JPQL, QueryDSL predicate, HTTP DTO, 외부 provider payload와 로그를 생성하지 않는다.
- 미해석·모호·충돌 표현은 추측하거나 삭제하지 않고 warning과 `remainingKeyword`로 보존한다.
- 같은 원문·사전·규칙 버전·Clock 시각·ZoneId에는 같은 값과 순서의 결과를 반환한다.
- 구현은 TDD red–green–refactor 순서를 지키며 각 red 실행에서 의도한 실패 메시지를 확인한다.

---

## File Map

### Public contracts

- `InterpretationRequest.java`: 원문, 승인 사전와 `ZoneId` 입력
- `SearchVocabulary.java`: 사전 버전과 네 종류의 승인 항목, 구성 검증
- `VocabularyEntry.java`: 불변 code·alias
- `PriceRange.java`: 원화 최소·최대 포함 경계
- `InterpretedSearchCondition.java`: 허용 code·수치·날짜·시각·잔여 키워드
- `InterpretationResult.java`: 규칙·사전 버전, 조건과 warning
- `InterpretationWarning.java`: 원문 없는 warning code·field
- `WarningCode.java`, `WarningField.java`: 안정된 기계 판독 enum
- `RuleInterpreter.java`: 공개 해석 진입점과 후보 해결

### Package-private collaborators

- `SearchInputNormalizer.java`: NFKC, 제어 문자와 공백 정규화
- `TextSpan.java`: 정규화 문자열의 반열린 구간
- `MatchedToken.java`: parser 값과 입력 span
- `DictionaryMatcher.java`: 독립 어휘·최장 alias 기반 사전 후보
- `PriceParser.java`: 원화 정확값·상하한·범위 후보와 모호 가격 warning
- `PartySizeParser.java`: `N명` 후보와 0·overflow warning
- `DateParser.java`: 상대·절대 날짜 후보와 모호 날짜 warning
- `TimeParser.java`: 24시간제·오전/오후 후보와 모호 시각 warning

### Tests

- `SearchVocabularyTest.java`: 사전 불변성·중복·모호 설정 검증
- `RuleInterpreterNormalizationTest.java`: Unicode·공백·전각 문자·빈 입력
- `RuleInterpreterDictionaryTest.java`: 사전 종류, 중복, 순서, 겹침과 잔여 키워드
- `RuleInterpreterNumericTest.java`: 가격·인원·overflow·충돌
- `RuleInterpreterTemporalTest.java`: 고정 Clock·ZoneId, 날짜·시각·모호·충돌
- `RuleInterpreterRegressionTest.java`: 복합 조건, 결정성, SQL injection 보존과 logging 부재 경계

---

### Task 1: 불변 사전과 결과 계약

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/VocabularyEntry.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/SearchVocabulary.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/PriceRange.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/WarningCode.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/WarningField.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/InterpretationWarning.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/InterpretedSearchCondition.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/InterpretationResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/InterpretationRequest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/SearchVocabularyTest.java`

**Interfaces:**
- Consumes: Java `List`, `LocalDate`, `LocalTime`, `ZoneId`
- Produces: `SearchVocabulary(String version, List<VocabularyEntry> regions, List<VocabularyEntry> storeCategories, List<VocabularyEntry> menuCategories, List<VocabularyEntry> tags)` and the public result records used by every later task

- [ ] **Step 1: Write failing vocabulary validation and immutability tests**

```java
@Test
@DisplayName("사전 목록과 alias를 방어 복사한다")
void defensivelyCopiesVocabulary() {
    List<String> aliases = new ArrayList<>(List.of("성수"));
    List<VocabularyEntry> regions = new ArrayList<>(
            List.of(new VocabularyEntry("REGION_SEONGSU", aliases)));

    SearchVocabulary vocabulary = new SearchVocabulary("catalog-v1", regions, List.of(), List.of(), List.of());
    aliases.add("서울숲");
    regions.clear();

    assertThat(vocabulary.regions()).hasSize(1);
    assertThat(vocabulary.regions().getFirst().aliases()).containsExactly("성수");
    assertThatThrownBy(() -> vocabulary.regions().add(new VocabularyEntry("X", List.of("x"))))
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
@DisplayName("같은 종류에서 정규화 alias가 다른 code를 가리키면 거부한다")
void rejectsAmbiguousAliasWithinType() {
    assertThatThrownBy(() -> new SearchVocabulary(
            "catalog-v1",
            List.of(
                    new VocabularyEntry("REGION_A", List.of("ＡBC")),
                    new VocabularyEntry("REGION_B", List.of("abc"))),
            List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("alias");
}
```

- [ ] **Step 2: Run the focused test and confirm the red state**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.SearchVocabularyTest"
```

Expected: compilation fails because `VocabularyEntry` and `SearchVocabulary` do not exist.

- [ ] **Step 3: Add the exact public record signatures and validation**

```java
public record VocabularyEntry(String code, List<String> aliases) {
    public VocabularyEntry {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (aliases == null || aliases.isEmpty()) {
            throw new IllegalArgumentException("aliases must not be empty");
        }
        aliases = List.copyOf(aliases);
        if (aliases.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
            throw new IllegalArgumentException("alias must not be blank");
        }
    }
}
```

Use these remaining signatures without Lombok or Spring annotations:

```java
public record InterpretationRequest(String rawInput, SearchVocabulary vocabulary, ZoneId zoneId) {}
public record PriceRange(Long minInclusive, Long maxInclusive) {}
public record InterpretationWarning(WarningCode code, WarningField field) {}
public record InterpretedSearchCondition(
        List<String> regionCodes,
        List<String> storeCategoryCodes,
        List<String> menuCategoryCodes,
        List<String> tagCodes,
        PriceRange priceRange,
        Integer partySize,
        LocalDate reservationDate,
        LocalTime reservationTime,
        String remainingKeyword) {}
public record InterpretationResult(
        String ruleVersion,
        String vocabularyVersion,
        InterpretedSearchCondition condition,
        List<InterpretationWarning> warnings) {}

public enum WarningCode {
    AMBIGUOUS_DICTIONARY_TERM,
    AMBIGUOUS_PRICE,
    CONFLICTING_PRICE,
    INVALID_PARTY_SIZE,
    CONFLICTING_PARTY_SIZE,
    AMBIGUOUS_DATE,
    CONFLICTING_DATE,
    AMBIGUOUS_TIME,
    CONFLICTING_TIME,
    OUT_OF_RANGE_NUMBER
}

public enum WarningField {
    DICTIONARY,
    PRICE,
    PARTY_SIZE,
    DATE,
    TIME
}
```

`SearchVocabulary` validates the nonblank version, copies every list, normalizes aliases with NFKC plus `Locale.ROOT` lowercase for duplicate detection, rejects duplicate code/alias entries, and rejects one normalized alias pointing to multiple codes within the same list. Result records copy all returned lists and reject null required values. `PriceRange` rejects two null bounds, negative bounds and `minInclusive > maxInclusive`.

- [ ] **Step 4: Run the focused test and confirm green**

Run the command from Step 2.

Expected: `BUILD SUCCESSFUL` and all `SearchVocabularyTest` cases pass.

- [ ] **Step 5: Commit the contracts**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/SearchVocabularyTest.java
git commit -m "feat(store): 검색 해석 불변 계약 추가"
```

---

### Task 2: 입력 정규화와 빈 해석

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/SearchInputNormalizer.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/TextSpan.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/MatchedToken.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/RuleInterpreter.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterNormalizationTest.java`

**Interfaces:**
- Consumes: Task 1의 `InterpretationRequest`, `InterpretationResult`, `InterpretedSearchCondition`
- Produces: `public RuleInterpreter(Clock clock)` and `public InterpretationResult interpret(InterpretationRequest request)`

- [ ] **Step 1: Write failing normalization tests**

```java
private final RuleInterpreter interpreter = new RuleInterpreter(
        Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

@Test
@DisplayName("전각 문자와 제어 문자를 NFKC와 공백으로 정규화한다")
void normalizesCompatibilityCharactersAndWhitespace() {
    InterpretationResult result = interpreter.interpret(new InterpretationRequest(
            "  ＡＢＣ\t１２３\n파스타  ", emptyVocabulary(), ZoneId.of("Asia/Seoul")));

    assertThat(result.condition().remainingKeyword()).isEqualTo("ABC 123 파스타");
}

@Test
@DisplayName("null 원문은 잘못된 호출로 거부한다")
void rejectsNullInput() {
    assertThatThrownBy(() -> interpreter.interpret(
            new InterpretationRequest(null, emptyVocabulary(), ZoneId.of("Asia/Seoul"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rawInput");
}
```

- [ ] **Step 2: Run the normalization test and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.RuleInterpreterNormalizationTest"
```

Expected: compilation fails because `RuleInterpreter` does not exist.

- [ ] **Step 3: Implement the normalizer and empty pipeline**

`SearchInputNormalizer.normalize(String)` must iterate code points, replace ISO control characters with a space, apply `Normalizer.Form.NFKC`, collapse `\s+` to one ASCII space, and trim. It returns the normalized display text; comparison-only lowercase is created by the dictionary matcher.

```java
public final class RuleInterpreter {
    public static final String RULE_VERSION = "rule-v1";
    private final Clock clock;

    public RuleInterpreter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public InterpretationResult interpret(InterpretationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String normalized = SearchInputNormalizer.normalize(request.rawInput());
        InterpretedSearchCondition condition = new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, normalized);
        return new InterpretationResult(
                RULE_VERSION, request.vocabulary().version(), condition, List.of());
    }
}
```

`TextSpan(int startInclusive, int endExclusive)` rejects negative, empty and reversed spans. `MatchedToken<T>(T value, TextSpan span)` rejects null components; later parsers share it.

- [ ] **Step 4: Run normalization tests and Task 1 tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.*"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit normalization**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterNormalizationTest.java
git commit -m "feat(store): 검색 입력 결정적 정규화"
```

---

### Task 3: 승인 사전 매칭과 code 순서

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/DictionaryMatcher.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/RuleInterpreter.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterDictionaryTest.java`

**Interfaces:**
- Consumes: `SearchVocabulary`의 네 목록과 `MatchedToken<String>`
- Produces: 네 종류의 입력 순서 보존 code 목록, accepted dictionary spans, `AMBIGUOUS_DICTIONARY_TERM`

- [ ] **Step 1: Write failing dictionary behavior tests**

```java
@Test
@DisplayName("지역과 카테고리와 태그를 추출하고 남은 키워드를 보존한다")
void extractsApprovedDictionaryCodes() {
    SearchVocabulary vocabulary = new SearchVocabulary(
            "catalog-v1",
            List.of(new VocabularyEntry("REGION_SEONGSU", List.of("성수", "성수동"))),
            List.of(new VocabularyEntry("STORE_RESTAURANT", List.of("식당"))),
            List.of(new VocabularyEntry("MENU_PASTA", List.of("파스타"))),
            List.of(new VocabularyEntry("MOOD_DATE", List.of("데이트"))));

    InterpretationResult result = interpret("성수동 데이트 파스타 맛집", vocabulary);

    assertThat(result.condition().regionCodes()).containsExactly("REGION_SEONGSU");
    assertThat(result.condition().menuCategoryCodes()).containsExactly("MENU_PASTA");
    assertThat(result.condition().tagCodes()).containsExactly("MOOD_DATE");
    assertThat(result.condition().remainingKeyword()).isEqualTo("맛집");
}

@Test
@DisplayName("같은 span이 서로 다른 사전 종류와 일치하면 어느 code도 선택하지 않는다")
void preservesCrossTypeAmbiguity() {
    SearchVocabulary vocabulary = new SearchVocabulary(
            "catalog-v1",
            List.of(),
            List.of(new VocabularyEntry("STORE_KOREAN", List.of("한식"))),
            List.of(new VocabularyEntry("MENU_KOREAN", List.of("한식"))),
            List.of());

    InterpretationResult result = interpret("한식 추천", vocabulary);

    assertThat(result.condition().storeCategoryCodes()).isEmpty();
    assertThat(result.condition().menuCategoryCodes()).isEmpty();
    assertThat(result.condition().remainingKeyword()).isEqualTo("한식 추천");
    assertThat(result.warnings()).containsExactly(new InterpretationWarning(
            WarningCode.AMBIGUOUS_DICTIONARY_TERM, WarningField.DICTIONARY));
}
```

- [ ] **Step 2: Run dictionary tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.RuleInterpreterDictionaryTest"
```

Expected: assertions fail because all terms remain keywords and no codes are returned.

- [ ] **Step 3: Implement longest independent-token matching and overlap resolution**

`DictionaryMatcher` builds normalized alias descriptors sorted by alias length descending and declaration order ascending. A match is allowed only when both adjacent code points, if present, are not letters or digits. It scans all occurrences, selects non-overlapping longest aliases within one kind, then returns tokens sorted by input start.

`RuleInterpreter` combines the four token lists. If tokens of different kinds overlap, mark all overlapping tokens rejected, add one dictionary ambiguity warning, and leave those spans untouched. Accepted code values use `LinkedHashSet` to remove duplicates while preserving first occurrence. Only accepted spans enter the removal set.

- [ ] **Step 4: Run all interpreter tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.*"
```

Expected: `BUILD SUCCESSFUL`, including longest alias (`성수동` before `성수`), duplicate code and input-order cases.

- [ ] **Step 5: Commit dictionary matching**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterDictionaryTest.java
git commit -m "feat(store): 승인 검색 사전 매칭"
```

---

### Task 4: 가격과 인원 해석

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/PriceParser.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/PartySizeParser.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/RuleInterpreter.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterNumericTest.java`

**Interfaces:**
- Consumes: normalized input
- Produces: inclusive `PriceRange`, positive `Integer partySize`, numeric accepted spans and warning values

- [ ] **Step 1: Write failing numeric tests**

```java
@Test
@DisplayName("가격 상하한을 교집합으로 결합하고 인원을 추출한다")
void combinesCompatiblePriceBoundsAndPartySize() {
    InterpretationResult result = interpret("1만원 이상 2만원 이하 3명 파스타");

    assertThat(result.condition().priceRange()).isEqualTo(new PriceRange(10_000L, 20_000L));
    assertThat(result.condition().partySize()).isEqualTo(3);
    assertThat(result.condition().remainingKeyword()).isEqualTo("파스타");
}

@Test
@DisplayName("모순 가격은 선택하지 않고 원문과 warning을 보존한다")
void preservesConflictingPriceExpressions() {
    InterpretationResult result = interpret("3만원 이상 2만원 이하 조용한 곳");

    assertThat(result.condition().priceRange()).isNull();
    assertThat(result.condition().remainingKeyword()).isEqualTo("3만원 이상 2만원 이하 조용한 곳");
    assertThat(result.warnings()).contains(new InterpretationWarning(
            WarningCode.CONFLICTING_PRICE, WarningField.PRICE));
}

@Test
@DisplayName("만원대와 서로 다른 인원은 추측하지 않는다")
void preservesAmbiguousPriceAndConflictingPartySize() {
    InterpretationResult result = interpret("2만원대 2명 4명 예약");

    assertThat(result.condition().priceRange()).isNull();
    assertThat(result.condition().partySize()).isNull();
    assertThat(result.condition().remainingKeyword()).isEqualTo("2만원대 2명 4명 예약");
    assertThat(result.warnings()).contains(
            new InterpretationWarning(WarningCode.AMBIGUOUS_PRICE, WarningField.PRICE),
            new InterpretationWarning(WarningCode.CONFLICTING_PARTY_SIZE, WarningField.PARTY_SIZE));
}
```

- [ ] **Step 2: Run numeric tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.RuleInterpreterNumericTest"
```

Expected: price and party assertions fail because numeric parsers are absent.

- [ ] **Step 3: Implement exact price and party grammar**

`PriceParser` recognizes:

```text
N원
N천원
N만원
N원|천원|만원 이상|이하|미만|초과
N~M원
N천원~M천원
N만원~M만원
N~M만원  (trailing unit applies to both endpoints)
```

Remove commas before `Long.parseLong`, multiply with `Math.multiplyExact`, and convert exclusive bounds with `Math.addExact` or `Math.subtractExact`. Overflow yields `OUT_OF_RANGE_NUMBER`; `N만원대` yields `AMBIGUOUS_PRICE`. Multiple compatible ranges are intersected. Empty intersection yields `CONFLICTING_PRICE` and consumes none of the price spans.

`PartySizeParser` recognizes an independent decimal integer followed by `명`. Zero and negative-looking `-N명` yield `INVALID_PARTY_SIZE`; integer overflow yields `OUT_OF_RANGE_NUMBER`. Repeated equal values consume all matching spans and return one value. Different values yield `CONFLICTING_PARTY_SIZE` and consume none.

- [ ] **Step 4: Run all interpreter tests**

Run the wildcard command from Task 3 Step 4.

Expected: `BUILD SUCCESSFUL`, including exact-boundary, exclusive-boundary and overflow cases.

- [ ] **Step 5: Commit numeric parsing**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterNumericTest.java
git commit -m "feat(store): 가격과 인원 규칙 해석"
```

---

### Task 5: 날짜와 시각 해석

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/DateParser.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/TimeParser.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/RuleInterpreter.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterTemporalTest.java`

**Interfaces:**
- Consumes: normalized input, injected `Clock`, request `ZoneId`
- Produces: one `LocalDate`, one `LocalTime`, temporal accepted spans and warning values

- [ ] **Step 1: Write failing temporal tests**

```java
@Test
@DisplayName("요청 시간대에서 상대 날짜와 오후 시각을 해석한다")
void interpretsRelativeDateInExplicitZone() {
    RuleInterpreter interpreter = new RuleInterpreter(
            Clock.fixed(Instant.parse("2026-08-04T15:30:00Z"), ZoneOffset.UTC));

    InterpretationResult result = interpreter.interpret(request(
            "오늘 오후 7시 30분 예약",
            ZoneId.of("Asia/Seoul")));

    assertThat(result.condition().reservationDate()).isEqualTo(LocalDate.of(2026, 8, 5));
    assertThat(result.condition().reservationTime()).isEqualTo(LocalTime.of(19, 30));
    assertThat(result.condition().remainingKeyword()).isEqualTo("예약");
}

@Test
@DisplayName("서로 다른 날짜와 모호 시각은 추측하지 않는다")
void preservesConflictingDateAndAmbiguousTime() {
    InterpretationResult result = interpret("오늘 내일 저녁쯤 파스타");

    assertThat(result.condition().reservationDate()).isNull();
    assertThat(result.condition().reservationTime()).isNull();
    assertThat(result.condition().remainingKeyword()).isEqualTo("오늘 내일 저녁쯤 파스타");
    assertThat(result.warnings()).contains(
            new InterpretationWarning(WarningCode.CONFLICTING_DATE, WarningField.DATE),
            new InterpretationWarning(WarningCode.AMBIGUOUS_TIME, WarningField.TIME));
}
```

- [ ] **Step 2: Run temporal tests and confirm red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.RuleInterpreterTemporalTest"
```

Expected: temporal assertions fail because date and time parsers are absent.

- [ ] **Step 3: Implement deterministic temporal grammar**

`DateParser` recognizes `오늘`, `내일`, `모레`, ISO `yyyy-MM-dd` and `yyyy년 M월 d일`. Relative dates use `LocalDate.now(clock.withZone(zoneId))`. Invalid calendar dates and yearless `M월 d일` produce `AMBIGUOUS_DATE`. Repeated equal dates deduplicate; different dates produce `CONFLICTING_DATE` and consume none.

`TimeParser` recognizes `HH:mm`, `오전 H시`, `오후 H시`, with optional `M분`. `오전 12시` becomes `00:00` and `오후 12시` becomes `12:00`. Invalid hours/minutes, `점심`, `저녁`, or recognized temporal text containing `쯤` produce `AMBIGUOUS_TIME`. Repeated equal times deduplicate; different times produce `CONFLICTING_TIME` and consume none.

- [ ] **Step 4: Run all interpreter tests**

Run the wildcard command from Task 3 Step 4.

Expected: `BUILD SUCCESSFUL` and fixed-Clock tests prove the JVM default timezone is irrelevant.

- [ ] **Step 5: Commit temporal parsing**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterTemporalTest.java
git commit -m "feat(store): 날짜와 시각 규칙 해석"
```

---

### Task 6: 통합 span 해결과 회귀 데이터셋

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/RuleInterpreter.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterRegressionTest.java`

**Interfaces:**
- Consumes: Tasks 3–5의 accepted token과 warnings
- Produces: 안정된 warning 순서와 승인 span만 제거한 최종 `InterpretationResult`

- [ ] **Step 1: Write failing end-to-end regression tests**

```java
@ParameterizedTest
@MethodSource("deterministicCases")
@DisplayName("고정 회귀 입력은 반복 실행해도 같은 결과를 만든다")
void returnsSameResultForSameSnapshot(InterpretationRequest request) {
    InterpretationResult first = interpreter.interpret(request);
    InterpretationResult second = interpreter.interpret(request);

    assertThat(second).isEqualTo(first);
}

private static Stream<InterpretationRequest> deterministicCases() {
    SearchVocabulary vocabulary = regressionVocabulary();
    ZoneId seoul = ZoneId.of("Asia/Seoul");
    return Stream.of(
            new InterpretationRequest("성수 성수 파스타", vocabulary, seoul),
            new InterpretationRequest("2명 4명 예약", vocabulary, seoul),
            new InterpretationRequest("알 수 없는 표현", vocabulary, seoul),
            new InterpretationRequest("", vocabulary, seoul),
            new InterpretationRequest("１２명 내일", vocabulary, seoul),
            new InterpretationRequest("오늘 오후 7시", vocabulary, ZoneId.of("America/New_York")));
}

@Test
@DisplayName("SQL injection 문자열은 실행 표현으로 바꾸지 않고 키워드에 보존한다")
void preservesSqlInjectionTextAsKeyword() {
    InterpretationResult result = interpret("성수 ' OR 1=1 -- 파스타");

    assertThat(result.condition().regionCodes()).containsExactly("REGION_SEONGSU");
    assertThat(result.condition().remainingKeyword()).isEqualTo("' OR 1=1 -- 파스타");
}

@Test
@DisplayName("복합 입력에서 승인된 span만 제거한다")
void interpretsCombinedInput() {
    InterpretationResult result = interpret("내일 성수 데이트 파스타 2명 1~2만원 오후 7시 조용한 곳");

    assertThat(result.condition()).isEqualTo(new InterpretedSearchCondition(
            List.of("REGION_SEONGSU"),
            List.of(),
            List.of("MENU_PASTA"),
            List.of("MOOD_DATE"),
            new PriceRange(10_000L, 20_000L),
            2,
            LocalDate.of(2026, 8, 5),
            LocalTime.of(19, 0),
            "조용한 곳"));
}
```

The method source must also cover duplicate categorical terms, conflicting numeric terms, unknown Korean text, empty input, full-width digits and different `ZoneId` values.

- [ ] **Step 2: Run regression tests and confirm any missing integration red**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.RuleInterpreterRegressionTest"
```

Expected: at least one integration assertion fails until cross-parser span ordering and residual cleanup are completed. If all tests pass immediately, temporarily change one expected value to prove the test detects a regression, observe the assertion failure, then restore it.

- [ ] **Step 3: Complete stable resolution and residual construction**

Sort accepted spans by start then end. Reject any cross-parser overlap rather than applying parser priority, retain the overlapping text, and add the field-appropriate ambiguity warning once. Mark accepted character indexes, replace only those indexes with spaces, then collapse whitespace without removing unmatched punctuation. Sort warnings by first source span, then by `WarningCode.ordinal()`; warnings without a unique span retain parser declaration order. Return all lists with `List.copyOf`.

Do not add logger fields, Spring annotations, SQL libraries or dependencies.

- [ ] **Step 4: Run focused package tests and compile**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.*"
.\gradlew.bat compileJava
```

Expected: both commands exit `0`; focused tests contain no Spring context or Testcontainers startup.

- [ ] **Step 5: Commit integrated interpreter**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/interpreter backend/src/test/java/com/miriyum/domain/store/search/interpreter/RuleInterpreterRegressionTest.java
git commit -m "test(store): 검색 해석 회귀 데이터셋 추가"
```

---

### Task 7: Issue allowlist와 backend 최종 검증

**Files:**
- Verify only: `backend/src/main/java/com/miriyum/domain/store/search/interpreter/**`
- Verify only: `backend/src/test/java/com/miriyum/domain/store/search/interpreter/**`
- Verify only: `docs/superpowers/specs/2026-08-04-rule-interpreter-design.md`
- Verify only: `docs/superpowers/plans/2026-08-04-rule-interpreter.md`

**Interfaces:**
- Consumes: all implementation commits
- Produces: reviewable #110 evidence without claiming an unobserved full-suite result

- [ ] **Step 1: Verify the exact changed path allowlist**

```powershell
git diff --name-only origin/dev...HEAD
```

Expected: every path starts with one of the four Issue #110 allowed prefixes above. Any other path must be removed from this branch without deleting user work.

- [ ] **Step 2: Run whitespace and focused verification**

```powershell
git diff --check origin/dev...HEAD
Set-Location backend
.\gradlew.bat test --tests "com.miriyum.domain.store.search.interpreter.*"
.\gradlew.bat compileJava
```

Expected: all commands exit `0`.

- [ ] **Step 3: Run the configured full backend test gate once**

```powershell
.\gradlew.bat test --no-daemon --max-workers=1
```

Expected: exit `0`. If Testcontainers MySQL startup again exceeds the execution window, capture process/thread evidence and report the full-suite result as `INCONCLUSIVE`; do not call it passing or failing without Gradle completion.

- [ ] **Step 4: Review requirements and repository state**

```powershell
Set-Location ..
git status --short --branch
git log --oneline origin/dev..HEAD
```

Expected: no uncommitted files; commits are scoped to #110; no AI, QueryDSL, Controller, Repository, external API, current-location or dependency changes exist.

- [ ] **Step 5: Request code review before integration**

Use `superpowers:requesting-code-review` against `origin/dev...HEAD`. Address only evidence-backed findings inside the Issue allowlist, rerun affected tests and preserve the final verification output for a later PR.
