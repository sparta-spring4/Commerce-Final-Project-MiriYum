# 예약 핵심 모델 부분 구현 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** #47의 독립 검증 가능한 범위인 예약 오류 계약, 상태·인원 값 객체, 예약 aggregate, 수용량 버킷·배정과 기본 JPA Repository를 구현한다.

**Architecture:** `com.miriyum.domain.reservation` 안에서 Entity와 값 객체가 구조적 불변식과 상태 전이를 소유하고, Spring Data Repository는 기본 영속성 경계만 제공한다. Auth·Store 계약, Controller·Service, custom query와 Flyway migration은 추가하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Gradle Wrapper 9.6.1, JUnit 5, AssertJ

## Global Constraints

- 작업 브랜치는 최신 `dev`에서 만든 `feature/47-reservation-core`다.
- 수정 경로는 #47의 `backend/src/main/java/com/miriyum/domain/reservation/**`, `backend/src/test/java/com/miriyum/domain/reservation/**`와 이 계획 문서로 제한한다.
- `backend/build.gradle.kts`, Controller, Service, DTO, Security와 Flyway migration을 변경하지 않는다.
- Java package는 `com.miriyum.domain.reservation` 아래만 사용한다.
- Entity에 `@Setter`, `@Data`, `@RequiredArgsConstructor`, blanket `@Builder`를 사용하지 않는다.
- Entity와 embeddable의 JPA 기본 생성자는 `protected`, 실제 생성자는 `private`, 생성 진입점은 의미 있는 static factory로 둔다.
- cross-domain 관계는 `Long` scalar ID로만 표현하고 Auth·Store Entity나 Repository를 import하지 않는다.
- 예약 상태는 `CONFIRMED`, `CANCELLED`, `FULFILLED`만 사용하고 `EnumType.STRING`으로 매핑한다.
- 공개 ID용 별도 UUID·문자열 컬럼을 추가하지 않는다.
- 연락처 원문, 알림 대상 참조와 연락 가능 상태는 Auth 공개 계약 전까지 추가하지 않는다.
- 시간대의 Store 정책 일치, 가용성, 점유·복구, locking과 조건부 SQL을 구현하지 않는다.
- 각 Task는 failing test → 최소 구현 → 집중 테스트 → 커밋 순서를 지킨다.
- 테스트 메서드는 camelCase, `@DisplayName`은 한국어를 사용한다.

---

## File Map

| 파일 | 책임 |
|---|---|
| `backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java` | `RESERVATION_001~009` 외부 오류 계약 |
| `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationStatus.java` | 승인된 세 예약 상태 |
| `backend/src/main/java/com/miriyum/domain/reservation/entity/PartyComposition.java` | 성인·아동·영유아 인원 스냅샷과 합계 불변식 |
| `backend/src/main/java/com/miriyum/domain/reservation/entity/Reservation.java` | 예약 거래 스냅샷과 취소·방문 완료 상태 전이 |
| `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java` | 구간별 수용량 정책과 현재 점유 스냅샷 |
| `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocation.java` | 예약별 버킷 점유 이력 |
| `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java` | 예약 기본 JPA 영속성 경계 |
| `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java` | 수용량 버킷 기본 JPA 영속성 경계 |
| `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java` | 수용량 배정 기본 JPA 영속성 경계 |
| `backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java` | 오류 HTTP·코드·메시지·중복 검증 |
| `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationStatusTest.java` | 상태 목록 검증 |
| `backend/src/test/java/com/miriyum/domain/reservation/entity/PartyCompositionTest.java` | 인원 범위·합계 검증 |
| `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationTest.java` | 예약 생성·스냅샷·종결 전이 검증 |
| `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java` | 버킷 한도·점유·정책 버전 검증 |
| `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocationTest.java` | 점유 인원·팀 1건·정책 버전 검증 |

