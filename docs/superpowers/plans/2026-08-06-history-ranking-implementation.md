# History-v1 Recommendation Ranking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 통합 검색에 `sort=recommendation,desc`를 추가하고, 현재 검색 의도 최대 70점과 유효한 소비자 이력 최대 30점의 `history-v1`으로 결정적 순서와 실제 기여 요인 설명을 반환한다.

**Architecture:** `store/recommendation/ranking`은 불변 후보·이력 입력만 받는 순수 Java 랭커, Reservation·MenuHold 공개 계약으로 이력 스냅샷을 만드는 loader, Store 소유 QueryDSL 신호 조회, 검색 서비스 연결 조정자로 나눈다. 추천 정렬 요청은 기존 통합 검색 후보 상한 안에서 최신 Store·Reservation 상태를 재검증한 후보를 모두 모아 점수화하고, HMAC 검색 cursor에 마지막 추천 정렬 키를 넣어 다음 페이지를 결정한다. 공개 경로는 익명 콜드스타트와 선택적 소비자 JWT를 허용하되 Authorization 헤더가 잘못되었거나 namespace가 다르면 401을 반환한다.

**Tech Stack:** Java 21, Spring Boot, Spring Security, QueryDSL JPA, JUnit 5, AssertJ, Mockito, Testcontainers MySQL, OpenAPI 3.1, Gradle Wrapper

## Global Constraints

- 규칙 버전은 정확히 `history-v1`이다.
- 현재 검색 의도는 최대 70점, 개인 이력은 최대 30점이다.
- 최근 `FULFILLED` 예약은 최대 20건, MenuHold 공개 스냅샷 조회는 그중 최근 5건이다.
- `CANCELLED`, 실패, 노쇼, 미래 `CONFIRMED`, 단순 조회, 광고비, 광고 계약, 구독 상태, 결제 규모는 점수 입력이 아니다.
- 사용자 현재 위치, AI/LLM, 벡터 DB, 검색 클러스터, 추천 캐시와 새 영속 테이블을 추가하지 않는다.
- Reservation·MenuHold Entity/Repository를 직접 참조하지 않고 공개 Service·DTO만 사용한다.
- 같은 후보·이력 스냅샷, `asOf`, 규칙 버전은 같은 점수·순서·이유를 만든다.
- 동점은 총점 내림차순 → 매장 주 카테고리 일치 → 메뉴 보조 카테고리 일치 수 → 가용성 → 허용 거리 → storeId 오름차순이다.

---

### Task 1: 순수 `history-v1` 점수·동점·설명 코어

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationAvailability.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationCandidate.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationHistoryEvent.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationHistorySnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationReason.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RankedRecommendation.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationCursorKey.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/HistoryRecommendationRanker.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/HistoryRecommendationRankerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/RecommendationCursorKeyTest.java`

**Interfaces:**
- Consumes: 불변 `RecommendationCandidate`, `RecommendationHistorySnapshot`, 호출자가 고정한 `Instant asOf`.
- Produces: `HistoryRecommendationRanker.rank(List<RecommendationCandidate>, RecommendationHistorySnapshot, Instant)` → `List<RankedRecommendation>`; `RecommendationCursorKey.from(RankedRecommendation)`, `serialize()`, `parse(String,long)`, `isBefore(RankedRecommendation)`.

- [ ] **Step 1: 점수 상한과 현재 의도 우선 사례의 실패 테스트 작성**

  `HistoryRecommendationRankerTest`에 손으로 계산한 literal을 사용한다. 예를 들어 tier 4(32)+매장 카테고리(12)+메뉴 주분류(10)+보조 2개(8)+태그 3개(6)+AVAILABLE(2)는 현재 70점이며, 최근 동일 매장 2건(10+10, cap18)+동일 메뉴 2건(6+6)은 이력 30점, 총 100점이다. 현재 의도 70점 신규 매장은 현재 의도 32점+이력 30점 후보보다 앞서야 한다.

- [ ] **Step 2: RED 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.HistoryRecommendationRankerTest"`

  Expected: `HistoryRecommendationRanker`와 입력 record가 없어 compile failure.

