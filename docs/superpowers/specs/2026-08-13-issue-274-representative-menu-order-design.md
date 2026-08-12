# Issue #274 대표(인기) 메뉴 선정·순서 관리 설계

## 1. 목표와 제품 용어

매장 운영자가 자기 매장의 현재 공개 가능한 메뉴 가운데 3~5개를 골라 대표 메뉴 순서를 관리한다. 화면에서 부르는 `인기 메뉴`와 기존 정책의 `대표 메뉴`는 같은 제품 개념이다. 공개 매장 상세의 `representativeMenus`와 향후 예약금 계산 입력은 동일한 현재 설정 원장을 사용한다.

기존 `menu_versions.representative` 값은 과거 메뉴 버전 재현을 위해 유지한다. 그러나 현재 대표 여부와 순서의 정본은 새 매장 단위 설정 원장이다. 기존 값을 일괄 수정하거나 과거 버전에 소급 반영하지 않는다.

## 2. 범위

### 포함

- 매장별 대표 메뉴 설정 version과 상태
- 대표 메뉴 3~5개의 전체 교체와 중복 없는 순서
- 운영자 조회, 권한, `expectedVersion`, `Idempotency-Key`
- 메뉴 소유권·현재 게시 버전·공개·판매 상태 재검증
- 공개 불가 상태 전이 시 자동 해제와 SYSTEM 감사
- 공개 상세의 기존 `representativeMenus`를 설정 순서로 반환
- Payment·Reservation이 후속 PR에서 소비할 Store/Menu 공개 조회 Service·DTO
- MySQL 제약, 동시성, HTTP·OpenAPI 계약 검증

### 제외

- MenuHold·Pickup의 시간대별 재고 원장과 차감
- 일부 시간대 재고 소진을 메뉴 전체 `SOLD_OUT`으로 바꾸는 동작
- Payment·Reservation 내부 예약금 계산 runtime
- 개인화 추천, 추천 점수, 품절 대안, 이미지, frontend 정렬 화면
- 과거 거래 또는 메뉴 버전 snapshot 소급 변경

## 3. 검토한 접근

### A. 매장 단위 순서 원장을 현재 정본으로 사용 — 선택

설정 한 건과 순서 항목 3~5개를 전체 교체한다. 현재 대표 여부, 공개 순서와 공개 조회 계약이 한 원본을 사용한다. 기존 메뉴 버전의 boolean은 역사 값으로만 남는다.

장점은 3~5개와 순서를 하나의 원자 명령으로 보장하고, 공개·예약금 소비자가 같은 version을 사용할 수 있다는 점이다. 기존 boolean과 신규 원장의 의미를 명시적으로 분리해야 한다는 이행 비용이 있다.

### B. 메뉴 버전 boolean과 별도 순서 테이블을 동기화

현재 메뉴 버전의 `representative`를 순서 테이블과 함께 갱신한다. 겉보기 호환성은 좋지만 게시된 불변 메뉴 버전을 수정하거나, 두 원본이 부분 실패로 어긋날 위험이 있다. 채택하지 않는다.

### C. 기존 boolean만 유지

메뉴별 수정으로 대표 여부를 설정한다. 순서와 3~5개 전체 불변식을 원자적으로 보장할 수 없고, 운영자가 2개에서 3개로 늘리는 정상 편집조차 중간 상태 제약과 충돌한다. 채택하지 않는다.

## 4. 도메인 모델

### RepresentativeMenuSetting

매장마다 논리적으로 한 개가 존재한다.

- `storeId`: 매장 식별자, 설정의 자연 키
- `version`: 0부터 시작하는 도메인 version
- `status`: `UNCONFIGURED`, `CONFIGURED`, `REQUIRES_ATTENTION`
- `lockVersion`: JPA 낙관 잠금이 아닌 DB 행 갱신 추적용 값
- `entries`: 표시 순서 1~5의 현재 항목

상태 규칙은 다음과 같다.

- 항목 0개이고 한 번도 설정하지 않았으면 `UNCONFIGURED`, version 0이다.
- 유효한 항목이 3~5개이면 `CONFIGURED`다.
- 자동 해제로 1~2개가 남으면 `REQUIRES_ATTENTION`이다.
- 자동 해제로 0개가 되더라도 이전 설정 이력이 있으면 `REQUIRES_ATTENTION`이다.
- 운영자 전체 교체 명령은 정확히 3~5개만 허용하므로 성공 결과는 항상 `CONFIGURED`다.

