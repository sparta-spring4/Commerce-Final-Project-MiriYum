# MiriYum 식당 대표자 풀스택 화면 구현 상세 지시서

## 작업 원칙

적용 단계는 1차 MVP, 2차 MVP, 고도화이며 현재 계약이 있는 단계만 실제 서비스에 노출한다.

`02-store-operator-design.md`와 화면 번호를 맞춘다. Access JWT는 대표자 셸 메모리, Refresh는 `MIRIYUM_STORE_OPERATOR_REFRESH`, CSRF는 `MIRIYUM_STORE_OPERATOR_XSRF_TOKEN`과 `X-CSRF-TOKEN`을 사용한다. 일반 사용자 namespace와 공유하지 않는다. 모든 storeId 명령은 서버의 대표 운영자 권한 결과를 따른다.

권장 route prefix는 `/partner`다. 인증 route와 보호 셸을 분리하고, 현재 매장 ID가 바뀌면 이전 매장의 query·mutation·폼 draft를 취소·격리한다.

## 화면별 구현

### 1. 대표자 회원가입
- route: `/partner/signup`
- API: `POST /api/v1/store-operator-auth/accounts`
- 이메일·비밀번호·표시 이름과 OpenAPI 확인 참조를 사용한다.
- 계정 생성과 매장 등록을 한 요청으로 합치지 않는다. 성공 후 로그인.

### 2. 대표자 로그인
- route: `/partner/login`
- 로그인 `POST /api/v1/store-operator-auth/sessions`
- CSRF `GET /api/v1/store-operator-auth/csrf-tokens/current`
- 재발급 `POST /api/v1/store-operator-auth/token-refreshes`
- 로그아웃 `DELETE /api/v1/store-operator-auth/sessions/current`
- 실패는 `AUTH_005`, 제한은 `COMMON_010`, 만료 재발급 실패 시 사유를 보존해 로그인 이동
- 성공 후 관리 매장 조회 결과가 없으면 등록, 있으면 홈으로 자동 분기한다.

### 3. 매장 등록
- route: `/partner/stores/new`
- catalog: `GET /api/v1/store-categories`, `/api/v1/store-tags`
- 생성: `POST /api/v1/store-operator/stores` + Idempotency-Key
- 요청: 사업자등록번호, `CAFE/BAKERY/OTHER`, 이름·설명·지역·주소·category code·tag code·운영 모드
- 운영자 ID·승인 필드·파일·이미지를 보내지 않는다.
- 1차 성공 응답은 즉시 `verificationStatus=APPROVED`; 승인 대기 route를 만들지 않는다.
- `STORE_002` 중복, `_004` catalog, `_008` OTHER+pickup 오류를 필드에 연결

### 4. 관리 홈
- route: `/partner`
- 관리 매장 조회 결과로 기본정보·설정 page link를 구성한다.
- 1차에는 통계 API를 추측하지 않고 설정 완성도와 운영 상태만 표현한다.

### 5. 매장 정보
- route: `/partner/stores/:storeId/settings`
- API: `GET/PATCH /api/v1/store-operator/stores/{storeId}`
- 변경 가능한 필드만 PATCH하고 Idempotency-Key 사용
- `STORE_003`은 권한 없음, `_005/_007` 상태 충돌, `_008` 픽업 자격

### 6. 운영시간
- route: `/partner/stores/:storeId/operating-hours`
- `GET/PUT /api/v1/store-operator/stores/{storeId}/operating-hours`
- PUT은 월~일 전체 설정을 게시한다. 일부 요일 로컬 병합 금지
- 구간 `[startTime,endTime)`, 브레이크타임을 분리하고 `STORE_006`을 구간별 표시
- 저장 성공 후 해당 매장 상세·공개 검색 관련 query를 갱신하되 기존 예약을 수정하지 않는다.

### 7. 예약 접수 시간대
- route: `/partner/stores/:storeId/reservation-time-slots`
- `GET/PUT /api/v1/store-operator/stores/{storeId}/reservation-time-slots`
- 전체 주간 게시, 영업시간 밖·브레이크 충돌 표시
- 수용량 필드를 이 요청에 섞지 않는다.

### 8. 메뉴 목록·등록·수정
- routes: `/partner/stores/:storeId/menus`, `/menus/new`, `/menus/:menuId/edit`
- `GET/POST /api/v1/store-operator/stores/{storeId}/menus`
- `GET/PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}`
- menu category catalog는 `/api/v1/menu-categories`
- 버전 `DRAFT/SCHEDULED/PUBLISHED/RETIRED`, 노출 `VISIBLE/HIDDEN`, 판매 `SELLING/PAUSED`를 독립 필드로 유지
- `AVAILABLE/SOLD_OUT`은 이 폼에 저장하지 않는다.