- [ ] **Step 3: 최소 점수 구현**

  `RecommendationCandidate`는 `storeId`, `relevanceTier`, 매장/메뉴 주분류 일치 boolean, 메뉴 보조 카테고리·태그 일치 수, `RecommendationAvailability`, 선택적 `BigDecimal distanceMeters`, 현재 판매 가능 메뉴 ID 집합을 검증한다. `HistoryRecommendationRanker`는 다음 literal만 사용한다.

  ```java
  int intent = candidate.relevanceTier() * 8
          + (candidate.storeCategoryMatch() ? 12 : 0)
          + (candidate.menuPrimaryCategoryMatch() ? 10 : 0)
          + Math.min(candidate.menuSecondaryCategoryMatchCount(), 2) * 4
          + Math.min(candidate.tagMatchCount(), 3) * 2
          + candidate.availability().score();
  int storeHistory = Math.min(18, matchingEvents.stream()
          .mapToInt(event -> recencyScore(event.occurredAt(), asOf, 10, 6, 3)).sum());
  int menuHistory = Math.min(12, matchingMenuEvents.stream()
          .mapToInt(event -> recencyScore(event.occurredAt(), asOf, 6, 4, 2)).sum());
  ```

  기간은 `Duration.between(event.occurredAt(), asOf)`가 음수면 0점, 30일 이하/90일 이하/180일 이하의 한 구간만 적용한다. 메뉴 이력은 한 예약 사건에서 현재 메뉴 ID 교집합이 하나 이상이면 한 번만 가산한다.

- [ ] **Step 4: GREEN 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.HistoryRecommendationRankerTest"`

  Expected: score fixture PASS.

- [ ] **Step 5: 동점·콜드스타트·금지 신호·이유 실패 테스트 작성**

  총점, 매장 카테고리, 보조 카테고리 수, 가용성, 거리 null-last, storeId의 각 comparator 가지를 두 후보 literal로 분리 검증한다. 이력 빈 목록은 historyScore 0, 최고 양의 기여 요인이 `KEYWORD`, `STORE_CATEGORY`, `MENU_PRIMARY_CATEGORY`, `MENU_SECONDARY_CATEGORY`, `TAG`, `AVAILABILITY`, `VISITED_STORE`, `ORDERED_MENU` 중 실제 요인과 일치하는지 검증한다. 이유 동률은 스펙 우선순위를 사용한다.

- [ ] **Step 6: RED 확인 후 최소 comparator·이유 구현**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.HistoryRecommendationRankerTest"`

  Expected before implementation: wrong order/reason FAIL. 구현 후 PASS.

- [ ] **Step 7: 추천 cursor 키 round-trip·경계 실패 테스트와 구현**

  `RecommendationCursorKey`는 `totalScore|storeCategoryFlag|secondaryCount|availabilityRank|distanceOrTilde`를 serialize하고 `storeId`는 기존 HMAC cursor의 storeId 필드를 사용한다. 잘못된 필드 수, 숫자, 음수 점수, 비양수 storeId, NaN/무한 거리 문자열은 `COMMON_001`로 변환될 수 있도록 `IllegalArgumentException`으로 거부한다. 같은 comparator에서 마지막 키보다 뒤인 후보만 `isAfter`가 true가 되게 테스트한다.

- [ ] **Step 8: Task 1 회귀 확인과 커밋**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.*"`

  Commit: `feat(store): history-v1 추천 점수 코어 구현`

---

### Task 2: Reservation·MenuHold 공개 이력 스냅샷 loader

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationHistoryLoader.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/RecommendationHistoryLoaderTest.java`

**Interfaces:**
- Consumes: `ReservationService.getConsumerReservationHistory(Long, ReservationHistorySearchRequest)`와 `MenuHoldSnapshotQueryService.findByReservationId(long)`.
- Produces: `RecommendationHistoryLoader.load(Long consumerAccountId, Instant asOf)` → 유효한 `RecommendationHistorySnapshot`; 익명·계약 실패는 empty snapshot.

- [ ] **Step 1: 공개 계약 호출 상한의 실패 테스트 작성**

  유효 소비자는 `ReservationHistorySearchRequest.from("FULFILLED", 0, 20, "createdAt,desc")` 결과와 동등한 요청을 한 번 사용하고, 반환된 최신 항목 중 최대 5개 reservationId에만 MenuHold 조회를 호출해야 한다. assertion은 loader 결과의 event 수·storeId·occurredAt·menuIds를 검증하고 mock 호출은 호출 상한 계약만 보조 검증한다.

- [ ] **Step 2: RED 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.RecommendationHistoryLoaderTest"`

  Expected: loader 없음으로 compile failure.

