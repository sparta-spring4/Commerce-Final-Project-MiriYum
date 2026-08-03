# Store 예약·픽업 거래 자격 공개 계약 설계

## 목표

Reservation과 Pickup 도메인이 운영자 principal이나 Store 내부 Entity·Repository 없이 `storeId`만으로 신규 거래 가능 여부를 검증하도록 Store Core 소유의 공개 읽기 계약을 제공한다.

## 확인된 기준

- 기준 커밋은 2026-08-03 PR #86 병합 후 최신 `origin/dev`의 `5e94791`이다.
- #33은 완료됐고 Store 운영자 권한 계약을 소유한다.
- #83과 PR #84는 완료·병합됐으며 활성 예약 접수 구간 조회만 소유한다.
- #85/PR #86은 완료·병합됐고 `storeId + menuId` 기반 잠금 계약인 `MenuTransactionEligibility`만 소유한다. 이 설계는 해당 파일과 메서드를 변경하지 않는다.
- 현재 1차 MVP의 `VerificationStatus` 값은 `APPROVED`뿐이다. 새 심사 상태를 발명하지 않고, 테스트에서는 `APPROVED`가 아닌 값이 fail-closed 되는지를 `null` 상태로 모사한다.

## 선택한 구조

`StoreTransactionEligibilityService` 하나에 목적별 공개 메서드 두 개를 둔다.

```java
StoreReservationTransactionEligibility
    requireReservationTransactionEligibility(long storeId);

StorePickupTransactionEligibility
    requirePickupTransactionEligibility(long storeId);
```

성공 DTO는 각각 검증된 `storeId`만 가진다. 성공 자체가 해당 목적의 모든 Store 조건을 통과했다는 증명이며, 다른 목적의 mode나 Store 내부 상태 enum을 노출하지 않는다.

```java
public record StoreReservationTransactionEligibility(long storeId) {}
public record StorePickupTransactionEligibility(long storeId) {}
```

서비스의 Repository 생성자는 package-private로 두고 공개 비즈니스 메서드만 외부 도메인이 소비하게 한다. Spring은 해당 생성자로 Bean을 구성할 수 있지만 외부 도메인의 컴파일 계약에는 `StoreRepository`가 나타나지 않는다.

## 대안과 기각 근거

1. `StoreService`에 두 메서드를 추가하는 방식은 기존 관리 명령 책임과 #85의 잠금 기반 메뉴 거래 책임을 더 키우고, read-only Store-only 계약과 동시성 의미를 섞는다.
2. Reservation/Pickup별 서비스를 각각 만드는 방식은 Store 조회와 공통 상태 검증을 두 군데에 중복한다.
3. 한 DTO에 `reservationEligible`과 `pickupEligible`을 함께 반환하는 방식은 소비자에게 불필요한 capability를 노출하고 실패 원인을 후속 도메인이 다시 해석하게 한다.

## 판정 순서와 오류

### 일반 예약

1. 매장 없음: `STORE_001`
2. `verificationStatus != APPROVED`: `STORE_007`
3. `operationStatus != OPEN`: `STORE_005`
4. `reservationEnabled == false`: `STORE_005`
5. 성공: `StoreReservationTransactionEligibility(storeId)`

### Pickup

1. 매장 없음: `STORE_001`
2. `verificationStatus != APPROVED`: `STORE_007`
3. `operationStatus != OPEN`: `STORE_005`
4. `pickupEligibility != ELIGIBLE`: `STORE_008`
5. `pickupEnabled == false`: `STORE_005`
6. 성공: `StorePickupTransactionEligibility(storeId)`

`STORE_008`의 기존 메시지는 기능 활성화에 초점이 있지만 코드 이름과 승인된 정책 의미는 중앙 픽업 자격 부재다. 사용자 승인에 따라 새 오류 코드를 추가하지 않고 Pickup 신규 거래의 중앙 자격 실패에도 재사용한다. mode 비활성은 목적별 기능이 현재 Store 상태에서 거래를 받을 수 없다는 의미로 `STORE_005`를 사용한다.

## 데이터 흐름과 트랜잭션

- 두 메서드 모두 `@Transactional(readOnly = true)`다.
- `StoreRepository.findById(storeId)`를 한 번 호출한다.
- `findByIdForUpdate`나 다른 잠금 쿼리를 사용하지 않는다.
- 상태 변경, `save`, `flush`, 감사 사건, 멱등 기록을 만들지 않는다.
- #85의 메뉴 잠금·메뉴 상태 검증은 호출하거나 복제하지 않는다.

## 공개 경계

외부 도메인에 공개되는 형식은 서비스의 두 메서드와 두 DTO뿐이다. 공개 메서드의 매개변수·반환 타입과 DTO record component에는 다음이 나타나지 않는다.

- `Store`
- `StoreRepository`
- `ManagedStoreResponse`
- `VerificationStatus`, `OperationStatus`, `PickupEligibility`
- 운영자 principal 또는 운영자 계정 ID

## 테스트 설계

서비스 단위 테스트가 다음 실패·성공을 실제 `Store` 상태로 검증한다.

- 존재하지 않는 매장
- `APPROVED`가 아닌 매장 fail-closed
- `TEMPORARILY_CLOSED`
- `CLOSED`
- 예약 mode 비활성
- Pickup mode 비활성
- Pickup 자격 없음
- 정상 예약 가능
- 정상 Pickup 가능
- 정상 조회가 비잠금 `findById`만 사용함

별도 공개 계약 테스트가 외부 소비자 관점에서 다음을 검증한다.

- 공개 메서드는 `storeId`만 입력받는다.
- 목적별 DTO만 반환한다.
- 공개 생성자가 없어 Repository가 생성 계약으로 노출되지 않는다.
- DTO는 `storeId` 외 record component를 갖지 않는다.
- Entity·Repository·관리 DTO·내부 enum이 공개 메서드와 DTO에 나타나지 않는다.

## 범위 제외

- #85의 `MenuTransactionEligibility`와 Store/Menu 잠금 검증
- #43, #48, #49의 소비 코드
- Controller, HTTP API, OpenAPI route
- Store Entity·Repository·enum·오류 코드 변경
- Flyway migration
- 관련 없는 리팩터링

## 이슈 관계

- follows: #33
- related: #83, #85
- blocks: #43, #48, #49
- blocked by: 없음. #33, #83/PR #84, #85/PR #86은 완료됐고 #85 계약은 독립적인 메뉴 거래 경계다.