## Task 1: 예약 오류 계약

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java`

**Interfaces:**
- Consumes: `com.miriyum.global.exception.ErrorCode`
- Produces: `ReservationErrorCode.INVALID_STATE_TRANSITION`과 `RESERVATION_001~009` 전체 카탈로그

- [ ] **Step 1: Write the failing error catalog test**

```java
package com.miriyum.domain.reservation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class ReservationErrorCodeTest {

    @ParameterizedTest
    @MethodSource("errorCodes")
    @DisplayName("예약 오류 코드는 승인된 HTTP 상태와 외부 코드를 제공한다")
    void providesApprovedErrorContract(
            ReservationErrorCode errorCode,
            HttpStatus httpStatus,
            String code,
            String message
    ) {
        assertThat(errorCode.getHttpStatus()).isEqualTo(httpStatus);
        assertThat(errorCode.getCode()).isEqualTo(code);
        assertThat(errorCode.getMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("예약 오류 외부 코드는 중복되지 않는다")
    void doesNotContainDuplicateExternalCodes() {
        assertThat(ReservationErrorCode.values()).hasSize(9);
        assertThat(ReservationErrorCode.values())
                .extracting(ReservationErrorCode::getCode)
                .doesNotHaveDuplicates();
    }

    private static Stream<Arguments> errorCodes() {
        return Stream.of(
                Arguments.of(ReservationErrorCode.RESERVATION_NOT_FOUND,
                        HttpStatus.NOT_FOUND, "RESERVATION_001", "예약을 찾을 수 없습니다."),
                Arguments.of(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW,
                        HttpStatus.CONFLICT, "RESERVATION_002", "요청 시간이 영업·예약 접수 구간에 없습니다."),
                Arguments.of(ReservationErrorCode.INSUFFICIENT_CAPACITY,
                        HttpStatus.CONFLICT, "RESERVATION_003", "요청한 시간의 예약 수용량이 부족합니다."),
                Arguments.of(ReservationErrorCode.DUPLICATE_RESERVATION,
                        HttpStatus.CONFLICT, "RESERVATION_004", "같은 사용자·매장에 겹치는 활성 예약이 있습니다."),
                Arguments.of(ReservationErrorCode.INVALID_STATE_TRANSITION,
                        HttpStatus.CONFLICT, "RESERVATION_005", "현재 예약 상태에서 처리할 수 없습니다."),
                Arguments.of(ReservationErrorCode.CANCELLATION_NOT_ALLOWED,
                        HttpStatus.CONFLICT, "RESERVATION_006", "현재 시각·정책에서는 예약을 취소할 수 없습니다."),
                Arguments.of(ReservationErrorCode.CAPACITY_POLICY_CHANGED,
                        HttpStatus.CONFLICT, "RESERVATION_007", "조회 후 정책·수용량 버전이 변경되었습니다."),
                Arguments.of(ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT,
                        HttpStatus.CONFLICT, "RESERVATION_008", "현재 예약 점유와 수용량 설정이 충돌합니다."),
                Arguments.of(ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE,
                        HttpStatus.CONFLICT, "RESERVATION_009", "요청 인원이 매장 최소·최대 정책을 벗어났습니다.")
        );
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.exception.ReservationErrorCodeTest"
```

Expected: compilation fails because `ReservationErrorCode` does not exist.

- [ ] **Step 3: Implement the error catalog**

```java
package com.miriyum.domain.reservation.exception;

import com.miriyum.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 일반 예약 도메인이 소유하는 외부 오류 코드다.
 */
public enum ReservationErrorCode implements ErrorCode {

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_001", "예약을 찾을 수 없습니다."),
    OUTSIDE_RESERVATION_WINDOW(
            HttpStatus.CONFLICT,
            "RESERVATION_002",
            "요청 시간이 영업·예약 접수 구간에 없습니다."
    ),
    INSUFFICIENT_CAPACITY(
            HttpStatus.CONFLICT,
            "RESERVATION_003",
            "요청한 시간의 예약 수용량이 부족합니다."
    ),
    DUPLICATE_RESERVATION(
            HttpStatus.CONFLICT,
            "RESERVATION_004",
            "같은 사용자·매장에 겹치는 활성 예약이 있습니다."
    ),
    INVALID_STATE_TRANSITION(
            HttpStatus.CONFLICT,
            "RESERVATION_005",
            "현재 예약 상태에서 처리할 수 없습니다."
    ),
    CANCELLATION_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "RESERVATION_006",
            "현재 시각·정책에서는 예약을 취소할 수 없습니다."
    ),
    CAPACITY_POLICY_CHANGED(
            HttpStatus.CONFLICT,
            "RESERVATION_007",
            "조회 후 정책·수용량 버전이 변경되었습니다."
    ),
    CAPACITY_CONFIGURATION_CONFLICT(
            HttpStatus.CONFLICT,
            "RESERVATION_008",
            "현재 예약 점유와 수용량 설정이 충돌합니다."
    ),
    PARTY_SIZE_OUT_OF_RANGE(
            HttpStatus.CONFLICT,
            "RESERVATION_009",
            "요청 인원이 매장 최소·최대 정책을 벗어났습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ReservationErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.exception.ReservationErrorCodeTest"
```

Expected: `ReservationErrorCodeTest` passes.

- [ ] **Step 5: Commit Task 1**

Run from the worktree root:

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java
git commit -m "feat(reservation): 예약 오류 계약 구현"
```

## Task 2: 예약 상태와 인원 구성 값 객체

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationStatusTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/PartyCompositionTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/PartyComposition.java`

**Interfaces:**
- Consumes: Jakarta Persistence annotations
- Produces: `ReservationStatus`, `PartyComposition.of(int, int, int)`, `totalCount()`

- [ ] **Step 1: Write the failing state and party tests**

```java
package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    @Test
    @DisplayName("1차 MVP 예약 상태는 확정·취소·방문 완료만 사용한다")
    void containsOnlyApprovedMvpStatuses() {
        assertThat(ReservationStatus.values())
                .containsExactly(
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.FULFILLED
                );
    }
}
```

```java
package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PartyCompositionTest {

    @Test
    @DisplayName("성인·아동·영유아 수와 전체 인원수를 보존한다")
    void preservesPartyCompositionAndCalculatesTotal() {
        PartyComposition party = PartyComposition.of(2, 1, 1);

        assertThat(party.getAdultCount()).isEqualTo(2);
        assertThat(party.getChildCount()).isEqualTo(1);
        assertThat(party.getInfantCount()).isEqualTo(1);
        assertThat(party.totalCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("전체 인원이 0명이면 생성할 수 없다")
    void rejectsEmptyParty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(0, 0, 0));
    }

    @Test
    @DisplayName("각 인원수는 음수일 수 없다")
    void rejectsNegativeCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(-1, 1, 1));
    }

    @Test
    @DisplayName("각 인원수는 공개 요청 최대값 100을 넘을 수 없다")
    void rejectsCountAbovePublicMaximum() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PartyComposition.of(101, 0, 0));
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationStatusTest" --tests "com.miriyum.domain.reservation.entity.PartyCompositionTest"
```

Expected: compilation fails because both production types do not exist.

- [ ] **Step 3: Implement `ReservationStatus`**

```java
package com.miriyum.domain.reservation.entity;

/**
 * 1차 MVP에 영속하고 공개할 일반 예약 상태다.
 */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED,
    FULFILLED
}
```

- [ ] **Step 4: Implement `PartyComposition`**

```java
package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 예약 확정 당시의 성인·아동·영유아 인원 구성이다.
 */
@Embeddable
public class PartyComposition {

    private static final int MAX_COUNT_PER_GROUP = 100;

    @Column(name = "adult_count", nullable = false)
    private int adultCount;

    @Column(name = "child_count", nullable = false)
    private int childCount;

    @Column(name = "infant_count", nullable = false)
    private int infantCount;

    protected PartyComposition() {
    }

    private PartyComposition(int adultCount, int childCount, int infantCount) {
        this.adultCount = requireValidCount(adultCount, "adultCount");
        this.childCount = requireValidCount(childCount, "childCount");
        this.infantCount = requireValidCount(infantCount, "infantCount");
        if (totalCount() < 1) {
            throw new IllegalArgumentException("party total count must be at least 1");
        }
    }

    /**
     * 검증된 연령대별 인원수로 거래 인원 스냅샷을 만든다.
     *
     * @param adultCount 성인 인원수
     * @param childCount 아동 인원수
     * @param infantCount 영유아 인원수
     * @return 검증된 인원 구성
     * @throws IllegalArgumentException 각 값이 0~100 범위 밖이거나 합계가 1 미만인 경우
     */
    public static PartyComposition of(int adultCount, int childCount, int infantCount) {
        return new PartyComposition(adultCount, childCount, infantCount);
    }

    public int totalCount() {
        return adultCount + childCount + infantCount;
    }

    private static int requireValidCount(int count, String fieldName) {
        if (count < 0 || count > MAX_COUNT_PER_GROUP) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100");
        }
        return count;
    }

    public int getAdultCount() {
        return adultCount;
    }

    public int getChildCount() {
        return childCount;
    }

    public int getInfantCount() {
        return infantCount;
    }
}
```

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationStatusTest" --tests "com.miriyum.domain.reservation.entity.PartyCompositionTest"
```