- [ ] **Step 3: 최소 loader 구현**

  `consumerAccountId == null`이면 외부 호출 없이 empty를 반환한다. `FULFILLED`만 요청하고 각 공개 ID를 양의 long으로 파싱하며 status가 정확히 `FULFILLED`, `startAt`이 null이 아니고 `asOf` 미래가 아닌지 검증한다. 이력 발생 시점은 예약 서비스 시각의 `startAt.toInstant()`다. 최근 5건 외에는 빈 menuIds로 사건을 만들고, 공개 응답이 null·중복 ID·잘못된 ID·잘못된 status이거나 어느 공개 호출이든 runtime failure이면 전체 개인 이력을 empty로 축소한다.

- [ ] **Step 4: GREEN 확인**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.RecommendationHistoryLoaderTest"`

  Expected: PASS.

- [ ] **Step 5: 익명·malformed·공개 계약 실패 회귀 추가 후 커밋**

  이력 호출 실패, 두 번째 MenuHold 호출 실패, 중복 reservationId, MenuHold의 비양수 menuId가 모두 empty snapshot으로 축소되고 예전 개인화 결과를 반환하지 않는지 검증한다.

  Commit: `feat(store): 추천 공개 이력 스냅샷 구성`

---

### Task 3: Store 소유 추천 후보 신호 일괄 조회

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationCandidateSignals.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/RecommendationSignalRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/RecommendationSignalRepositoryIT.java`

**Interfaces:**
- Consumes: 후보 storeId 목록과 `InterpretedSearchCondition`의 승인된 매장/메뉴 category·tag code.
- Produces: `RecommendationSignalRepository.findSignals(List<Long>, InterpretedSearchCondition)` → 입력 storeId마다 `RecommendationCandidateSignals(storeCategoryMatch, menuPrimaryCategoryMatch, menuSecondaryCategoryMatchCount, tagMatchCount, currentMenuIds)`.

- [ ] **Step 1: 실제 MySQL fixture 기반 실패 통합 테스트 작성**

  게시·VISIBLE·SELLING 메뉴와 숨김/판매중지/retired 메뉴를 같이 저장한다. store tag 교집합은 distinct count, 메뉴 주분류는 any match, 보조 분류는 요청 code distinct count, currentMenuIds는 게시·VISIBLE·SELLING 메뉴만 포함해야 한다. 입력 순서와 누락 storeId는 보존하고 누락은 zero signal이 되게 검증한다.

- [ ] **Step 2: RED 확인**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.store.recommendation.ranking.RecommendationSignalRepositoryIT"`

  Expected: repository 없음으로 compile failure.

- [ ] **Step 3: QueryDSL 일괄 조회 최소 구현**

  Store tag, 현재 게시 메뉴의 ID/주분류, 보조분류를 고정된 수의 batch query로 읽는다. 메뉴 predicate는 `retired=false`, `visibility=VISIBLE`, `sellingStatus=SELLING`, `publishedVersionNumber=versionNumber`, `version.status=PUBLISHED`를 모두 요구한다. 입력 storeId 외 데이터와 DB 반환 순서에 의존하지 않고 Java의 `LinkedHashMap`과 distinct set으로 조합한다.

- [ ] **Step 4: GREEN·query count 경계 확인 후 커밋**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.store.recommendation.ranking.RecommendationSignalRepositoryIT"`

  Expected: PASS; 후보 수에 따라 query 수가 증가하지 않는다.

  Commit: `feat(store): 추천 후보 신호 일괄 조회`

---

