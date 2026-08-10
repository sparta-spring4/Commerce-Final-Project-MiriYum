# MiriYum 일반 사용자 풀스택 화면 구현 상세 지시서

## 작업 원칙

적용 단계는 1차 MVP, 2차 MVP, 고도화이며 현재 계약이 있는 단계만 실제 서비스에 노출한다.

디자인 문서 `01-consumer-design.md`의 화면 번호와 이 문서의 번호를 대응한다. 현재 브랜치의 `AGENTS.md`, `docs/specs/mvp1-common/ownership.md`, 도메인별 `spec.md`·`openapi.yaml`, 실제 코드 순으로 확인한다. 아래 경로보다 현재 승인된 OpenAPI가 바뀌었다면 OpenAPI에 맞추되 임의 endpoint를 만들지 않는다.

Access JWT는 일반 사용자 셸 메모리에만 보관한다. Refresh는 `MIRIYUM_CONSUMER_REFRESH` HttpOnly 쿠키, CSRF는 `MIRIYUM_CONSUMER_XSRF_TOKEN`과 `X-CSRF-TOKEN`을 사용한다. Web Storage에 토큰을 저장하지 않는다.

## 공통 프론트 구조

- 공개 셸: 매장 검색·상세·catalog
- 인증 셸: 예약·픽업·마이페이지
- 라우트 가드: 미인증은 로그인으로 보내고 원래 목적지를 보존
- API 모듈: consumer-auth, stores, reservations, pickup-reservations, consumer-account
- 조회 상태·폼 상태·서버 업무 상태를 분리
- 쓰기마다 같은 사용자 의도 재시도에는 안정적인 `Idempotency-Key` 사용
- 오류 메시지 문자열이 아니라 HTTP와 `code`로 분기

## 화면별 구현

### 1. 회원가입

- 권장 라우트: `/signup`
- API: `POST /api/v1/consumer-auth/accounts`
- 요청: 이메일·비밀번호·닉네임과 실제 OpenAPI의 `emailVerificationReference`, `identityVerificationReference`
- 검증: 이메일, 닉네임 2~20자, 비밀번호 확인, 확인 참조 존재
- 오류: `ACCOUNT_001` 이메일 중복, `ACCOUNT_002` 휴대전화 중복, `ACCOUNT_003/004` 확인 실패
- 성공: 로그인으로 이동하며 자동 로그인하지 않는다.

### 2. 로그인·세션 복구

- 라우트: `/login`
- 로그인: `POST /api/v1/consumer-auth/sessions`
- CSRF 준비: `GET /api/v1/consumer-auth/csrf-tokens/current`
- 재발급: `POST /api/v1/consumer-auth/token-refreshes`
- 로그아웃: `DELETE /api/v1/consumer-auth/sessions/current`
- `AUTH_005`는 계정 존재 여부를 나누지 않고 통합 실패 문구 사용
- `COMMON_010`은 `Retry-After`로 남은 시간 표시
- `AUTH_002`는 한 번의 namespace 재발급 후 원 요청 재시도, 실패하면 세션 만료 사유와 로그인 이동
- 일반 사용자 요청에 store-operator 쿠키·토큰을 사용하지 않는다.

### 3. 매장 찾기

- 라우트: `/stores`
- API: `GET /api/v1/stores`, `GET /api/v1/store-categories`, `GET /api/v1/store-tags`
- query: keyword, region, storeCategoryCode, serviceDate·startTime·partySize, availableOnly, 페이지 계약
- 지역은 `SEOUL/BUSAN/DAEGU/DAEJEON/GWANGJU`만 제출
- 예약 조건 세 개가 모두 있을 때만 availableOnly를 허용하고 `NOT_REQUESTED/AVAILABLE/UNAVAILABLE`를 표시
- URL search params를 필터 원본으로 사용해 뒤로가기와 공유를 유지한다.

### 4. 매장 상세

- 라우트: `/stores/:storeId`
- API: `GET /api/v1/stores/{storeId}`, `GET /api/v1/stores/{storeId}/menus`, 필요 시 가용성 조회
- `STORE_001`은 찾을 수 없음, 운영·게시 상태에 따라 예약·픽업 진입을 숨김
- catalog 코드 대신 서버 표시명을 렌더링하고 내부 운영자 FK를 요구하지 않는다.

### 5. 예약 날짜·시간·인원

- 라우트: `/stores/:storeId/reserve`, `/reservations/:reservationId`
- 예약 작성 상태: 날짜·시간·성인·아동·영유아·menuSelections를 한 흐름의 draft로 관리
- 가용성은 공개 조회로 미리 보여도 최종 성공을 보장하지 않는다.

### 6. 대표 메뉴 사전 선택

- 메뉴 가용성: `GET /api/v1/stores/{storeId}/menu-hold-availability`
- 선택 수량은 서버 잔여를 넘지 않게 안내하되 최종 쓰기에서 다시 검증한다.
- 메뉴 없음은 menuSelections 생략 또는 빈 배열. 별도 skip API 금지

### 7. 예약 최종 확인·생성