Expected: both test classes pass.

- [ ] **Step 6: Commit Task 2**

Run from the worktree root:

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationStatus.java backend/src/main/java/com/miriyum/domain/reservation/entity/PartyComposition.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationStatusTest.java backend/src/test/java/com/miriyum/domain/reservation/entity/PartyCompositionTest.java
git commit -m "feat(reservation): 예약 상태와 인원 구성 모델 구현"
```

## Task 3: 예약 aggregate와 종결 전이

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/Reservation.java`

**Interfaces:**
- Consumes: `ReservationStatus`, `PartyComposition`, `ReservationErrorCode.INVALID_STATE_TRANSITION`, `ServiceException`
- Produces: `Reservation.confirm(...)`, `cancel(Instant)`, `fulfill(Instant)`과 거래 스냅샷 getter

- [ ] **Step 1: Write the failing aggregate tests**

```java
package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T01:00:00Z");
    private static final Instant TERMINATED_AT = Instant.parse("2026-08-01T02:00:00Z");

    @Test
    @DisplayName("승인된 거래 스냅샷으로 예약을 즉시 확정한다")
    void confirmsReservationFromApprovedSnapshot() {
        Reservation reservation = createConfirmedReservation();

        assertThat(reservation.getConsumerAccountId()).isEqualTo(11L);
        assertThat(reservation.getStoreId()).isEqualTo(22L);
        assertThat(reservation.getStoreNameSnapshot()).isEqualTo("미리엄 키친");
        assertThat(reservation.getServiceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(reservation.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(reservation.getEndTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(reservation.getParty().totalCount()).isEqualTo(3);
        assertThat(reservation.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(reservation.getReservationPolicyVersion()).isEqualTo(5L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("확정 예약을 취소 상태로 종결한다")
    void cancelsConfirmedReservation() {
        Reservation reservation = createConfirmedReservation();

        reservation.cancel(TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("확정 예약을 방문 완료 상태로 종결한다")
    void fulfillsConfirmedReservation() {
        Reservation reservation = createConfirmedReservation();

        reservation.fulfill(TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("취소된 예약은 방문 완료로 전이할 수 없다")
    void rejectsTransitionFromCancelledToFulfilled() {
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("방문 완료된 예약은 취소로 전이할 수 없다")
    void rejectsTransitionFromFulfilledToCancelled() {
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("취소된 예약은 취소를 반복할 수 없다")
    void rejectsRepeatedCancellation() {
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("방문 완료된 예약은 방문 완료를 반복할 수 없다")
    void rejectsRepeatedFulfillment() {
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("양수가 아닌 소유 관계 ID를 거부한다")
    void rejectsNonPositiveOwnerId() {
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                0L,
                22L,
                "미리엄 키친",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                5L,
                CREATED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L,
                0L,
                "미리엄 키친",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                5L,
                CREATED_AT
        ));
    }

    @Test
    @DisplayName("비어 있는 매장명 스냅샷을 거부한다")
    void rejectsBlankStoreNameSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L,
                22L,
                " ",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                5L,
                CREATED_AT
        ));
    }

    @Test
    @DisplayName("양수가 아닌 정책 버전을 거부한다")
    void rejectsNonPositivePolicyVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L,
                22L,
                "미리엄 키친",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                0L,
                5L,
                CREATED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L,
                22L,
                "미리엄 키친",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                0L,
                CREATED_AT
        ));
    }

    @Test
    @DisplayName("필수 거래 스냅샷이 없으면 생성할 수 없다")
    void rejectsMissingRequiredSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L,
                22L,
                "미리엄 키친",
                null,
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                5L,
                CREATED_AT
        ));
    }

    private static Reservation createConfirmedReservation() {
        return Reservation.confirm(
                11L,
                22L,
                "미리엄 키친",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0),
                3L,
                5L,
                CREATED_AT
        );
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationTest"
```

