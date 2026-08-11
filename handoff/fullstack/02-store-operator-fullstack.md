# MiriYum 식당 대표자 풀스택 화면 구현 상세 지시서

> **문서 지위:** 이 문서는 프론트 작업을 위한 비정본 인계 자료다. 제품·정책·아키텍처·API 사실은 이 문서가 소유하지 않는다. 충돌하거나 구현 시점이 달라졌다면 [`AGENTS.md`](../../AGENTS.md), [`ai/document-routing.md`](../../ai/document-routing.md), [`docs/00-index.md`](../../docs/00-index.md), 활성 [`service-policies`](../../docs/service-policies/README.md), 도메인별 `spec.md`·`openapi.yaml`, 실제 Controller·테스트 순으로 다시 확인한다.

> **직접 대조:** [`ownership.md`](../../docs/specs/mvp1-common/ownership.md), [`auth-account/openapi.yaml`](../../docs/specs/auth-account/openapi.yaml), [`store-search/openapi.yaml`](../../docs/specs/store-search/openapi.yaml), [`reservation/openapi.yaml`](../../docs/specs/reservation/openapi.yaml), [`menu-hold-pickup/openapi.yaml`](../../docs/specs/menu-hold-pickup/openapi.yaml), 실제 [`StoreOperatorAuthController`](../../backend/src/main/java/com/miriyum/domain/storeoperator/controller/StoreOperatorAuthController.java)·[`StoreOperatorAccountController`](../../backend/src/main/java/com/miriyum/domain/storeoperator/controller/StoreOperatorAccountController.java)·[`StoreController`](../../backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java)·[`StoreScheduleController`](../../backend/src/main/java/com/miriyum/domain/store/schedule/controller/StoreScheduleController.java)·[`StoreReservationController`](../../backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java)·[`PickupStoreManagementController`](../../backend/src/main/java/com/miriyum/domain/pickup/controller/PickupStoreManagementController.java)를 기준으로 한다.

> **화면 경로 정본:** [`frontend/src/app/routes.ts`](../../frontend/src/app/routes.ts)가 전체 화면 경로를 단독 소유한다. 현재 등록값은 `ROUTES.home=/`, `ROUTES.consumerSignIn=/sign-in`, `ROUTES.storeOperatorSignIn=/store-operator/sign-in`, `ROUTES.forbidden=/forbidden`이며, 이 문서는 그중 식당 대표자 로그인 경로와 공통 접근 거부 경로만 소비한다. 화면 기능이 있어도 `routes.ts`에 없는 `/partner/**` 등의 경로·내비게이션·라우트 가드는 담당 화면 Issue가 소유 파일과 테스트를 함께 갱신하기 전까지 만들지 않는다.

## 작업 원칙

적용 단계는 1차 MVP, 2차 MVP, 고도화이며 현재 계약이 있는 단계만 실제 서비스에 노출한다.

`02-store-operator-design.md`와 화면 번호를 맞춘다. Access JWT는 대표자 셸 메모리, Refresh는 `MIRIYUM_STORE_OPERATOR_REFRESH`, CSRF는 `MIRIYUM_STORE_OPERATOR_XSRF_TOKEN`과 `X-CSRF-TOKEN`을 사용한다. 일반 사용자 namespace와 공유하지 않는다. 모든 storeId 명령은 서버의 대표 운영자 권한 결과를 따른다.

인증 화면과 보호 셸을 분리하고, 현재 매장 ID가 바뀌면 이전 매장의 query·mutation·폼 draft를 취소·격리한다. 아직 등록되지 않은 보호 화면의 공통 prefix를 이 문서에서 정하지 않는다.

## 화면별 구현

### 1. 대표자 회원가입
- 화면 경로: 현재 `routes.ts`에 미등록. 대표자 회원가입 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- API: `POST /api/v1/store-operator-auth/accounts`
- 요청은 `email`, `password`, `passwordConfirm`, `phoneNumber`, `displayName`만 보낸다. 스키마가 `additionalProperties: false`이므로 이메일·본인확인 참조 등 임의 필드를 추가하지 않는다.
- `400 COMMON_001` 입력 오류, `409 ACCOUNT_001/002` 이메일·휴대전화 중복, `429 COMMON_010` 요청 제한을 처리한다.
- 계정 생성과 매장 등록을 한 요청으로 합치지 않는다. 성공 후 로그인.

