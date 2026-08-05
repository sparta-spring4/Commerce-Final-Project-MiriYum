# 픽업 수량 공개 계약 설계

> 구현 artifact이며 제품 정본이 아니다. 제품 동작은 `docs/specs/menu-hold-pickup/spec.md`,
> `docs/specs/menu-hold-pickup/openapi.yaml`, `docs/service-policies/06-menu-hold.md`를 따른다.

## 목적과 범위

Issue #108은 픽업 도메인이 메뉴 수량 버킷, 수량 원장, MenuHold Entity·Repository에
접근하지 않고 다음 세 동작을 수행할 수 있는 최소 공개 계약을 제공한다.

- Store 공개 계약에서 검증한 메뉴와 정확한 서비스 구간의 온라인 가용량 조회
- 여러 메뉴 수량의 원자적 조건부 확보
- 최초 확보 operation을 기준으로 한 정확히 한 번 원래 풀 복구

픽업 예약 Entity·Repository·Controller, 픽업 HTTP API, 픽업 상태 기계와 공통 멱등
결과 저장은 Issue #43의 범위이며 이 설계에 포함하지 않는다. 기존 V13·V17 재고
버킷과 원장을 재사용하므로 새 Flyway migration도 추가하지 않는다.

## 선택한 구조

메뉴 수량 도메인의 공개 경계를 `MenuInventoryTransactionService`로 분리한다. 기존
`MenuHoldService`에는 일반 예약 메뉴 홀드의 create/release/fulfill만 유지하고, 기존
package-private `MenuInventoryService`는 버킷 잠금·조건부 갱신·원장 기록을 담당하는
내부 런타임으로 유지한다.

공개 서비스 구현은 내부 런타임에 위임하지만 다음 내부 세부사항을 소비자에게 노출하지
않는다.

- `MenuInventoryBucket`과 Repository
- 내부 bucket ID와 lock version
- 실제 `ONLINE_HOLD`·`SHARED` 풀 배분
- `MenuInventoryLedger` 행과 원장 조회 방식

이 구조는 기존 내부 클래스를 그대로 public으로 만드는 방식보다 계약 표면이 작고,
`MenuHoldService`에 수량 명령을 섞는 방식보다 픽업 fixture가 무관한 홀드 명령을
구현하지 않아도 된다는 장점이 있다.

## 공개 계약

### 온라인 가용량 조회

호출자는 Store 공개 계약으로 거래 가능한 메뉴와 픽업 제공 구간을 먼저 검증한다. 이후
메뉴 ID 목록과 `serviceDate`, `startTime`, `endDate`, `endTime`을 조회 요청으로
전달한다.

결과는 메뉴 ID 오름차순으로 안정적으로 정렬하며 각 항목에 다음 값을 포함한다.

- `menuId`
- 현재 `inventoryPolicyVersion`
- 서비스 날짜·시작·종료 구간과 `timeZoneId`
- `availableOnlineQuantity`
- 신규 거래 가능 여부를 나타내는 재고 가용 상태

온라인 가용량은 `ONLINE_HOLD` 잔여와 `sharedOnlineAllowed`인 `SHARED` 잔여의
합이다. `ONSITE`는 포함하지 않는다. 수동 `SOLD_OUT` 버킷은 잔여 수량과 별개로
신규 확보 불가 상태를 반환한다. 조회 결과는 확보 성공을 보장하지 않으며 확보 명령이
현재 정책과 수량을 다시 검증한다.

조회 요청의 메뉴 ID는 비어 있을 수 없고 중복을 제거한 뒤 처리한다. ID는 모두 양수여야
하며 서비스 구간은 증가해야 한다. 현재 버킷이 없는 메뉴는 `MENU_HOLD_003`으로
실패해 일부 메뉴만 반환하지 않는다.

### 조건부 확보

호출자는 논리 확보 명령마다 전역 고유한 `operationId`를 발급한다. 요청 항목은 조회에서
받은 메뉴 ID, 서비스 구간, 현재 정책 버전과 양수 수량을 포함한다. 중복 메뉴·구간 항목은
수량을 합산하되 정수 범위를 넘으면 요청을 거부한다.

공개 서비스의 확보 메서드는 `Propagation.MANDATORY`로 호출자의 MySQL 트랜잭션에
참여한다. 내부 런타임은 기존 #42 구현을 사용해 현재 버킷을 PK 오름차순으로 잠그고,
`ONLINE_HOLD → 허용된 SHARED` 순서로 계획한 뒤 조건부 SQL로 전체 수량을
차감한다. 하나라도 부족하거나 상태·정책이 바뀌면 예외를 던져 상위 픽업 거래와 모든
차감·원장이 함께 롤백되게 한다.

공개 결과는 `operationId`와 요청 항목별 확보 완료 정보만 제공한다. 내부 bucket ID와
풀별 배분은 반환하지 않는다.

