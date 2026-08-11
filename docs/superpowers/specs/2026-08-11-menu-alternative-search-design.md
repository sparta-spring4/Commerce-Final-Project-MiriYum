# Menu Alternative Search Design

**Issue:** #114  
**Stage:** 2차 MVP  
**Target branch:** `mvp2`  
**Status:** 사용자 승인 완료

## Outcome

품절 또는 마지막 수량 확보 경합 실패 뒤, 클라이언트가 원본 메뉴 하나와 필요한 수량을 기준으로 대안을 별도 조회한다. 서버는 같은 매장의 적격 메뉴를 먼저 반환하고, 하나도 없을 때만 원 매장의 검증 좌표 기준 3km 이내 다른 매장을 탐색한다. 조회는 기존 예약이나 메뉴 홀드를 변경하지 않으며 실제 수량 확보 또는 예약 성공을 보장하지 않는다.

## Preconditions

- #112의 Reservation 일괄 가용성 계약을 사용한다.
- #117의 저장·검증 매장 좌표와 #71의 bounding box 및 Haversine 구현을 사용한다.
- #75 / PR #222의 MenuHold `findExistingOnlineAvailability` 계약을 사용한다.
- #82 / PR #228의 최상위 `search`, `menu`, `store` 패키지와 도메인 공개 계약 경계를 따른다.
- 구현 전에 최신 `dev`를 `mvp2`에 반영하고 위 공개 계약이 기준 브랜치에 실제로 존재하는지 검증한다.
- 다른 도메인의 Entity 또는 Repository를 직접 참조하지 않는다.

## Public HTTP Contract

복합 조건과 알레르기 코드를 URL에 노출하지 않도록 상태를 변경하지 않는 POST 검색으로 제공한다.

```http
POST /api/v1/stores/{storeId}/menus/{menuId}/alternatives/search
Content-Type: application/json
```

경로 변수:

- `storeId`: 원본 메뉴가 속한 양의 PublicId
- `menuId`: 대안을 찾을 원본 메뉴의 양의 PublicId

요청 body:

```json
{
  "quantity": 2,
  "serviceDate": "2026-08-15",
  "startTime": "18:30",
  "startOffset": "+09:00",
  "partySize": 2,
  "includesInfants": false,
  "excludedAllergenCodes": ["MILK", "PEANUT"],
  "size": 10
}
```

검증 규칙:

- `quantity`: 1 이상
- `partySize`: 1 이상 100 이하
- `startTime`: 분 단위 정밀도
- `startOffset`: 생략 가능하며 지정 시 `±HH:MM`
- `includesInfants`: 생략 시 `false`
- `excludedAllergenCodes`: 생략 시 빈 목록, 중복 제거 후 중앙 `AllergenIngredientCode`만 허용
- `size`: 생략 시 10, 1 이상 20 이하
- 승인되지 않은 요청 필드는 기존 strict Jackson 설정으로 거부한다.
- request body와 알레르기 조건은 애플리케이션 로그에 남기지 않는다.

## Response Contract

공통 응답 envelope의 `data`는 다음 의미를 갖는다.

```json
{
  "sourceStoreId": "10",
  "sourceMenuId": "20",
  "quantity": 2,
  "startAt": "2026-08-15T18:30:00+09:00",
  "serviceEndAt": "2026-08-15T20:00:00+09:00",
  "timeZoneId": "Asia/Seoul",
  "mode": "SAME_STORE",
  "items": [
    {
      "storeId": "10",
      "storeName": "미리윰",
      "menuId": "21",
      "menuName": "대체 메뉴",
      "unitPrice": 12000,
      "availableOnlineQuantity": 3,
      "secondaryCategoryMatchCount": 2,
      "distanceMeters": null,
      "coordinates": null,
      "reasonCodes": [
        "SAME_PRIMARY_CATEGORY",
        "PRICE_WITHIN_20_PERCENT",
        "IN_STOCK"
      ]
    }
  ]
}
```

`mode`:

- `SAME_STORE`: 같은 매장 적격 후보만 반환
- `NEARBY_STORE`: 같은 매장 후보가 없고 3km 이내 다른 매장 후보를 반환
- `NO_ALTERNATIVE`: 검증 가능한 원본 좌표가 있지만 적격 후보가 없음
- `REGION_SELECTION_REQUIRED`: 원본 매장의 저장 좌표가 없거나 검증할 수 없어 직접 지역 선택이 필요