### RepresentativeMenuEntry

- `storeId`
- `displayOrder`: 1~5
- `menuId`

DB는 `(store_id, display_order)` 기본 키와 `(store_id, menu_id)` 유일 키로 순서와 메뉴 중복을 막는다. 메뉴는 같은 매장 소유여야 한다. 현재 게시 메뉴의 내용은 항목에 복제하지 않고 조회 시 최신 게시 버전에서 읽는다.

### RepresentativeMenuAudit

append-only 감사에는 다음을 기록한다.

- 전후 setting version·상태
- 사건 `REPLACED` 또는 `AUTO_REMOVED`
- 행위자 `OPERATOR` 또는 `SYSTEM`
- 운영자 ID 또는 자동 해제를 유발한 메뉴 ID
- 순서가 보존된 결과 menu ID snapshot
- request ID, 명령 시각, 결과

운영자 전체 교체와 자동 해제는 설정·항목·감사를 같은 트랜잭션에서 확정한다.

## 5. 메뉴 적격성과 품절

운영자가 대표 메뉴로 저장할 수 있는 조건은 다음과 같다.

- 요청 매장 소유 메뉴
- `retired = false`
- 현재 게시 버전 존재 및 상태 `PUBLISHED`
- `visibility = VISIBLE`
- `sellingStatus = SELLING` 또는 `SOLD_OUT`

`SOLD_OUT`은 대표 선정을 유지한다. 공개 응답은 기존 `saleStatus`로 품절을 표시한다. 특정 날짜·시간 구간의 재고 0도 MenuHold·Pickup의 가용성 응답에서만 품절로 계산하며 대표 선정 원장을 변경하지 않는다.

다음 전이는 공개 불가이므로 해당 메뉴를 대표 목록에서 자동 해제한다.

- `VISIBLE → HIDDEN`
- `SELLING|SOLD_OUT → PAUSED`
- 메뉴 운영 종료 `RETIRED`
- 현재 게시 버전이 없어지는 미래 명령이 추가되는 경우

안전한 숨김·중지·종료 명령은 대표 메뉴 최소 개수 때문에 거절하지 않는다. 자동 해제 뒤 3개 미만이면 설정을 `REQUIRES_ATTENTION`으로 바꾼다. 다시 공개·판매 상태로 돌아와도 자동 재선정하지 않는다.

## 6. API 계약

### 운영자 조회

`GET /api/v1/store-operators/stores/{storeId}/representative-menus`

응답은 다음 의미를 가진다.

- `version`
- `status`
- 순서대로 정렬된 `items`
- 각 항목의 `menuId`, `displayOrder`, 현재 게시 버전 번호, 이름, 가격, 판매 상태

설정 전에는 `version: 0`, `status: UNCONFIGURED`, `items: []`를 반환한다. 타 매장 운영자는 기존 `STORE_003`으로 거부한다.

### 운영자 전체 교체

`PUT /api/v1/store-operators/stores/{storeId}/representative-menus`

- header: 필수 `Idempotency-Key`
- body: `expectedVersion`, 순서가 의미인 `menuIds` 3~5개
- 같은 menu ID 중복 금지
- 성공: 증가한 version과 `CONFIGURED` 설정 반환
- 구 version: `COMMON_008` 409
- 다른 매장 또는 존재하지 않는 menu ID: `STORE_009` 404
- 현재 공개 불가 menu: `STORE_010` 409
- 개수·중복·형식 오류: `COMMON_001` 400

동일 멱등 키와 동일 fingerprint 재시도는 최초 응답을 반환한다. 같은 키에 다른 fingerprint는 기존 공통 멱등 충돌을 반환한다.

### 공개 매장 상세

기존 `PublicStoreDetail.representativeMenus` 필드와 `PublicMenu.representative` 필드명은 유지한다.

- 상세의 `representativeMenus`는 설정 순서로만 반환한다.
- 공개 메뉴 목록의 `representative`는 현재 설정 membership으로 계산한다.
- 방어적 조회 조건에서도 공개 불가 메뉴를 제외한다.
- 설정이 `UNCONFIGURED` 또는 `REQUIRES_ATTENTION`이어도 공개 가능한 남은 항목만 순서대로 반환한다.

### 교차 도메인 공개 계약

Store/Menu 소유 Service는 `RepresentativeMenuSnapshot`을 제공한다.

