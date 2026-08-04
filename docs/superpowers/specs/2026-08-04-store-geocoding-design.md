# Store Geocoding Design

**Issue:** #117
**Stage:** 2차 MVP
**Owner:** Store / @116Lv
**Status:** Approved in conversation; awaiting written-spec review

## 1. Goal and boundaries

매장 신규 입점과 주소 변경 시 주소의 존재 가능성과 지역 일관성을 Kakao Local REST API로 검증하고, 검증된 좌표를 현재 주소 버전과 함께 저장한다. 검색·추천은 저장된 좌표만 읽으며 외부 지오코딩 API를 호출하지 않는다.

프론트엔드, 사용자 현재 위치, 지도 SDK, 반경 후보 조회·추천 점수, 운영용 provider 계약과 secret 저장소, 자동 재시도 및 비동기 보정은 이 Issue에서 제외한다.

## 2. Decisions

### 2.1 Strict validation before write

신규 등록과 주소 변경은 지오코딩 검증이 성공해야만 저장된다.

- 후보 0건, 복수 후보, 주소 불일치, 요청 Region 불일치, 좌표 범위 오류: `COMMON_001`, HTTP 400
- timeout, HTTP 429/5xx, 인증·설정 오류, 해석 불가능한 응답: `COMMON_012`, HTTP 503
- 등록 실패에는 매장 row가 생성되지 않는다.
- 변경 실패에는 기존 주소, 좌표, 주소 버전이 보존된다.

기존 데이터는 migration에서 `UNVERIFIED`와 좌표 없음으로 backfill한다. 이 상태는 신규 쓰기 실패를 허용하는 우회로가 아니라, 과거 데이터 호환과 검색의 지역 직접 선택 fallback을 위한 상태다.

### 2.2 External call outside the DB transaction

흐름은 다음과 같다.

`권한·요청 확인 → Kakao 호출(최대 2초) → 응답 검증 → DB transaction → 잠금 후 입력 재확인 → 주소·버전·좌표 저장`

네트워크 호출을 transaction 내부에서 수행하지 않아 DB connection과 lock을 외부 지연 동안 점유하지 않는다. 검증된 결과는 이후 transaction에서 주소와 함께 원자적으로 반영한다.

### 2.3 Provider-neutral boundary

Store service는 `StoreGeocodingPort`만 의존한다. `KakaoLocalGeocodingAdapter`가 Kakao 요청·응답 형식을 담당하고 `StoreGeocodingValidator`가 provider 응답을 도메인에서 사용할 검증 결과로 좁힌다. API key, 전체 payload, 요청 header는 도메인 객체나 응답 DTO에 전달하지 않는다.

## 3. Kakao adapter contract

- Endpoint: `GET https://dapi.kakao.com/v2/local/search/address.json`
- Authorization: `KakaoAK ${REST_API_KEY}`
- Query: `query=<address>`, `analyze_type=similar`, `size=2`
- connect timeout: 1 second
- total response timeout: 2 seconds
- automatic retry: none
- timeout values are configuration properties and may be increased later

`similar` 검색은 사용자가 상세 건물명·층·호를 포함한 경우를 수용하기 위해 사용한다. `size=2`로 복수 후보를 감지하고, adapter가 fuzzy 결과를 그대로 신뢰하지 않도록 별도 검증기가 정확히 1건·주소 일치·지역 일치를 강제한다.

환경 변수는 저장소 기본 secret 없이 다음 이름을 사용한다.

- `MIRIYUM_KAKAO_LOCAL_REST_API_KEY`
- `MIRIYUM_STORE_GEOCODING_CONNECT_TIMEOUT_MS` (default 1000)
- `MIRIYUM_STORE_GEOCODING_RESPONSE_TIMEOUT_MS` (default 2000)

## 4. Response validation

검증 성공에는 다음 조건이 모두 필요하다.

1. `meta.total_count`와 반환 문서가 단일 후보를 나타낸다.
2. `x`와 `y`가 숫자로 해석되며 longitude `[-180, 180]`, latitude `[-90, 90]` 범위에 있다.
3. 도로명 주소 또는 지번 주소 중 하나가 정규화된 입력 주소와 일치한다.
4. 요청 `Region`이 Kakao 주소의 `region_1depth_name`과 일치한다.
5. 검증 주소와 좌표를 구성하는 필수 필드가 존재한다.

주소 정규화는 Unicode·공백·일반 구두점 차이를 제거하되 행정구역이나 도로/번지 숫자를 바꾸지 않는다. Kakao가 반환하는 정식 주소 뒤에 입력의 상세 주소가 붙은 경우는 허용한다. 정식 주소 자체가 입력의 접두부로 확인되지 않는 fuzzy 후보는 거절한다.

## 5. Persistence model and invariants

`stores`에 V20 migration으로 다음 컬럼을 추가한다.

- `address_version BIGINT NOT NULL DEFAULT 1`
- `geocoding_status VARCHAR(...) NOT NULL DEFAULT 'UNVERIFIED'`
- `latitude DECIMAL(...) NULL`
- `longitude DECIMAL(...) NULL`
- `verified_address VARCHAR(300) NULL`
- `geocoding_verified_at DATETIME(6) NULL`
- `geocoding_address_version BIGINT NULL`
- 최소 내부 추적용 provider/provider API version 컬럼

정밀도와 길이는 기존 DB 규칙과 Kakao 좌표 표현을 확인해 구현 계획에서 확정하되 위도·경도 범위를 손실 없이 저장해야 한다.