다른 매장 항목의 `coordinates`에는 #117이 저장한 검증 좌표만 포함한다. 응답은 새 예약 화면이 사용할 `storeId`와 `menuId`를 제공할 뿐 URL을 조립하거나 기존 예약을 취소하지 않는다.

## Components and Boundaries

### Controller and DTO

Controller는 요청 형식과 공개 응답 envelope만 소유한다. 후보 규칙, 거리, 예약 및 수량 판정을 계산하지 않는다. 공개 경로는 기존 Store 검색 보안 체인과 `PUBLIC_STORE_READ` rate-limit을 재사용한다.

### Search-owned MenuAlternativeCandidateQueryService

최상위 Search 도메인이 QueryDSL 기반 공개 읽기 모델로 원본 메뉴와 후보를 읽는다. Search 소유의 immutable Service·DTO 계약은 Store·Menu Entity·Repository·내부 model을 Recommendation에 노출하지 않고 다음 값만 제공한다.

- 매장 ID·이름·검증 좌표·운영 상태·예약/메뉴홀드 모드
- 메뉴 ID·이름·단가·주 카테고리·보조 카테고리
- 알레르기 정보 등록 상태와 중앙 disclosure
- 게시·공개·판매·홀드 선택 자격

같은 매장 조회와 bounding box 기반 다른 매장 조회를 분리한다. 다른 매장 후보 평가는 기존 `StoreSearchCandidateLimit`으로 제한한다.

Search 공개 계약은 Recommendation 구현보다 먼저 별도 contract-first PR로 검토한다. Recommendation은 이 Service·DTO가 선행 stacked base에 포함된 뒤에만 구현한다.

### Alternative-owned MenuAlternativeEligibility

외부 의존성 없는 결정적 정책 객체다. 다음을 판정한다.

- 원본 메뉴 제외
- 동일 주 카테고리
- 정수 KRW 기준 원 가격 80% 이상 120% 이하, 양 경계 포함
- 보조 카테고리 교집합 수
- 알레르기 제외 조건

제외 알레르기 조건이 하나라도 있으면 후보의 알레르기 정보가 `REGISTERED`여야 한다. 제외 코드가 `CONTAINS` 또는 `MAY_CONTAIN`이면 후보에서 제외한다. 정보 미등록 또는 위험을 배제할 수 없는 후보도 제외한다.

### Alternative-owned MenuAlternativeSearchService

오케스트레이션 순서는 고정한다.

1. Search 공개 계약으로 원본 매장·메뉴 현재 게시 스냅샷을 조회한다.
2. Reservation 공개 계약으로 원본 매장의 서비스 구간을 계산한다.
3. Search 공개 계약이 반환한 같은 매장 후보를 Recommendation 정책으로 필터링한다.
4. MenuHold `findExistingOnlineAvailability`로 현재 버킷 존재와 요청 수량을 검증한다.
5. 같은 매장 적격 후보가 하나라도 있으면 정렬·절단 후 즉시 반환한다.
6. 원본 좌표가 없으면 `REGION_SELECTION_REQUIRED`를 반환한다.
7. bounding box로 다른 매장 후보를 제한한 뒤 Haversine `<= 3,000m`를 확정한다.
8. Reservation `getAvailabilities`로 날짜·시각·인원·영유아 조건을 일괄 재검증한다.
9. 예약 가능한 매장 ID만 Reservation `resolveReservationTimes`에 전달해 매장별 `startAt`, `serviceEndAt`, `timeZoneId`를 얻는다.
10. 계산된 각 매장의 정확한 서비스 구간에 맞춰 MenuHold 현재 버킷과 수량을 검증한다.
11. 정렬·절단 후 `NEARBY_STORE`, 후보가 없으면 `NO_ALTERNATIVE`를 반환한다.

다른 도메인 결과의 개수, ID, 순서, 시간대 또는 서비스 구간이 요청과 맞지 않으면 fail-closed 한다. 후보가 비었을 때 불필요한 Reservation 또는 MenuHold 호출은 생략한다.

## Canonical Contract Updates