- `storeId`, setting `version`, `status`
- 순서가 보존된 현재 공개 가능 항목
- 항목별 `menuId`, 게시 version, 이름, 기본 가격, 판매 상태

Payment·Reservation은 이 DTO만 소비하며 Menu Entity·Repository를 직접 참조하지 않는다. `REQUIRES_ATTENTION`을 예약금 계산에 허용할지는 Payment 정책 소유자가 후속 계약에서 결정한다. #274는 이를 성공으로 추측하지 않는다.

## 7. 트랜잭션과 동시성

같은 매장의 대표 설정 전체 교체와 메뉴 공개 불가 전이를 직렬화해야 한다. 잠금 순서는 항상 다음과 같다.

1. 설정 행을 존재 보장 후 `SELECT ... FOR UPDATE`
2. 대상 메뉴를 menu ID 오름차순으로 잠금
3. 현재 상태와 `expectedVersion` 재검증
4. 항목 교체 또는 자동 해제
5. version·상태와 감사 저장

설정 행은 기존·신규 매장 모두 lazy `INSERT IGNORE` 후 잠근다. 설정이 없는 상태를 잠글 수 없어서 생기는 최초 명령 경합을 피한다. 메뉴 숨김·중지·종료도 메뉴를 잠그기 전에 같은 설정 행을 먼저 잠가 교착 순서를 통일한다.

공개 조회는 잠금을 획득하지 않는다. 설정 항목과 메뉴 현재 상태를 한 조회에서 결합하고 공개 조건을 다시 적용한다.

## 8. Migration과 호환성

- #272가 예약한 `V36` 다음 `V37__create_representative_menu_settings.sql`을 사용한다.
- #272의 V36이 `dev`에 병합되기 전 #274를 병합하지 않는다.
- 기존 `menu_versions.representative` column은 제거·수정하지 않는다.
- 기존 boolean을 임의 순서로 자동 이관하지 않는다. 매장별로 명시적 3~5개 전체 교체 전까지 `UNCONFIGURED`다.
- 공개 필드명은 유지하지만 현재 값의 원본이 설정 원장으로 전환된다.
- 과거 거래 snapshot과 메뉴 버전은 변경하지 않는다.

## 9. 실패와 복구

- 감사 저장 실패 시 설정 변경과 자동 해제를 함께 롤백한다.
- 공개 상세은 설정에 stale 항목이 있어도 현재 메뉴 조건으로 다시 필터링하여 비공개 메뉴를 노출하지 않는다.
- stale 항목은 다음 쓰기 또는 자동 해제 재시도로 정리할 수 있지만, 공개 안전성을 재조정 성공에 의존하지 않는다.
- 자동 해제 실패 시 원 메뉴 숨김·중지·종료와 같은 트랜잭션을 롤백해 설정과 공개 상태의 부분 성공을 남기지 않는다.
- Payment 공개 계약 소비가 준비되지 않았으면 #274의 Store/Menu 계약까지만 구현하고 Payment runtime 연결은 `BLOCKED`로 남긴다.

## 10. 검증 전략

- 계약 테스트: 운영자 GET·PUT, 기존 공개 상세 필드, audience aggregate `$ref`, 금지된 별도 `popularMenus` 필드
- 단위 테스트: 3·5 경계, 2·6 거부, 중복, 상태 전이, 자동 해제 후 상태
- Controller 테스트: 인증, 소유권, validation, 오류 envelope, 멱등 키
- 실제 MySQL 통합 테스트: 두 전체 교체 경합, 전체 교체와 숨김 경합, DB 유일 제약, 감사 원자성, V37 clean/upgrade
- 공개 조회 테스트: 순서, `SOLD_OUT` 유지, `HIDDEN`·`PAUSED`·`RETIRED` 제외, `REQUIRES_ATTENTION` 부분 목록
- 회귀: 기존 Menu·Store search 테스트, 전체 `test`, `integrationTest`, `build`, Redocly lint/bundle, `git diff --check`

## 11. 구현 경계

구현 계획에서 Java·migration·test의 exact allowlist를 파일 단위로 확정하고 #274 본문에 단계 2로 반영한 뒤 runtime을 수정한다. #272와 공유하는 `store-operator-openapi.yaml`, audience 계약 테스트와 migration 순서는 최신 `dev`를 다시 반영한 뒤 검증한다.