### Task 4: 추천 조정자와 HMAC cursor 기반 통합 검색 정렬

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/StoreRecommendationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/query/IntegratedStoreSearchSort.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/query/IntegratedStoreSearchQuery.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/repository/IntegratedStoreSearchRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/service/IntegratedStoreSearchService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/StoreRecommendationServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/query/IntegratedStoreSearchQueryTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/service/IntegratedStoreSearchServiceTest.java`

**Interfaces:**
- Consumes: 최신 상태·가용성을 가진 검색 후보, 정규화 조건, 선택적 consumerAccountId, `Instant asOf`.
- Produces: 전체 후보의 `RankedRecommendation`과 `history-v1`; 통합 검색의 recommendation page와 다음 HMAC cursor.

- [ ] **Step 1: 조정자 mapping 실패 테스트 작성**

  Store signal의 current menu IDs, 검색 relevance tier, availability와 history snapshot이 랭커 입력에 들어가며, 원래 후보 payload가 storeId로 다시 결합되는지 실제 반환 순서·reason으로 검증한다. signal 응답이 후보 storeId를 빠뜨리면 해당 후보를 fail-closed 제외한다.

- [ ] **Step 2: RED→GREEN으로 `StoreRecommendationService` 구현**

  Run before/after: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.StoreRecommendationServiceTest"`

  `rank(Long, List<RecommendationSearchCandidate>, InterpretedSearchCondition, Instant)`가 history loader를 한 번, signal repository를 한 번 호출하고 순수 랭커 결과를 반환한다.

- [ ] **Step 3: `recommendation,desc` query/cursor 실패 테스트 작성**

  `IntegratedStoreSearchQuery.from(..., "recommendation,desc", signedCursor, size, codec)`가 허용되고, 다른 조건·size·sort에 cursor를 재사용하면 `COMMON_001`이어야 한다. 추천 cursor의 sortValue는 date parser를 거치지 않는다.

- [ ] **Step 4: RED→GREEN으로 sort enum과 repository exhaustive switch 확장**

  repository의 `RECOMMENDATION_DESC` 직접 DB 순서는 후보 scan 안전망으로 기존 relevance desc/name asc/storeId asc를 사용한다. 공개 추천 페이지는 repository 순서를 결과 순위로 사용하지 않고 service의 Java 랭커만 사용한다.

- [ ] **Step 5: 추천 검색 전체 후보 scan·순위·페이지 실패 테스트 작성**

  `IntegratedStoreSearchService.search`에 선택적 consumerAccountId를 추가한다. recommendation sort일 때 후보를 relevance scan cursor로 후보 상한까지 읽고 기존 두 번의 Store refresh와 Reservation availability 검증을 유지한 뒤 한 번에 rank한다. HMAC user cursor의 `RecommendationCursorKey` 뒤 후보만 골라 size만큼 반환하고 남은 후보가 있을 때 마지막 결과 키로 nextCursor를 만든다. 일반 relevance/name/created sort는 기존 호출 수·순서·cursor가 바뀌지 않아야 한다.

- [ ] **Step 6: RED 확인 후 최소 추천 branch 구현**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.search.service.IntegratedStoreSearchServiceTest"`

  Expected before: recommendation sort/consumer argument 없음으로 FAIL. 구현 후 PASS.

- [ ] **Step 7: Task 4 회귀 확인과 커밋**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.search.query.*" --tests "com.miriyum.domain.store.search.service.IntegratedStoreSearchServiceTest" --tests "com.miriyum.domain.store.recommendation.ranking.*Test"`

  Commit: `feat(store): 통합 검색 추천 정렬 연결`

---

