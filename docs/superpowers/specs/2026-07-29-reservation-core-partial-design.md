# 예약 핵심 모델 부분 구현 설계

- 작성일: 2026-07-29
- 소유 Issue: [#47](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/47)
- 구현 담당자·도메인 소유자: `@usersy628` (SOYEON KIM)
- 적용 단계: 1차 MVP
- 문서 성격: 구현 설계 기록

이 문서는 활성 제품 정본을 새로 정의하지 않는다. 예약 기능 정본과 공통 계약을 #47의 현재 저장소 상태에 적용하며, 선행 계약이 없는 범위를 구현된 것처럼 만들지 않는다.

## 1. 정본과 우선순위

구현은 다음 활성 정본을 따른다.

1. `docs/specs/reservation/spec.md`
2. `docs/specs/reservation/openapi.yaml`
3. `docs/specs/mvp1-common/spec.md`의 C-008
4. `docs/specs/mvp1-common/openapi.yaml`의 `PublicId`
5. `docs/specs/mvp1-common/domain-model.md`의 S-001·S-002, E-003·E-006·E-007
6. `docs/specs/mvp1-common/ownership.md`의 O-009
7. `docs/service-policies/04-reservation.md`의 RES-007
8. `backend/ai/implementation-guardrails.md`

서로 다른 표현은 더 구체적인 공통 계약으로 해석한다.

- 예약 ID는 별도 공개 UUID나 문자열 컬럼이 아니다. 내부 `BIGINT`·Java `Long` 한 개를 공개 API에서 숫자 문자열로 표현한다.
- 연락처 스냅샷은 연락처 원문 복제를 뜻하지 않는다. 계정 식별자, 인증 도메인이 제공할 알림 대상 참조와 거래 당시 연락 가능 상태를 연결하는 구조다.
- 교차 도메인 FK는 참조 대상 소유자의 migration과 공동 검토 없이 생성하지 않는다.

## 2. 선택한 접근

### 선택안: 안전한 부분 구현

현재 `dev`에는 `consumer_accounts`, `stores` migration과 공용 Testcontainers MySQL 실행 surface가 없다. #47의 허용 경로에는 공용 테스트 의존성을 활성화할 `backend/build.gradle.kts`도 포함되지 않는다.

따라서 이번 증분은 독립적으로 검증할 수 있는 Java 핵심 모델, Repository 경계, 예약 오류 카탈로그와 단위 테스트만 구현한다. 향후 스키마의 DDL 계약은 이 문서에서 확정하되 실행 가능한 Flyway migration 파일은 선행 migration 병합 뒤 작성한다.

이 증분만으로 #47을 완료 처리하거나 MySQL 스키마 인수 조건을 충족했다고 주장하지 않는다.

### 검토한 대안

1. 모든 선행 계약이 준비될 때까지 아무것도 구현하지 않는 안은 가장 보수적이지만, 상태 기계와 독립 불변식까지 불필요하게 지연한다.
2. 임시 계정·매장 테이블 또는 FK 없는 완성 스키마를 먼저 만드는 안은 migration 순서와 교차 도메인 계약을 추측하게 하므로 채택하지 않는다.
3. 임의의 높은 Flyway 버전이나 Issue 번호를 migration 버전으로 사용하는 안은 병렬 migration의 적용 순서를 깨뜨릴 수 있어 채택하지 않는다.

## 3. 구현 범위

### 포함

- `com.miriyum.domain.reservation.entity`
  - `ReservationStatus`
  - `PartyComposition`
  - `Reservation`
  - `ReservationCapacityBucket`
  - `ReservationCapacityAllocation`
- `com.miriyum.domain.reservation.repository`
  - 각 Entity의 기본 Spring Data JPA Repository
- `com.miriyum.domain.reservation.exception`
  - `ReservationErrorCode`
- 위 모델과 오류의 단위 테스트

### 제외

- Controller, 요청·응답 DTO와 공개 HTTP API
- `ReservationService`와 생성·조회·취소·방문 완료 유스케이스
- 수용량 게시, 가용성 계산, 점유·복구와 잠금 SQL
- Auth·Store·MenuHold Entity, Repository, migration 또는 임시 구현
- 연락처 원문, 임의의 알림 대상 참조 형식과 연락 가능 상태 값
- 실행 가능한 Flyway migration과 Testcontainers MySQL 테스트
- 픽업, 결제, 체크인, 노쇼, 예약 변경과 뒤 단계 상태

## 4. Java 모델

### 4.1 `ReservationStatus`

영속 상태는 다음 세 값만 가진다.

- `CONFIRMED`
- `CANCELLED`
- `FULFILLED`

`REQUESTED`와 `CAPACITY_HELD`는 생성 트랜잭션 내부 처리 단계이므로 enum에 넣지 않는다. `CANCELLED`와 `FULFILLED`는 종결 상태다.

### 4.2 `PartyComposition`

`PartyComposition`은 JPA embeddable 값 객체로 성인·아동·영유아 인원수를 보존한다.

- 각 인원수는 0 이상 100 이하다.
- 세 값의 합은 1 이상이어야 한다.
- 전체 인원수는 저장 열로 중복 보존하지 않고 세 값의 합으로 계산한다.
- 매장별 최소·최대 일행 인원 판정은 후속 `ReservationService` 유스케이스가 소유한다.

### 4.3 `Reservation`

`Reservation`은 다음 상태와 거래 스냅샷을 가진다.

| 구분 | 필드 |
|---|---|
| 식별 | `Long id` |
| 소유 관계 | `Long consumerAccountId`, `Long storeId` |
| 매장 스냅샷 | `String storeNameSnapshot` |
| 방문 스냅샷 | `LocalDate serviceDate`, `LocalTime startTime`, `LocalTime endTime` |
| 인원 스냅샷 | embedded `PartyComposition` |
| 정책 스냅샷 | `long capacityPolicyVersion`, `long reservationPolicyVersion` |
| 상태 | `ReservationStatus status` |
| 시각 | `Instant createdAt`, nullable `cancelledAt`, nullable `fulfilledAt` |

별도 외부 ID 컬럼은 두지 않는다. `consumerAccountId`와 `storeId`는 cross-domain JPA 연관관계가 아니라 양수 scalar ID다.

의미 있는 정적 팩터리는 승인된 입력을 받아 `CONFIRMED` 예약을 만든다. 구조적 객체 불변식 위반은 `IllegalArgumentException`으로 거부한다. 시간대가 실제 매장 예약 슬롯과 일치하는지와 종료 시각 계산은 Store 공개 계약을 사용하는 후속 생성 유스케이스가 검증하므로 이번 모델이 임의의 시간대 정책을 만들지 않는다.

상태 전이는 다음 명시적 메서드만 제공한다.

- `cancel(Instant cancelledAt)`
- `fulfill(Instant fulfilledAt)`

두 메서드는 `CONFIRMED`에서만 성공한다. 이미 `CANCELLED` 또는 `FULFILLED`이면 `ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION)`을 던지고 상태와 종결 시각을 변경하지 않는다. 범용 `changeStatus`와 public setter는 제공하지 않는다.

연락처 원문, 알림 대상 참조와 연락 가능 상태 필드는 이번 증분에 추가하지 않는다. 인증 도메인이 참조 형식·상태 의미·최대 길이를 공개하고 `dev`에 병합한 뒤 예약 스냅샷에 추가한다.

### 4.4 `ReservationCapacityBucket`

버킷은 다음 정보를 보존한다.

| 구분 | 필드 |
|---|---|
| 식별·범위 | `Long id`, `Long storeId`, `LocalDate serviceDate`, `LocalTime startTime`, `LocalTime endTime` |
| 한도 | `int maxPeople`, `int maxTeams`, `int minPartySize`, `int maxPartySize`, `boolean infantsAllowed` |
| 현재 점유 | `int occupiedPeople`, `int occupiedTeams` |
| 정책 | `long policyVersion` |

구조적 불변식은 다음과 같다.

- ID와 정책 버전은 양수다.
- 최대·현재 인원과 팀 수는 음수가 아니다.
- 최소 일행 인원은 1 이상이고 최대 일행 인원 이하이다.
- 최대 일행 인원은 최대 인원보다 클 수 없다.
- 기존 점유보다 작은 새 정책도 이력을 보존할 수 있으므로 `occupiedPeople <= maxPeople`, `occupiedTeams <= maxTeams`를 객체 불변식으로 강제하지 않는다.

시간 구간의 운영 의미와 Store 예약 시간대 일치 여부는 #48이 소유한다. 이번 증분에는 점유·복구, 가용성 계산, locking 또는 조건부 갱신 메서드를 추가하지 않는다.

### 4.5 `ReservationCapacityAllocation`

배정은 다음 스냅샷을 가진다.

- `Long id`
- 양수 `Long reservationId`
- 양수 `Long capacityBucketId`
- 1명 이상의 `int occupiedPeople`
- 항상 1인 `int occupiedTeams`
- 양수 `long capacityPolicyVersion`

배정은 생성 뒤 점유 수치와 정책 버전을 변경하지 않는다. 배정 상태 enum, 해제 플래그와 복구 메서드는 후속 동시성·취소 설계에서 승인되기 전에는 추가하지 않는다.

## 5. 영속성 경계

각 Entity는 다음 규칙을 적용한다.

- JPA 기본 생성자는 `protected`
- 의미 있는 static factory와 private constructor
- `@Setter`, `@Data`, blanket `@Builder` 미사용
- `ReservationStatus`는 `EnumType.STRING`
- cross-domain 관계는 scalar ID
- public 상태 변경은 명시적 전이 메서드만 제공
- 의미 있는 Entity, enum, 생성과 상태 전이에 계약 중심 Javadoc 작성

Repository는 `JpaRepository<Entity, Long>` 기본 경계만 제공한다. 조회 조건, 소유권 은닉, 잠금, 정렬과 조건부 갱신 메서드는 해당 후속 Issue에서 추가한다.

## 6. 향후 DDL 계약

선행 migration과 Testcontainers surface가 준비된 뒤 다음 세 테이블을 Flyway migration으로 만든다.

### `reservations`

- 독립 `BIGINT` 대리 PK
- indexed scalar `consumer_account_id`, `store_id`
- 매장명, 방문 날짜·시각, 성인·아동·영유아 인원과 정책 버전 스냅샷
- 문자열 심볼 예약 상태
- 생성·종결 시각
- Auth 계약 뒤 알림 대상 참조와 거래 당시 연락 가능 상태
- `consumer_accounts`, `stores` FK는 각 소유 migration 병합과 공동 검토 뒤 추가

### `reservation_capacity_buckets`

- 독립 `BIGINT` 대리 PK
- indexed scalar `store_id`
- 날짜·시작·종료 시각, 최대·현재 인원과 팀 수, 일행 정책과 정책 버전
- 업무 유일 키: 매장 + 서비스 날짜 + 시작 시각 + 종료 시각 + 수용량 정책 버전
- `stores` FK는 Store migration 병합과 공동 검토 뒤 추가

### `reservation_capacity_allocations`

- 독립 `BIGINT` 대리 PK
- `reservation_id`, `reservation_capacity_bucket_id`
- 점유 인원, 점유 팀 1건과 적용 정책 버전
- 업무 유일 키: 예약 + 수용량 버킷
- 같은 예약 도메인의 예약·버킷 FK를 사용하고 `ON DELETE CASCADE`를 적용하지 않음

정확한 Flyway 버전은 실행 파일을 만들기 직전에 최신 `dev`, 병렬 migration과 이미 병합된 버전을 다시 확인해 다음 유효 순서로 정한다. API `/api/v1`과 Flyway migration 버전은 서로 관계가 없다.

## 7. 오류 처리

`ReservationErrorCode` 하나가 승인된 `RESERVATION_001`부터 `RESERVATION_009`까지 소유한다.

| 코드 | HTTP | 의미 |
|---|---:|---|
| `RESERVATION_001` | 404 | 예약을 찾을 수 없음 |
| `RESERVATION_002` | 409 | 영업·예약 접수 시간 밖 |
| `RESERVATION_003` | 409 | 예약 수용량 부족 |
| `RESERVATION_004` | 409 | 겹치는 활성 예약 |
| `RESERVATION_005` | 409 | 현재 상태에서 전이 불가 |
| `RESERVATION_006` | 409 | 현재 시각·정책에서 취소 불가 |
| `RESERVATION_007` | 409 | 정책·수용량 버전 변경 |
| `RESERVATION_008` | 409 | 수용량 설정 충돌 |
| `RESERVATION_009` | 409 | 일행 인원 정책 위반 |

새 오류 코드를 만들거나 Store·MenuHold 오류를 예약 오류로 바꾸지 않는다.

## 8. 테스트와 증거

### 이번 증분에서 실행할 검증

- `ReservationStatus`가 승인된 세 값만 가지는지 검증
- `PartyComposition`의 범위와 합계 불변식 검증
- 예약 생성이 `CONFIRMED`이고 스냅샷을 보존하는지 검증
- `CONFIRMED → CANCELLED`, `CONFIRMED → FULFILLED` 전이 검증
- 두 종결 상태의 재활성화와 상호 전환 거부 검증
- 버킷 한도·점유·정책 버전 불변식 검증
- 배정의 점유 인원과 팀 수 1 불변식 검증
- `ReservationErrorCode`의 HTTP 상태·외부 코드·중복 부재 검증
- `.\gradlew.bat test`
- `.\gradlew.bat build`
- `git diff --check`
- #47 허용 경로와 실제 diff 대조

### 이번 증분에서 완료로 주장하지 않을 검증

- Flyway clean-start
- DB FK·유일·CHECK 제약
- 실제 enum 문자열 저장
- transaction rollback
- 잠금과 동시 조건부 갱신
- Testcontainers MySQL 통합 테스트

H2, mock, SQL 문자열 검사와 단위 테스트 성공을 MySQL 동작 증거로 사용하지 않는다.

## 9. 차단 조건과 후속 순서

다음 조건이 충족되기 전에는 #47의 DB 인수 조건을 완료 처리하지 않는다.

1. 인증 소유 Issue/PR이 `consumer_accounts` migration과 예약이 사용할 알림 대상 참조·연락 가능 상태 계약을 제공하고 `dev`에 병합된다.
2. Store 선행 migration과 #33의 `stores` 계약이 `dev`에 병합된다.
3. #31 또는 별도 공통 Issue/PR이 Testcontainers MySQL 의존성과 재현 가능한 실행 surface를 활성화하고 `dev`에 병합된다.
4. 최신 `dev` 기준 Flyway 순서를 확인해 예약 migration 버전을 정한다.
5. 교차 도메인 FK를 참조·피참조 도메인 소유자가 함께 검토한다.

선행 계약 전에는 가짜 테이블, 임시 FK 대상, 임의 연락처 참조, production no-op과 H2 증거로 차단 조건을 우회하지 않는다.

## 10. 위험과 되돌림

- 부분 구현이 #47 전체 완료로 오해될 수 있으므로 PR과 Issue에서 미실행 DB 검증과 차단 조건을 명시한다.
- 후속 Auth·Store 계약과 필드 형상이 달라질 수 있으므로 현재 Entity에 추측성 연락처 필드나 cross-domain JPA 연관관계를 넣지 않는다.
- Java 핵심 모델은 독립 커밋으로 되돌릴 수 있다.
- Flyway migration은 이번 증분에서 만들지 않으므로 적용된 스키마 롤백 위험이 없다.