구현 PR은 승인된 동작을 `docs/specs/store-search/spec.md`와 `docs/specs/store-search/openapi.yaml`에 기록한다. 전역 제품·정책·아키텍처 문서는 이미 같은 매장 우선, 원 매장 좌표 3km, 중앙 재검증, 사용자 현재 위치 비사용을 소유하므로 규칙을 중복 복사하지 않고 필요한 링크와 API 세부 계약만 갱신한다. OpenAPI 변경 뒤 frontend 생성 타입을 다시 만들고 생성 diff를 검증한다.

## Deterministic Ordering

모든 후보는 동일 주 카테고리, 가격 범위, 판매 자격, 알레르기 조건, 예약 가능성 및 요청 수량을 통과해야 한다.

같은 매장:

1. 보조 카테고리 교집합 수 내림차순
2. 원 가격과 절대 차이 오름차순
3. 단가 오름차순
4. 메뉴 ID 오름차순

다른 매장:

1. Haversine 거리 오름차순
2. 보조 카테고리 교집합 수 내림차순
3. 원 가격과 절대 차이 오름차순
4. 매장 ID 오름차순
5. 메뉴 ID 오름차순

정확히 3,000m는 포함하고 이를 초과하면 제외한다. 부동소수점 결과를 반올림해 자격을 바꾸지 않으며 응답 표시값만 기존 거리 계약의 정밀도에 맞춘다.

## Error and Empty-Result Semantics

- 잘못된 body, PublicId, 수량, 인원, 시각, offset, 알레르기 코드 또는 size: 기존 validation 공통 400
- 존재하지 않는 원본 매장 또는 메뉴·매장 불일치: 기존 Store 오류 보존
- 원본 매장이 예약 또는 메뉴 홀드를 제공하지 않거나 Reservation이 `UNAVAILABLE`: 소유 도메인의 기존 오류·상태를 임의 변환하지 않음
- 다른 도메인 응답 shape·identity 불일치: 기존 fail-closed 공통 오류
- 후보 없음, 재고 버킷 없음, 명시적 `SOLD_OUT`, 수량 부족: 오류가 아니라 후보 제외
- 좌표 없음: 오류가 아니라 `REGION_SELECTION_REQUIRED`
- 후보 최종 없음: 오류가 아니라 `NO_ALTERNATIVE`, `items: []`

새 오류 코드를 만들지 않는다.

## Security and Privacy

- 로그인과 사용자 현재 위치를 요구하지 않는다.
- 사용자 현재 위치를 요청·저장·URL·로그에 남기지 않는다.
- 추천 요청 중 Kakao Local API나 Kakao Maps API를 호출하지 않는다.
- 알레르기 제외 조건과 전체 body를 로그에 남기지 않는다.
- AI/LLM, 검색 엔진, 벡터 DB, 추천 캐시, Kafka 또는 범용 Outbox를 도입하지 않는다.

## Verification Strategy

TDD 순서는 다음과 같다.

1. 순수 정책 단위 테스트: 가격 80%·120% 경계, 주 카테고리, 보조 카테고리 교집합, 알레르기 등록·함유·혼입 가능·미등록
2. 같은 매장 오케스트레이션 테스트: 같은 매장 우선, 수량 부족·버킷 없음·SOLD_OUT 제외, 결정적 정렬, 다른 매장 미호출
3. 거리 테스트: bounding box, 정확히 3km 포함, 3km 초과 제외, 좌표 fallback
4. 다른 매장 오케스트레이션 테스트: Reservation 일괄 가용성, 매장별 서비스 구간, MenuHold 수량, 결정적 정렬
5. Repository Testcontainers MySQL 테스트: 게시·공개·판매·카테고리·알레르기·좌표 projection과 후보 상한
6. Controller MockMvc 테스트: 공개 접근, strict body validation, envelope, 네 mode
7. OpenAPI 계약 테스트와 frontend 생성 타입 갱신 검증
8. 집중 테스트, backend 전체 test/build, frontend typecheck/test/build, `git diff --check`, Issue allowlist 대조, 원격 required CI 확인

각 production 변경은 해당 동작을 증명하는 실패 테스트를 먼저 실행한 뒤 최소 구현으로 통과시킨다.

## Rollback

대안 검색 route, Store 내부 projection, 정책 객체와 오케스트레이션은 기존 검색·예약·MenuHold 거래 경로와 분리한다. 문제가 있으면 #114 커밋을 revert해 새 route를 제거할 수 있으며 기존 #75 가용성 API와 2차 MVP 통합 검색·추천 기능은 유지된다.