Expected: compilation fails because `Reservation` does not exist.

- [ ] **Step 3: Implement the aggregate**

Create `Reservation.java` with the following exact public contract and mapping:

```java
package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 일반 방문 예약의 거래 스냅샷과 승인된 종결 전이를 소유한다.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name_snapshot", nullable = false, length = 100)
    private String storeNameSnapshot;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Embedded
    private PartyComposition party;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    @Column(name = "reservation_policy_version", nullable = false)
    private long reservationPolicyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    protected Reservation() {
    }

    private Reservation(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            PartyComposition party,
            long capacityPolicyVersion,
            long reservationPolicyVersion,
            Instant createdAt
    ) {
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.storeId = requirePositive(storeId, "storeId");
        this.storeNameSnapshot = requireStoreName(storeNameSnapshot);
        this.serviceDate = requireNonNull(serviceDate, "serviceDate");
        this.startTime = requireNonNull(startTime, "startTime");
        this.endTime = requireNonNull(endTime, "endTime");
        this.party = requireNonNull(party, "party");
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
        this.reservationPolicyVersion = requirePositive(
                reservationPolicyVersion,
                "reservationPolicyVersion"
        );
        this.status = ReservationStatus.CONFIRMED;
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    /**
     * 승인된 소유 관계와 거래 스냅샷으로 즉시 확정 예약을 만든다.
     *
     * @param consumerAccountId 예약 대표자 계정 ID
     * @param storeId 대상 매장 ID
     * @param storeNameSnapshot 예약 당시 매장 표시명
     * @param serviceDate 매장 업무 날짜
     * @param startTime 방문 시작 시각
     * @param endTime 점유 종료 시각
     * @param party 예약 당시 인원 구성
     * @param capacityPolicyVersion 적용 수용량 정책 버전
     * @param reservationPolicyVersion 적용 예약 정책 버전
     * @param createdAt 예약 확정 시각
     * @return 즉시 확정된 예약
     * @throws IllegalArgumentException 필수 값이 없거나 ID·정책 버전·매장명이 유효하지 않은 경우
     */
    public static Reservation confirm(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            PartyComposition party,
            long capacityPolicyVersion,
            long reservationPolicyVersion,
            Instant createdAt
    ) {
        return new Reservation(
                consumerAccountId,
                storeId,
                storeNameSnapshot,
                serviceDate,
                startTime,
                endTime,
                party,
                capacityPolicyVersion,
                reservationPolicyVersion,
                createdAt
        );
    }

    /**
     * 확정 예약을 취소 상태로 종결한다.
     *
     * @param cancelledAt 취소 확정 시각
     * @throws IllegalArgumentException 취소 확정 시각이 없는 경우
     * @throws ServiceException 현재 상태가 확정이 아닌 경우
     */
    public void cancel(Instant cancelledAt) {
        requireConfirmed();
        this.cancelledAt = requireNonNull(cancelledAt, "cancelledAt");
        this.status = ReservationStatus.CANCELLED;
    }

    /**
     * 확정 예약을 방문 완료 상태로 종결한다.
     *
     * @param fulfilledAt 방문 완료 확정 시각
     * @throws IllegalArgumentException 방문 완료 확정 시각이 없는 경우
     * @throws ServiceException 현재 상태가 확정이 아닌 경우
     */
    public void fulfill(Instant fulfilledAt) {
        requireConfirmed();
        this.fulfilledAt = requireNonNull(fulfilledAt, "fulfilledAt");
        this.status = ReservationStatus.FULFILLED;
    }

    private void requireConfirmed() {
        if (status != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    private static String requireStoreName(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(
                    "storeNameSnapshot must contain between 1 and 100 characters"
            );
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getConsumerAccountId() {
        return consumerAccountId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public String getStoreNameSnapshot() {
        return storeNameSnapshot;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public PartyComposition getParty() {
        return party;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }

    public long getReservationPolicyVersion() {
        return reservationPolicyVersion;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationTest"
```