### Task 5: 선택적 소비자 인증·공개 응답·OpenAPI

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/config/OptionalConsumerAuthenticationFilter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/config/StoreSearchSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/controller/StoreSearchController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/dto/IntegratedStoreSearchItem.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/search/dto/IntegratedStoreSearchData.java`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-recommendation/spec.md`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/config/StoreSearchOptionalConsumerAuthenticationIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/controller/StoreSearchControllerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/controller/StoreSearchOpenApiContractTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/dto/IntegratedStoreSearchDataTest.java`

**Interfaces:**
- Consumes: optional `Authorization: Bearer` consumer JWT and ranked search result.
- Produces: recommendation sort에서 item별 `recommendationReason`, data의 nullable `rankingRuleVersion`; 익명 허용, 잘못된/타 namespace Bearer는 401.

- [ ] **Step 1: 선택적 인증 실패 통합 테스트 작성**

  헤더 없음은 200과 principal null, 유효한 consumer Access JWT는 consumer accountId 전달, 만료/변조/refresh/store-operator JWT는 `AUTH_002`/`AUTH_003`/`AUTH_004` 계열 기존 오류 envelope의 401이어야 한다.

- [ ] **Step 2: RED→GREEN으로 optional JWT chain 구현**

  `StoreSearchSecurityConfig`에 기존 `JwtAuthenticationFilter(jwtTokenProvider, CONSUMER)`를 넣는다. `OptionalConsumerAuthenticationFilter`는 Authorization 헤더가 없으면 통과하고, 헤더가 있는데 앞 필터가 principal을 만들지 못했으면 `JwtAuthenticationEntryPoint`로 즉시 401을 반환한다. 컨트롤러는 nullable `@AuthenticationPrincipal AuthenticatedPrincipal`의 accountId만 service에 전달한다.

- [ ] **Step 3: DTO·controller 실패 테스트 작성**

  recommendation 응답은 `rankingRuleVersion="history-v1"`과 실제 reason code/message를 포함하고 내부 score/count/history는 JSON에 없어야 한다. 다른 sort는 두 필드가 null이며 기존 필드는 그대로다.

- [ ] **Step 4: RED→GREEN으로 DTO mapping 구현**

  `IntegratedStoreSearchItem`에 nullable `RecommendationReason recommendationReason`, `IntegratedStoreSearchData`에 nullable `String rankingRuleVersion`을 추가한다. 기존 생성 지점과 테스트 fixture를 명시적 null로 갱신한다.

- [ ] **Step 5: OpenAPI·정본 문서 변경과 계약 테스트**

  `/api/v1/stores` GET은 security를 익명 또는 bearer로 선언하고 `sort` enum에 `recommendation,desc`를 추가한다. `RecommendationReason` schema는 허용 code와 비어 있지 않은 message만 가지며, `IntegratedStoreSearchItem.recommendationReason`과 `IntegratedStoreSearchData.rankingRuleVersion`은 required+nullable로 고정한다. 401 응답을 추가한다. store-search/recommendation 스펙에는 선택적 소비자 JWT, 잘못된 JWT 401, 추천 cursor와 현재 검색 의도 필수 후보 경계를 기록한다.

- [ ] **Step 6: Task 5 회귀 확인과 커밋**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.search.controller.*" --tests "com.miriyum.domain.store.search.dto.*"`

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.store.search.config.StoreSearchOptionalConsumerAuthenticationIT"`

  Commit: `feat(store): 선택적 인증 추천 응답 공개`

---

### Task 6: 고정 평가셋·전체 검증·Issue/PR 증거 준비

**Files:**
- Modify only if a verified gap exists: files already listed in Tasks 1–5.

**Interfaces:**
- Consumes: #113 acceptance checklist and the committed `history-v1` spec.
- Produces: complete backend verification evidence and allowlist-clean diff.

- [ ] **Step 1: 고정 평가셋 mutation 점검**

  다음 mutation마다 최소 한 테스트가 실패하는지 테스트 이름과 assertion을 대조한다: 8/12/10/4/2/2 가중치 변경, 18/12 cap 제거, 30/90/180 경계 변경, FULFILLED 필터 제거, MenuHold 5건 상한 제거, comparator 단계 삭제, reason을 실제 요인과 다르게 선택, 광고/구독 field 추가, cursor fingerprint 검증 제거.

- [ ] **Step 2: 관련 단위 테스트 전체 실행**

  Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.recommendation.ranking.*" --tests "com.miriyum.domain.store.search.*"`

  Expected: 0 failures, 0 errors.

- [ ] **Step 3: 관련 통합 테스트 실행**

  Run: `.\gradlew.bat integrationTest --tests "com.miriyum.domain.store.recommendation.ranking.*" --tests "com.miriyum.domain.store.search.*"`

  Expected: 0 failures, 0 errors.

- [ ] **Step 4: backend 전체 검증**

  Run: `.\gradlew.bat test`

  Run: `.\gradlew.bat integrationTest`

  Run: `.\gradlew.bat build`

  Expected: 각 명령 exit 0; 테스트 failures/errors 0.

- [ ] **Step 5: 저장소 범위 검증**

  Run from repository root: `git diff --check`

  Run: `git diff --name-only origin/mvp2...HEAD`

  Expected: whitespace error 없음; 모든 경로가 #113 exact allowlist 안에 있음.

- [ ] **Step 6: 최종 구현 커밋**

  Commit: `feat(store): 이력 기반 결정적 추천 완성`
