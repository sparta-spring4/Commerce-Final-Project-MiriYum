# 소비자 마이페이지 예약 내역 조회 설계

## 1. 목적

GitHub 이슈 #62의 `GET /api/v1/consumer-accounts/me/reservations`를 실제 HTTP API로 연결한다.
현재 통합 OpenAPI에는 경로가 있지만 `ConsumerAccountController`에 메서드가 없어 404가 반환된다.

최신 `dev`에는 예약 도메인의 다음 공개 조회 계약이 이미 구현되어 있다.

- `ReservationHistorySearchRequest`
- `ReservationService.getConsumerReservationHistory()`
- `ReservationHistoryPageResponse`
- 소비자 ID·상태 조건을 포함한 `ReservationRepository` 조회

따라서 예약 조회 기능을 중복 구현하지 않고 마이페이지 HTTP 경계만 연결한다.

## 2. 최신 계약 대조 결과

인증 OpenAPI의 예약 내역 항목은 과거 계약인 `startTime`, `endTime`을 사용한다.
반면 최신 예약 정책과 구현은 다음 필드를 사용한다.

- `timeStatus`: `RESOLVED` 또는 `LEGACY_UNRESOLVED`
- `startAt`: 실제 offset을 포함한 시작 시각
- `serviceEndAt`: 실제 offset을 포함한 고객 서비스 종료 시각
- `timeZoneId`: 예약 계산 당시 매장 IANA 시간대

V15의 과거 예약은 실제 offset을 복원할 근거가 없어 `LEGACY_UNRESOLVED`와 null 시각으로 공개한다.
따라서 임의의 현지 시각을 만들어 내지 않고 예약 도메인의 최신 계약을 정본으로 사용한다.

## 3. API 계약

### 요청

- 메서드와 경로: `GET /api/v1/consumer-accounts/me/reservations`
- 인증: 소비자 Bearer Access Token 필수
- `status`: 선택값, `CONFIRMED`, `CANCELLED`, `FULFILLED`
- `page`: 기본 0, 0 이상
- `size`: 기본 20, 1~100
- `sort`: 기본 `createdAt,desc`
  - `createdAt,desc`
  - `createdAt,asc`
  - `serviceDate,desc`
  - `serviceDate,asc`

날짜 범위 필터는 현재 OpenAPI에 없으므로 추가하지 않는다.

### 응답

`ApiResponse<ReservationHistoryPageResponse>`를 반환한다.
예약 내역 항목은 다음 필드를 사용한다.

- `reservationId`
- `storeId`
- `storeName`
- `serviceDate`
- `timeStatus`
- `startAt`
- `serviceEndAt`
- `timeZoneId`
- `partySize`
- `status`
- `createdAt`

현재 구현과 예약 정책에 없는 `cancelledBy`는 공개하지 않는다.
취소 주체 저장은 별도 정책·DB 변경이 필요한 후속 범위다.

## 4. 호출 흐름과 의존성

```text
ConsumerAccountController
  -> ReservationService.getConsumerReservationHistory()
     -> ConsumerAccountService.getMe()로 활성 계정 확인
     -> ReservationRepository에서 인증된 consumerAccountId 범위 조회
```

`ReservationService`가 이미 `ConsumerAccountService`를 사용하므로 `ConsumerAccountService`에
`ReservationService`를 다시 주입하지 않는다. 그렇게 하면 Spring 순환 의존성이 생긴다.
마이페이지 Controller가 예약 도메인의 공개 Service 계약을 직접 호출한다.

## 5. 보안과 조회 규칙

- 소비자 ID는 `AuthenticatedPrincipal.accountId()`에서만 가져온다.
- 클라이언트가 다른 소비자 ID를 파라미터로 전달할 수 없다.
- 저장소 조회에는 항상 `consumerAccountId = 인증된 계정 ID`가 포함된다.
- 상태가 있으면 동일 조회에 상태 조건을 추가한다.
- 정지 계정은 `ReservationService`가 조회 전에 `ConsumerAccountService.getMe()`로 차단한다.
- 결과가 없으면 `200 OK`, `items: []`와 0건 페이지 메타데이터를 반환한다.

## 6. 문서 변경이 필요한 이유

기능을 새로 정의하기 위해 정책을 바꾸는 것이 아니다.
이미 예약 도메인에서 승인·구현된 시간 계약이 인증 OpenAPI에 반영되지 않은 문서 불일치를 수정한다.

- 인증 OpenAPI가 마이페이지 경로와 `ReservationHistoryStatus`, `ReservationHistoryItem`, `ReservationHistoryPageData` 스키마를 소유한다.
- 팀원이 먼저 정의한 세 스키마의 이름과 위치는 유지하고, 과거 시간 필드만 예약 도메인의 최신 공개 DTO와 맞게 갱신한다.
- 예약 도메인이 이미 소유한 `StoreLocalDate`, `ReservationTimeStatus`는 인증 문서에서 `$ref`로 재사용한다.
- 따라서 예약 OpenAPI에 마이페이지 전용 스키마를 중복 추가하거나 별도 린트 예외를 만들지 않는다.
- `auth-account/spec.md`의 도메인 책임 원칙은 이미 올바르므로 변경하지 않는다.
- DB 스키마와 Flyway 마이그레이션은 변경하지 않는다.

## 7. TDD 구현 순서

1. OpenAPI 계약 테스트에 인증 문서가 예약 도메인의 최신 내역 스키마를 참조해야 한다는 실패 테스트를 추가한다.
2. 계약 테스트 실패를 확인한 뒤 예약·인증 OpenAPI를 최소 범위로 정렬한다.
3. 마이페이지 컨트롤러 테스트에 예약 내역 성공 응답, 빈 결과, 잘못된 정렬 거절을 추가한다.
4. 현재 404 또는 경로 부재로 테스트가 실패하는지 확인한다.
5. `ConsumerAccountController`에 조회 메서드와 `ReservationService` 의존성을 추가한다.
6. 관련 컨트롤러·예약 계약 테스트를 실행한다.
7. 기존 예약 서비스·저장소 테스트를 실행해 본인 범위, 상태 필터와 정렬 회귀가 없는지 확인한다.
8. 커밋 전 사용자 확인을 받은 뒤 필요한 최종 검증을 실행한다.

## 8. 변경 파일

- `backend/src/main/java/com/miriyum/domain/consumer/controller/ConsumerAccountController.java`
- `backend/src/test/java/com/miriyum/domain/consumer/controller/ConsumerAccountControllerTest.java`
- `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java`
- `docs/specs/auth-account/openapi.yaml`

기존 예약 Service, Repository, 조회 요청과 응답 DTO는 이미 요구사항을 구현하므로 변경하지 않는다.

## 9. 제외 범위

- 날짜 범위 검색 추가
- 결제·환불·노쇼·체크인 필드
- 취소 주체 저장과 마이그레이션
- 예약 수정·취소 API
- 운영자 예약 목록 변경