Expected: all `ReservationTest` cases pass.

- [ ] **Step 5: Run the reservation tests completed so far**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.*"
```

Expected: Tasks 1~3 reservation tests pass.

- [ ] **Step 6: Commit Task 3**

Run from the worktree root:

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/Reservation.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationTest.java
git commit -m "feat(reservation): 예약 핵심 상태 전이 구현"
```

## Task 4: 예약 수용량 버킷

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java`

**Interfaces:**
- Consumes: Jakarta Persistence annotations
- Produces: `ReservationCapacityBucket.create(...)`와 수용량·점유·정책 getter

- [ ] **Step 1: Write the failing bucket tests**

```java
package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityBucketTest {

    @Test
    @DisplayName("수용량 한도와 현재 점유 및 정책 버전을 보존한다")
    void preservesCapacityPolicyAndOccupancy() {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                20,
                5,
                8,
                2,
                1,
                6,
                true,
                3L
        );

        assertThat(bucket.getStoreId()).isEqualTo(22L);
        assertThat(bucket.getMaxPeople()).isEqualTo(20);
        assertThat(bucket.getMaxTeams()).isEqualTo(5);
        assertThat(bucket.getOccupiedPeople()).isEqualTo(8);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(2);
        assertThat(bucket.getMinPartySize()).isEqualTo(1);
        assertThat(bucket.getMaxPartySize()).isEqualTo(6);
        assertThat(bucket.isInfantsAllowed()).isTrue();
        assertThat(bucket.getPolicyVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("기존 점유가 새 최대값보다 커도 이력 보존을 위해 허용한다")
    void allowsOccupancyAboveNewLimits() {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                4,
                1,
                8,
                2,
                1,
                4,
                true,
                4L
        );

        assertThat(bucket.getOccupiedPeople()).isGreaterThan(bucket.getMaxPeople());
        assertThat(bucket.getOccupiedTeams()).isGreaterThan(bucket.getMaxTeams());
    }

    @Test
    @DisplayName("음수 수용량이나 점유량을 거부한다")
    void rejectsNegativeCapacityOrOccupancy() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        -1,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("최대 일행 인원이 최대 수용 인원을 넘으면 거부한다")
    void rejectsPartyMaximumAbovePeopleMaximum() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        4,
                        5,
                        0,
                        0,
                        1,
                        5,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("최대 일행 인원이 최소 일행 인원보다 작으면 거부한다")
    void rejectsPartyMaximumBelowPartyMinimum() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        5,
                        4,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("양수가 아닌 매장 ID나 정책 버전을 거부한다")
    void rejectsNonPositiveIdentityOrPolicyVersion() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        0L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        0L
                )
        );
    }

    @Test
    @DisplayName("필수 날짜 스냅샷이 없으면 생성할 수 없다")
    void rejectsMissingServiceDate() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        null,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityBucketTest"