### 9. 예약 수용량
- route: `/partner/stores/:storeId/capacities/:serviceDate`
- `GET/PUT /api/v1/store-operator/stores/{storeId}/reservation-capacities/{serviceDate}`
- 날짜별 전체 버킷, maxPeople·maxTeams·minPartySize·maxPartySize·infantsAllowed
- 현재 점유와 잔여를 서버 응답으로 표시; 클라이언트가 재계산해 원장처럼 저장하지 않는다.
- `RESERVATION_008/009`, `COMMON_008` 처리

### 10. 메뉴 수량·품절
- route: `/partner/stores/:storeId/inventory`
- 목록 `GET /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets`
- 생성 `POST /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets`
- 수정 `PATCH /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}`
- 전체 totalSupply·ONLINE_HOLD·ONSITE·SHARED·availabilityStatus를 함께 제출
- `MENU_HOLD_004` 합계, `_005` 사용량 이하 축소, `_006` 전이 오류
- 성공 후 해당 버킷·공개 가용성·픽업 가용성 query 무효화

### 11. 예약 목록
- routes: `/partner/stores/:storeId/reservations`, `/reservations/:reservationId`
- 목록·상세: `GET /api/v1/store-operator/stores/{storeId}/reservations[/{reservationId}]`
- 날짜·상태·0 기반 page; `CONFIRMED/CANCELLED/FULFILLED`

### 12. 예약 상세·처리

- 상세 조회 뒤 현재 상태에 허용되는 명령만 렌더링한다.
- 취소 `POST .../{reservationId}/cancellations`, 사유와 Idempotency-Key
- 방문 완료 `POST .../{reservationId}/fulfillments`
- 범용 status PATCH 금지. 취소는 수량 복구 API를 별도로 호출하지 않고 방문 완료는 복구하지 않는다.

### 13. 픽업 목록·상세·처리
- routes: `/partner/stores/:storeId/pickups`, `/pickups/:pickupReservationId`
- 목록·상세 `GET /api/v1/store-operator/stores/{storeId}/pickup-reservations[/{pickupReservationId}]`
- 취소 `POST .../{pickupReservationId}/cancellations`
- 수령 완료 `POST .../{pickupReservationId}/fulfillments`
- 상태 `CONFIRMED/CANCELLED/PICKED_UP`; CAFE·BAKERY와 pickupEnabled 조건이 아니면 route·menu 미등록

### 14. 대표자 내 정보
- route: `/partner/profile`
- `GET/PATCH /api/v1/store-operator-accounts/me`
- 표시 이름만 수정. 이메일·휴대전화·비밀번호 범용 PATCH 금지

## 2차 MVP

고객용 자연어 검색·추천·지도 때문에 대표자 관리 API나 화면을 발명하지 않는다. 운영자 계약이 생기지 않는 한 1차 구조를 유지한다.

## 고도화 화면 15~21

### 15. 운영 대시보드
통계 OpenAPI가 생길 때 기준 시각을 포함한 read-only module로 추가한다.

### 16. 웨이팅 설정
사용 여부·접수 조건의 versioned 설정 API와 영향 확인 명령을 사용한다.

### 17. 실시간 웨이팅
목록 snapshot과 SSE를 분리하고 호출·입장은 허용된 명령 endpoint로만 처리한다.

### 18. 체크인·노쇼
체크인과 노쇼 후보·확정 계약을 예약 기존 상태 enum과 분리한다.

### 19. 결제·환불
예약 상세에 별도 query model로 결합하며 프론트에서 환불 성공을 추측하지 않는다.

### 20. 이미지 관리
presigned upload·완료 확인 계약이 생길 때 추가하고 사업자등록증 저장 경계와 분리한다.

### 21. 추천 메뉴 관리
3~5개 제한과 순서를 서버가 재검증하는 독립 API가 있을 때 대표 여부와 별도 모듈로 추가한다.

capability 또는 build flag는 내비게이션·route registration·query·mutation을 함께 제어한다. 예약·픽업 기존 enum에 고도화 상태를 합치지 않는다.

## 검증 체크리스트

- 가입 실패와 매장 등록 실패가 서로의 데이터를 롤백·삭제하지 않는다.
- 매장 등록 성공이 승인 대기로 이동하지 않는다.
- 다른 매장 ID로 모든 조회·명령이 거부된다.
- 운영시간·접수시간은 전체 주간, 수용량은 날짜 전체, 재고 수정은 풀 전체 계약을 지킨다.
- 예약·픽업 취소 후 자원 복구를 프론트에서 별도 호출하지 않는다.
- 일반 사용자 토큰으로 대표자 API를 호출하지 않는다.
- 고도화 API가 없으면 관련 route·요청이 없다.