### 원래 풀 복구

복구 요청은 새 전역 고유 `operationId`와 `sourceAcquireOperationId`를 전달한다.
공개 서비스의 복구 메서드도 `Propagation.MANDATORY`로 호출자 트랜잭션에 참여한다.

내부 런타임은 최초 확보 operation의 원장에서 실제 풀 배분을 읽고 bucket ID 오름차순으로
잠근 뒤 원래 `ONLINE_HOLD`와 `SHARED`에 복구한다. 동일 source operation에 대한
복구가 이미 있으면 수량을 다시 증가시키지 않고 멱등 성공한다. 픽업 멱등 replay는 상위
조정자가 최초 결과를 재생하고 이 서비스를 다시 호출하지 않는다.

## 오류 계약

새 오류 코드를 추가하지 않고 기존 계약을 그대로 전달한다.

| 조건 | 오류 |
|---|---|
| 현재 구간·정책 버킷 부재 | `MENU_HOLD_003` |
| 온라인 수량 부족 | `MENU_HOLD_002` |
| 수동 품절 또는 현재 상태에서 신규 확보 불가 | `MENU_HOLD_006` |
| 조건부 갱신·현재 정책 경합 | 기존 `MENU_HOLD_006` 의미의 상태 충돌 코드 |
| 승인된 Store·공통 검증 실패 | 원래 `STORE_###`·`COMMON_###` 전달 |

DTO 구조 검증은 생성자에서 `IllegalArgumentException`으로 처리하고, 런타임 정책·수량
오류는 `ServiceException(MenuHoldErrorCode)`로 처리한다.

## 소비자 fixture

픽업 도메인이 production fake 없이 독립적으로 컴파일·테스트할 수 있도록 test source에
공개 계약 fixture를 제공한다. fixture는 다음을 지원한다.

- 고정된 가용량 결과
- 확보·복구 명령 기록
- 성공 결과와 지정된 `ServiceException` 전달
- 멱등 replay에서 픽업 소비자가 수량 서비스를 다시 호출하지 않는 계약 검증
- 각 논리 확보·복구 명령이 서로 다른 operation ID를 사용하는 계약 검증

fixture는 버킷, 원장 또는 실제 풀 배분을 흉내 내지 않는다. 이는 공개 계약 소비 테스트용
도구이며 production bean이 아니다.

## 트랜잭션과 동시성

- 조회는 `@Transactional(readOnly = true)`를 사용한다.
- 확보·복구는 `@Transactional(propagation = Propagation.MANDATORY)`를 사용한다.
- 확보는 요청 순서와 무관하게 bucket PK 오름차순으로 잠근다.
- 복구도 source acquire 원장의 bucket PK 오름차순으로 잠근다.
- 확보·복구의 원장과 수량 변경은 호출자 거래와 같은 트랜잭션에서 처리한다.
- 응답 유실에 따른 상위 멱등 replay는 수량 서비스를 재호출하지 않는다.

## 테스트 전략

모든 production 동작은 실패 테스트를 먼저 확인한 뒤 최소 구현으로 통과시킨다.

1. 공개 인터페이스, DTO 검증, MANDATORY 전파와 fixture 소비 계약 단위 테스트
2. 가용량 조회에서 현재 정책, 메뉴 ID 안정 정렬, `ONSITE` 제외, 조건부 `SHARED`,
   `SOLD_OUT`, 버킷 부재를 검증하는 단위·Repository 테스트
3. Testcontainers MySQL에서 다중 메뉴 PK 잠금, 마지막 수량 경합, 부분 차감 롤백,
   호출자 롤백, 원래 풀 복구와 중복 복구를 검증하는 통합 테스트
4. production source가 Store·Pickup Entity/Repository를 참조하지 않는 구조 검사
5. 메뉴 홀드 집중 테스트, 전체 backend clean build와 `git diff --check`

새 Testcontainers 통합 테스트 클래스에는 `@Tag("integration")`과 PR #138이 도입한
`@Tag("integration-shard-a")` 또는 `@Tag("integration-shard-b")` 중 정확히 하나를
선언한다. 빠른 단위·contract 테스트에는 integration shard 태그를 붙이지 않는다.

## 커밋 경계

작업은 다음과 같이 독립 검토 가능한 한글 커밋으로 나눈다.

1. 설계 문서
2. 공개 DTO·Service 계약과 픽업 소비자 fixture
3. 온라인 가용량 조회 구현과 테스트
4. 조건부 확보·원래 풀 복구 공개 어댑터와 Testcontainers 회귀
5. Javadoc·구조 검사·최종 정리

각 커밋 전 허용 파일만 stage됐는지 확인하며 `.idea/`와 관련 없는 사용자 파일은 stage하지
않는다.