```

Expected: compilation fails because `ReservationCapacityBucket` does not exist.

- [ ] **Step 3: Implement the bucket entity**

```java
package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 매장·업무 날짜·시간 구간별 예약 인원과 팀 수 정책 스냅샷이다.
 */
@Entity
@Table(name = "reservation_capacity_buckets")
public class ReservationCapacityBucket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_capacity_bucket_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "max_people", nullable = false)
    private int maxPeople;

    @Column(name = "max_teams", nullable = false)
    private int maxTeams;

    @Column(name = "occupied_people", nullable = false)
    private int occupiedPeople;

    @Column(name = "occupied_teams", nullable = false)
    private int occupiedTeams;

    @Column(name = "min_party_size", nullable = false)
    private int minPartySize;

    @Column(name = "max_party_size", nullable = false)
    private int maxPartySize;

    @Column(name = "infants_allowed", nullable = false)
    private boolean infantsAllowed;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    protected ReservationCapacityBucket() {
    }

    private ReservationCapacityBucket(
            Long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int maxTeams,
            int occupiedPeople,
            int occupiedTeams,
            int minPartySize,
            int maxPartySize,
            boolean infantsAllowed,
            long policyVersion
    ) {
        this.storeId = requirePositive(storeId, "storeId");
        this.serviceDate = requireNonNull(serviceDate, "serviceDate");
        this.startTime = requireNonNull(startTime, "startTime");
        this.endTime = requireNonNull(endTime, "endTime");
        this.maxPeople = requireNonNegative(maxPeople, "maxPeople");
        this.maxTeams = requireNonNegative(maxTeams, "maxTeams");
        this.occupiedPeople = requireNonNegative(occupiedPeople, "occupiedPeople");
        this.occupiedTeams = requireNonNegative(occupiedTeams, "occupiedTeams");
        this.minPartySize = requirePositive(minPartySize, "minPartySize");
        this.maxPartySize = requirePositive(maxPartySize, "maxPartySize");
        if (maxPartySize < minPartySize) {
            throw new IllegalArgumentException(
                    "maxPartySize must be greater than or equal to minPartySize"
            );
        }
        if (maxPartySize > maxPeople) {
            throw new IllegalArgumentException(
                    "maxPartySize must be less than or equal to maxPeople"
            );
        }
        this.infantsAllowed = infantsAllowed;
        this.policyVersion = requirePositive(policyVersion, "policyVersion");
    }

    /**
     * 게시가 승인된 구간별 수용량 정책과 현재 점유 스냅샷을 만든다.
     *
     * @param storeId 대상 매장 ID
     * @param serviceDate 매장 업무 날짜
     * @param startTime 구간 시작 시각
     * @param endTime 구간 종료 시각
     * @param maxPeople 최대 예약 인원
     * @param maxTeams 최대 예약 팀 수
     * @param occupiedPeople 현재 점유 인원
     * @param occupiedTeams 현재 점유 팀 수
     * @param minPartySize 최소 일행 인원
     * @param maxPartySize 최대 일행 인원
     * @param infantsAllowed 영유아 동반 허용 여부
     * @param policyVersion 적용 수용량 정책 버전
     * @return 검증된 수용량 버킷 스냅샷
     * @throws IllegalArgumentException 필수 값이나 구조적 수용량 불변식이 유효하지 않은 경우
     */
    public static ReservationCapacityBucket create(
            Long storeId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int maxTeams,
            int occupiedPeople,
            int occupiedTeams,
            int minPartySize,
            int maxPartySize,
            boolean infantsAllowed,
            long policyVersion
    ) {
        return new ReservationCapacityBucket(
                storeId,
                serviceDate,
                startTime,
                endTime,
                maxPeople,
                maxTeams,
                occupiedPeople,
                occupiedTeams,
                minPartySize,
                maxPartySize,
                infantsAllowed,
                policyVersion
        );
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getMaxPeople() {
        return maxPeople;
    }

    public int getMaxTeams() {
        return maxTeams;
    }

    public int getOccupiedPeople() {
        return occupiedPeople;
    }

    public int getOccupiedTeams() {
        return occupiedTeams;
    }

    public int getMinPartySize() {
        return minPartySize;
    }

    public int getMaxPartySize() {
        return maxPartySize;
    }

    public boolean isInfantsAllowed() {
        return infantsAllowed;
    }

    public long getPolicyVersion() {
        return policyVersion;
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityBucketTest"
```

Expected: all bucket tests pass.

- [ ] **Step 5: Commit Task 4**

Run from the worktree root:

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java
git commit -m "feat(reservation): 예약 수용량 버킷 모델 구현"
```

## Task 5: 수용량 배정과 Repository 경계

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocationTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocation.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java`

**Interfaces:**
- Consumes: `Reservation`, `ReservationCapacityBucket`, Spring Data `JpaRepository`
- Produces: `ReservationCapacityAllocation.allocate(...)`와 세 기본 Repository

- [ ] **Step 1: Write the failing allocation tests**

```java
package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityAllocationTest {

    @Test
    @DisplayName("예약의 실제 점유 인원과 팀 1건 및 정책 버전을 보존한다")
    void preservesAllocationSnapshot() {
        ReservationCapacityAllocation allocation =
                ReservationCapacityAllocation.allocate(101L, 202L, 4, 3L);

        assertThat(allocation.getReservationId()).isEqualTo(101L);
        assertThat(allocation.getCapacityBucketId()).isEqualTo(202L);
        assertThat(allocation.getOccupiedPeople()).isEqualTo(4);
        assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
        assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("양수가 아닌 예약·버킷 ID를 거부한다")
    void rejectsNonPositiveReferences() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(0L, 202L, 4, 3L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 0L, 4, 3L));
    }

    @Test
    @DisplayName("점유 인원은 1명 이상이어야 한다")
    void rejectsEmptyOccupiedPeople() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 202L, 0, 3L));
    }

    @Test
    @DisplayName("수용량 정책 버전은 양수여야 한다")
    void rejectsNonPositivePolicyVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 202L, 4, 0L));
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityAllocationTest"
```

Expected: compilation fails because `ReservationCapacityAllocation` does not exist.

- [ ] **Step 3: Implement the allocation entity**

```java
package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 예약이 특정 수용량 버킷에서 실제 점유한 인원과 팀 수의 이력이다.
 */