### 2. 대표자 로그인
- 화면 경로: `/store-operator/sign-in` (`ROUTES.storeOperatorSignIn`, `SIGN_IN_PATH.storeOperator`)
- 로그인 `POST /api/v1/store-operator-auth/sessions`
- CSRF `GET /api/v1/store-operator-auth/csrf-tokens/current`
- 재발급 `POST /api/v1/store-operator-auth/token-refreshes`
- 로그아웃 `DELETE /api/v1/store-operator-auth/sessions/current`
- 실패는 `AUTH_005`, 제한은 `COMMON_010`, 만료 재발급 실패 시 사유를 보존해 로그인 이동
- `returnTo` 복귀와 매장 등록 성공 뒤의 `storeId` 기반 이동은 대상 화면 Issue가 해당 경로와 테스트를 `routes.ts`에 등록한 경우에만 연결한다. 등록 전에는 목적 URL을 추측하지 않는다.
- 현재 없는 계약은 로그인한 운영자 소유 매장 목록 `GET /api/v1/store-operator/stores`다. 따라서 일반 로그인 직후 `매장 없음/있음`을 추측해 자동 분기하거나 공개 검색 결과로 소유 매장을 찾지 않는다. 반면 알려진 `storeId`가 있으면 `GET /api/v1/store-operator/stores/{storeId}` 단건 조회를 사용할 수 있다.

### 3. 매장 등록
- 화면 경로: 현재 `routes.ts`에 미등록. 매장 등록 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- catalog: `GET /api/v1/store-categories`, `/api/v1/store-tags`
- 생성: `POST /api/v1/store-operator/stores` + Idempotency-Key
- 요청: `businessRegistrationNumber`, `businessType(CAFE/BAKERY/OTHER)`, `name`, `description`, `region`, `address`, IANA `timeZoneId`, `storeCategoryCode`, `tagCodes`, `modes`, `applicantSelfAttested=true`, `requiredTermsAgreed=true`
- 주소나 브라우저 기본값으로 시간대를 추측하지 않고 사용자가 확인한 IANA 식별자를 제출한다. 자기확약과 필수 입점 약관 동의가 모두 확인되기 전에는 생성 요청을 보내지 않는다.
- 운영자 ID·승인 필드·파일·이미지를 보내지 않는다.
- 1차 성공 응답은 즉시 `verificationStatus=APPROVED`; 승인 대기 route를 만들지 않는다.
- `STORE_002` 사업자등록번호 중복과 `STORE_004` catalog 오류를 필드에 연결한다. 등록 업종은 픽업 판정에 사용하지 않고 모든 업종에서 `pickupEnabled`를 허용한다.

### 4. 관리 홈
- 화면 경로: 현재 `routes.ts`에 미등록. 관리 홈 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 등록 응답 또는 등록된 보호 경로의 알려진 `storeId`를 보존하되, 기본정보·설정 page link는 각 대상 화면 Issue가 `routes.ts`와 테스트에 경로를 등록한 항목만 구성한다. 현재 계약에 없는 운영 매장 목록 query를 만들지 않는다.
- 1차에는 통계 API를 추측하지 않고 설정 완성도와 운영 상태만 표현한다.

### 5. 매장 정보
- 화면 경로: 현재 `routes.ts`에 미등록. 매장 정보 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- API: `GET/PATCH /api/v1/store-operator/stores/{storeId}`
- 변경 가능한 필드만 PATCH하고 Idempotency-Key 사용
- `STORE_003`은 권한 없음, `STORE_005/007`은 현재 매장·입점 상태 충돌로 처리한다. 업종을 근거로 픽업 변경을 거부하지 않는다.

### 6. 운영시간
- 화면 경로: 현재 `routes.ts`에 미등록. 운영시간 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 초안 저장: `PUT /api/v1/store-operator/stores/{storeId}/operating-hours`
- 게시: `POST /api/v1/store-operator/stores/{storeId}/operating-hours/{version}/publication`; 예약 게시 취소: `POST /api/v1/store-operator/stores/{storeId}/operating-hours/{version}/publication-cancellation`
- PUT은 월~일 전체 초안을 만든다. 일부 요일 로컬 병합 금지. 게시 요청은 `IMMEDIATE/SCHEDULED`, 예약 게시일 때 offset 포함 `effectiveAt`, 필수 `changeReason`을 사용한다.
- 현재 운영자용 GET은 없다. 기존 설정을 읽어 편집하거나 재접속 뒤 복구하는 route는 조회 계약이 승인되기 전까지 활성화하지 않는다.
- 구간 `[startTime,endTime)`, 브레이크타임을 분리하고 `STORE_006`을 구간별 표시
- 저장 성공 후 해당 매장 상세·공개 검색 관련 query를 갱신하되 기존 예약을 수정하지 않는다.