- 생성: `POST /api/v1/reservations`; endTime·연락처·사용자 ID를 보내지 않는다.
- 프론트가 예약 생성 후 메뉴 홀드 쓰기를 별도로 호출하지 않는다.
- 성공은 `CONFIRMED`만 허용하고 상세 `GET /api/v1/reservations/{reservationId}`로 이동

### 8. 예약 상세·취소

- 취소: `POST /api/v1/reservations/{reservationId}/cancellations`
- 오류: `RESERVATION_002` 시간 불가, `_003` 수용량, `_004` 중복, `_006` 취소 불가, `_007` 버전 변경, `_009` 인원; `MENU_HOLD_001/002`는 메뉴 재선택 또는 메뉴 없이 진행
- 성공 후 매장 검색 가용성, 내 예약 목록, 해당 상세 캐시를 무효화한다.

### 9. 내 예약 목록

- 라우트: `/mypage/reservations`
- API: `GET /api/v1/consumer-accounts/me/reservations`
- query: 상태 `CONFIRMED/CANCELLED/FULFILLED`, 날짜, 0 기반 페이지
- 빈 결과는 오류가 아닌 200·빈 목록으로 렌더링
- 결제·노쇼·체크인 필드를 추측해 DTO에 추가하지 않는다.

### 10. 픽업 선택·생성

- 라우트: `/stores/:storeId/pickup`, `/pickup-reservations/:pickupReservationId`, `/mypage/pickups`
- 가용성: `GET /api/v1/stores/{storeId}/pickup-availability`
- 생성: `POST /api/v1/pickup-reservations`; pickupDate·pickupTime·메뉴와 수량, endTime·partySize 제외

### 11. 픽업 목록·상세·취소

- 상세: `GET /api/v1/pickup-reservations/{pickupReservationId}`
- 취소: `POST /api/v1/pickup-reservations/{pickupReservationId}/cancellations`
- 상태: `CONFIRMED/CANCELLED/PICKED_UP`만 사용
- `PICKUP_002` 자격 없음, `_003` 구간, `_004` 수량, `_005` 전이, `_006` 취소 불가
- 일반 예약 캐시·수용량을 픽업 성공으로 수정하지 않는다.

### 12. 마이페이지·프로필

- 라우트: `/mypage`, `/mypage/profile`
- 조회·수정: `GET/PATCH /api/v1/consumer-accounts/me`
- PATCH는 닉네임만 보내고 Idempotency-Key 사용
- `ACCOUNT_005`는 서버가 제공하는 다음 변경 가능 시각을 기준으로 안내
- 이메일·휴대전화·비밀번호 변경 폼을 1차에 만들지 않는다.

## 2차 MVP 화면

### 13. 자연어 검색·추천
승인된 자연어·개인화 API가 확인될 때 별도 route chunk로 등록한다. 일반 검색 API를 임의 프롬프트 endpoint로 감싸지 않는다.

### 14. 품절 대체·주변 매장 추천
대체 API의 추천 이유·원 매장·대체 storeId를 구분한다. 다른 매장 선택 시 기존 가용성·메뉴 draft를 폐기하고 새 매장 기준으로 재검증한다.

### 15. 지도·목록
카카오맵 계약과 키 관리가 확인될 때 lazy load한다. 위치 거부·SDK 실패에도 `/stores` 목록은 유지한다.

## 고도화 화면

### 16. 소셜 로그인
승인된 provider callback·계정 연결 계약이 있을 때 consumer-auth 모듈에 추가한다.

### 17. 예약금 결제
결제 요청·결과 조회·웹훅 반영 상태 API를 분리하고 결과 불명을 성공으로 표시하지 않는다.

### 18. 결제·환불 내역
본인 결제 read API만 사용하며 예약 상태와 결제 상태를 별도 모델로 유지한다.

### 19. 웨이팅 등록
등록·예약 충돌과 위치 검증 계약이 있을 때만 route와 mutation을 활성화한다.

### 20. 현재 웨이팅
snapshot 조회와 SSE 상태를 분리하고 마지막 event cursor 이후 재연결한다.

### 21. 체크인·노쇼
QR·확인번호 명령과 노쇼 후보·확정 상태를 예약 enum에 섞지 않는다.

### 22. 알림
목록·읽음 API와 관련 화면 deep link를 허용 목록으로 연결한다.

기능별 OpenAPI·권한·상태 기계가 없으면 CSS 비표시가 아니라 route·menu·query·mutation을 모두 제외한다.

## 검증 체크리스트

- 일반·대표자 namespace 토큰과 쿠키가 교차 사용되지 않는다.
- 비회원 검색·상세, 인증 후 원래 예약 화면 복귀가 동작한다.
- 불완전한 예약 조건으로 availableOnly 요청을 보내지 않는다.
- 예약과 메뉴 홀드를 두 번의 쓰기로 분리하지 않는다.
- 마지막 수용량·메뉴 수량 충돌을 성공으로 낙관 표시하지 않는다.
- 개인 예약·픽업의 부재와 타인 소유 404를 구분 노출하지 않는다.
- 고도화 API가 없을 때 관련 라우트·메뉴·요청이 없다.