@Entity
@Table(name = "reservation_capacity_allocations")
public class ReservationCapacityAllocation {

    private static final int OCCUPIED_TEAMS_PER_RESERVATION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_capacity_allocation_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "reservation_capacity_bucket_id", nullable = false)
    private Long capacityBucketId;

    @Column(name = "occupied_people", nullable = false)
    private int occupiedPeople;

    @Column(name = "occupied_teams", nullable = false)
    private int occupiedTeams;

    @Column(name = "capacity_policy_version", nullable = false)
    private long capacityPolicyVersion;

    protected ReservationCapacityAllocation() {
    }

    private ReservationCapacityAllocation(
            Long reservationId,
            Long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.capacityBucketId = requirePositive(capacityBucketId, "capacityBucketId");
        this.occupiedPeople = requirePositive(occupiedPeople, "occupiedPeople");
        this.occupiedTeams = OCCUPIED_TEAMS_PER_RESERVATION;
        this.capacityPolicyVersion = requirePositive(
                capacityPolicyVersion,
                "capacityPolicyVersion"
        );
    }

    /**
     * 예약 한 건이 버킷 하나에서 점유한 인원과 팀 1건의 이력을 만든다.
     *
     * @param reservationId 예약 ID
     * @param capacityBucketId 수용량 버킷 ID
     * @param occupiedPeople 실제 점유 인원
     * @param capacityPolicyVersion 적용 수용량 정책 버전
     * @return 변경할 수 없는 수용량 배정 스냅샷
     * @throws IllegalArgumentException ID·점유 인원·정책 버전이 양수가 아닌 경우
     */
    public static ReservationCapacityAllocation allocate(
            Long reservationId,
            Long capacityBucketId,
            int occupiedPeople,
            long capacityPolicyVersion
    ) {
        return new ReservationCapacityAllocation(
                reservationId,
                capacityBucketId,
                occupiedPeople,
                capacityPolicyVersion
        );
    }

    private static Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getCapacityBucketId() {
        return capacityBucketId;
    }

    public int getOccupiedPeople() {
        return occupiedPeople;
    }

    public int getOccupiedTeams() {
        return occupiedTeams;
    }

    public long getCapacityPolicyVersion() {
        return capacityPolicyVersion;
    }
}
```

- [ ] **Step 4: Implement the three minimal Repository interfaces**

```java
package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 일반 예약 aggregate의 기본 영속성 경계다.
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
```

```java
package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 수용량 버킷의 기본 영속성 경계다.
 */