### 7. 예약 접수 시간대
- 화면 경로: 현재 `routes.ts`에 미등록. 예약 접수 시간대 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 접수 구간 초안: `PUT /api/v1/store-operator/stores/{storeId}/reservation-time-slots`; 게시: `POST /api/v1/store-operator/stores/{storeId}/reservation-time-slots/{version}/publication`; 예약 게시 취소: `POST /api/v1/store-operator/stores/{storeId}/reservation-time-slots/{version}/publication-cancellation`
- 예약 시간 정책 초안: `PUT /api/v1/store-operator/stores/{storeId}/reservation-time-policies`; 게시: `POST /api/v1/store-operator/stores/{storeId}/reservation-time-policies/{version}/publication`; 예약 게시 취소: `POST /api/v1/store-operator/stores/{storeId}/reservation-time-policies/{version}/publication-cancellation`
- 접수 구간은 전체 주간 초안이며 영업시간 밖·브레이크 충돌을 표시한다. 예약 시간 정책은 `slotInterval`, `serviceDuration`, `turnoverDuration`을 별도로 다룬다.
- 두 계약 모두 현재 운영자용 GET이 없으므로 기존 설정 편집·재접속 복구는 조회 계약 승인 전까지 활성화하지 않는다.
- 수용량 필드를 이 요청에 섞지 않는다.

### 7-1. 정기·임시 휴점

- 화면 경로: 현재 `routes.ts`에 미등록. 휴점 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 정기 휴무 전체 초안: `PUT /api/v1/store-operator/stores/{storeId}/regular-closures`에 `weeklyDays`, `dates`와 Idempotency-Key를 보낸다.
- 정기 휴무 게시: `POST /api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication`에 `publicationMode`, 필수 `changeReason`, 예약 게시일 때 offset 포함 `effectiveAt`을 보낸다.
- 정기 휴무 예약 게시 취소: `POST /api/v1/store-operator/stores/{storeId}/regular-closures/{version}/publication-cancellation`에 `changeReason`을 보낸다.
- 임시 휴무 등록: `POST /api/v1/store-operator/stores/{storeId}/temporary-closures`에 offset 포함 `startAt`, `endAt`, `reason(MAINTENANCE/STAFFING/PRIVATE_EVENT/OTHER)`, 선택 `publicMessage`를 보낸다.
- 임시 휴무 종료 변경: `PUT /api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/end-at`에 `endAt`, `changeReason`을 보낸다. 취소는 `POST /api/v1/store-operator/stores/{storeId}/temporary-closures/{closureId}/cancellation`에 `changeReason`을 보낸다.
- 모든 쓰기에 Idempotency-Key를 사용하고 `STORE_003` 권한 없음, `STORE_001` 매장 없음, `409` 게시·기간 충돌을 처리한다. 현재 운영자용 휴점 목록 GET은 없으므로 서버 응답으로 받은 버전·식별자를 보존하되 임의 조회 API를 만들지 않는다.

### 8. 메뉴 목록·등록·수정
- 화면 경로: 메뉴 목록·등록·수정 모두 현재 `routes.ts`에 미등록. 각 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- `GET/POST /api/v1/store-operator/stores/{storeId}/menus`
- `GET/PUT /api/v1/store-operator/stores/{storeId}/menus/{menuId}`로 조회·내용 초안 저장
- 게시·예약 게시 취소·운영 종료는 각각 `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication`, `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication-cancellation`, `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/retirement`
- 노출·판매 상태는 각각 `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/visibility`, `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/selling-status`
- menu category catalog는 `/api/v1/menu-categories`
- 내용 초안 요청에는 기본정보와 함께 `allergenInformationStatus`, `allergenDisclosures`, `originInformationStatus`, `originDisclosures`, `alcoholic`을 모두 보낸다. 알레르기·원산지·주류 여부를 운영자에게 명시적으로 입력받으며, 빈 배열이나 `NOT_REGISTERED`를 안전·해당 없음으로 추론하지 않는다.
- 버전 `DRAFT/SCHEDULED/PUBLISHED/RETIRED`, 노출 `VISIBLE/HIDDEN`, 판매 `SELLING/PAUSED`를 독립 필드로 유지
- `AVAILABLE/SOLD_OUT`은 이 폼에 저장하지 않는다.