DB와 Entity는 다음 불변식을 유지한다.

- `VERIFIED`: 좌표·검증 주소·검증 시각·좌표 주소 버전이 모두 존재한다.
- `VERIFIED`: `geocoding_address_version = address_version`이다.
- `VERIFIED`: 위도·경도가 유효 범위에 있다.
- `UNVERIFIED`: 좌표와 검증 메타데이터가 모두 `NULL`이다.
- legacy migration: `address_version = 1`, `geocoding_status = UNVERIFIED`.

주소 변경 성공 시 `address_version`을 증가시키고 같은 transaction에서 새 좌표와 `geocoding_address_version`을 새 버전에 맞춘다. 이전 좌표가 새 주소 좌표처럼 남는 중간 상태를 허용하지 않는다.

## 6. Service and idempotency flow

현재 `IdempotencyExecutor.execute`는 기존 transaction을 요구하므로 public create/update method 전체에 transaction을 두지 않는다.

1. 권한과 요청을 확인한다.
2. create 또는 주소·Region 중 하나가 포함된 PATCH이면 transaction 밖에서 변경 후의 유효 주소·Region을 지오코딩한다. Region만 바뀌면 현재 주소를 사용하고, 주소만 바뀌면 현재 Region을 사용한다.
3. 성공 또는 실패 결과를 capture한다.
4. `TransactionTemplate` 안에서 `IdempotencyExecutor.execute`를 호출한다.
5. 새 claim일 때 잠근 최신 Store에서 유효 주소·Region을 다시 계산한다. preflight 입력과 다르면 `COMMON_008`로 중단하고, 같으면 capture한 실패를 throw하거나 성공 좌표를 저장한다.
6. 완료된 동일 명령 replay이면 supplier가 실행되지 않고 기존 저장 응답을 반환한다.

이 구조는 replay 전에 불필요한 외부 호출이 한 번 발생할 수 있지만, 기존 멱등성 저장 구조를 깨지 않고 완료 결과 재사용을 보장한다. API key나 원본 provider payload는 멱등성 결과에 포함하지 않는다.

주소와 Region이 모두 없는 PATCH는 Kakao를 호출하지 않는다. 둘 중 하나가 포함되면 현재 값과 같더라도 변경 후의 유효 주소·Region을 재검증하여 기존 `UNVERIFIED` 데이터의 명시적 보정 경로를 제공한다. 외부 검증과 행 잠금 사이에 다른 명령이 주소·Region을 바꾸면 검증 결과를 새 상태에 적용하지 않는다.

## 7. Public response contract

기존 운영자용 매장 등록·조회·변경 응답에 다음 중첩 객체를 추가한다.

```yaml
geocoding:
  status: VERIFIED | UNVERIFIED
  latitude: number | null
  longitude: number | null
  verifiedAddress: string | null
  verifiedAt: date-time | null
  addressVersion: integer
```

`UNVERIFIED`에서는 좌표·검증 주소·시각이 null이다. 내부 provider 이름·API version, API key, 전체 Kakao 응답과 header는 공개하지 않는다. 기존 공통 성공 envelope와 오류 code를 재사용하며 새 route·role·공개 error code는 만들지 않는다.

## 8. Failure safety and logging

- provider 요청/응답 전문, Authorization header, API key를 DB·application log·HTTP response에 기록하지 않는다.
- 오류 log는 provider 분류, HTTP 상태 범주, timeout 종류와 correlation에 필요한 비민감 정보만 남긴다.
- 외부 실패는 transaction 시작 전에 분류하되, 새 멱등성 claim에서 최종 HTTP 실패가 되도록 transaction 경계에서 throw한다.
- 적용된 Flyway migration은 수정하지 않으며 rollback 필요 시 후속 보정 migration을 추가한다.

## 9. Verification strategy

- Validator unit: 단일/0/복수 후보, 상세 주소 정규화, 주소·Region 불일치, 필수 필드, 좌표 parsing/range
- WireMock adapter: 성공, 0건, 복수, timeout, 429, 5xx, malformed JSON
- Service: network call이 transaction 밖임, 실패 시 미저장, 변경 실패 시 기존 값 보존, 주소·Region 없는 PATCH 미호출, Region-only 재검증, preflight 후 동시 변경 거절, 동일 key replay
- Testcontainers MySQL: V20 legacy backfill, CHECK constraints, 주소 버전·좌표 원자 저장
- MockMvc/OpenAPI: `geocoding` 응답과 400/503 mapping
- final gates: focused tests, backend full test, `git diff --check`, Issue allowlist audit

## 10. Alternatives rejected

### Save first, geocode asynchronously

입점 직후 검증되지 않은 주소가 활성 데이터가 되고 별도 worker·재시도 상태가 필요하므로 현재 strict 계약과 범위를 벗어난다.

### Call Kakao inside the DB transaction

구현은 단순하지만 외부 지연 동안 DB resource를 점유하고 timeout이 transaction 수명에 직접 영향을 주므로 선택하지 않는다.

### Allow geocoding failure and save without coordinates

기존 legacy fallback과 신규 쓰기 검증을 혼동하며 존재하지 않는 주소의 신규 유입을 막지 못하므로 선택하지 않는다.

## 11. References

- Kakao Maps REST API 주소 검색: https://developers.kakao.com/docs/ko/kakaomap/rest-api
- Active feature contract: `docs/specs/store-search/spec.md`
- Active API contract: `docs/specs/store-search/openapi.yaml`