public interface ReservationCapacityBucketRepository
        extends JpaRepository<ReservationCapacityBucket, Long> {
}
```

```java
package com.miriyum.domain.reservation.repository;

import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 예약 수용량 배정 이력의 기본 영속성 경계다.
 */
public interface ReservationCapacityAllocationRepository
        extends JpaRepository<ReservationCapacityAllocation, Long> {
}
```

- [ ] **Step 5: Run the focused allocation test and compile Repository types**

Run from `backend/`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityAllocationTest"
```

Expected: allocation tests pass and all Repository interfaces compile.

- [ ] **Step 6: Run all reservation tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.reservation.*"
```

Expected: all reservation tests pass.

- [ ] **Step 7: Run the complete backend test and build gates**

Run:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Expected: both commands exit with code 0. The result proves Java compilation and unit tests only; it does not prove MySQL schema behavior.

- [ ] **Step 8: Verify scope and formatting**

Run from the worktree root:

```powershell
git diff --check 2baaa30 -- backend
git status --short
git diff --name-only 2baaa30 -- backend
```

Expected:

- `git diff --check 2baaa30 -- backend` exits with code 0.
- changed source and test files are all under `backend/src/**/com/miriyum/domain/reservation/**`.
- no migration, `build.gradle.kts`, Auth, Store, MenuHold, Controller, Service or DTO file appears.

- [ ] **Step 9: Commit Task 5**

Run:

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocation.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityAllocationTest.java
git commit -m "feat(reservation): 예약 수용량 배정과 저장소 경계 구현"
```

## Completion Boundary

이 계획의 모든 Task가 통과해도 #47 전체는 완료가 아니다. 다음 결과만 주장할 수 있다.

- 예약 Java 핵심 모델과 상태 전이가 단위 테스트를 통과함
- 수용량 버킷·배정의 구조적 불변식이 단위 테스트를 통과함
- 승인된 예약 오류 카탈로그가 공통 `ErrorCode` 계약을 구현함
- 기본 JPA Repository가 컴파일됨

다음 결과는 Auth·Store migration과 Testcontainers MySQL surface가 `dev`에 병합될 때까지 `BLOCKED`다.

- Flyway clean-start
- 실제 PK·FK·유일·CHECK 제약
- enum 문자열 저장
- transaction rollback
- 잠금, 조건부 갱신과 동시성
- #47 DB 인수 조건 완료