### 9. 예약 수용량
- 화면 경로: 현재 `routes.ts`에 미등록. 예약 수용량 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- `PUT /api/v1/store-operator/stores/{storeId}/reservation-capacities/{serviceDate}`. 현재 운영자용 GET은 없으므로 기존 날짜 설정을 읽어 편집하는 route는 조회 계약 승인 전까지 활성화하지 않는다.
- 날짜별 전체 버킷, maxPeople·maxTeams·minPartySize·maxPartySize·infantsAllowed
- PUT 성공 응답의 현재 점유와 잔여를 표시하되 클라이언트가 재계산해 원장처럼 저장하지 않는다.
- `RESERVATION_008/009`, `COMMON_008` 처리

### 10. 메뉴 수량·품절
- 화면 경로: 현재 `routes.ts`에 미등록. 재고 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 목록 `GET /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets`
- 생성 `POST /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets`
- 수정 `PATCH /api/v1/store-operator/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}`
- 생성은 `menuId`, `serviceDate`, `startTime`, `endDate`, `endTime`, `totalSupply`, `pools.onlineHold`, `pools.onsite`, `pools.shared`, `sharedOnlineAllowed`, `availabilityStatus`를 모두 제출한다.
- 수정은 제공 구간 식별자를 경로에서 유지하고 `totalSupply`, 세 `pools` 값, `sharedOnlineAllowed`, `availabilityStatus`를 모두 제출한다. `sharedOnlineAllowed`를 공유 수량 존재 여부로 추론하지 않는다.
- `MENU_HOLD_004` 합계, `_005` 사용량 이하 축소, `_006` 전이 오류
- 성공 후 해당 버킷·공개 가용성·픽업 가용성 query 무효화

### 11. 예약 목록
- 화면 경로: 예약 목록·상세 모두 현재 `routes.ts`에 미등록. 각 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 목록: `GET /api/v1/store-operator/stores/{storeId}/reservations`; 상세: `GET /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}`
- 날짜·상태·0 기반 page; `CONFIRMED/CANCELLED/FULFILLED`

### 12. 예약 상세·처리

- 상세 조회 뒤 현재 상태에 허용되는 명령만 렌더링한다.
- 취소 `POST /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/cancellations`, 사유와 Idempotency-Key
- 방문 완료 `POST /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/fulfillments`
- 범용 status PATCH 금지. 취소는 수량 복구 API를 별도로 호출하지 않고 방문 완료는 복구하지 않는다.

### 13. 픽업 목록·상세·처리
- 화면 경로: 픽업 목록·상세 모두 현재 `routes.ts`에 미등록. 각 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 목록 `GET /api/v1/store-operator/stores/{storeId}/pickup-reservations`; 상세 `GET /api/v1/store-operator/stores/{storeId}/pickup-reservations/{pickupReservationId}`
- 취소 `POST /api/v1/store-operator/stores/{storeId}/pickup-reservations/{pickupReservationId}/cancellations`
- 수령 완료 `POST /api/v1/store-operator/stores/{storeId}/pickup-reservations/{pickupReservationId}/fulfillments`
- 상태 `CONFIRMED/CANCELLED/PICKED_UP`; 등록 업종과 무관하게 `pickupEnabled=false`이면 route·menu 미등록

### 14. 대표자 내 정보
- 화면 경로: 현재 `routes.ts`에 미등록. 대표자 내 정보 화면 Issue가 소유 파일과 테스트에 등록하기 전에는 URL을 추측하지 않는다.
- 조회·표시 이름 수정: `GET/PATCH /api/v1/store-operator-accounts/me`
- 조회 응답의 `phoneNumber`가 `null`인 기존 계정에만 최초 연락처 등록 폼을 표시한다. `PUT /api/v1/store-operator-accounts/me/contact`에는 `phoneNumber`만 보내고 별도 `Idempotency-Key`를 사용한다.
- 연락처 등록은 `400 COMMON_001` 입력 오류, `409 ACCOUNT_002` 같은 운영자 계정 유형의 휴대전화 중복, `409 ACCOUNT_007` 이미 등록된 연락처 변경 시도를 구분한다. 성공하면 응답의 마스킹된 휴대전화를 읽기 전용으로 표시하고 등록 폼을 제거한다.
- 표시 이름만 범용 PATCH로 수정한다. 이메일·휴대전화·비밀번호 범용 PATCH는 금지하며, 등록된 번호 변경에 최초 연락처 등록 API를 재사용하지 않는다.

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
