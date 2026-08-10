# ReservationCreationIT Fixed Clock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실제 실행 시각과 무관하게 `ReservationCreationIT`의 네 예약 생성 시나리오가 결정적으로 실행되도록 테스트 전용 고정 `Clock`을 주입한다.

**Architecture:** 운영 코드는 변경하지 않는다. `ReservationCreationIT` 내부의 nested `@TestConfiguration`이 `@Primary Clock`을 제공하고 테스트 클래스가 `@Import`로 이 설정을 명시적으로 활성화한다.

**Tech Stack:** Java 21, Spring Boot Test, JUnit 5, Testcontainers MySQL, Gradle

## Global Constraints

- GitHub Issue #215의 허용 경로만 수정한다.
- 생산 코드, 공개 계약, migration, OpenAPI, 다른 테스트 및 CI workflow를 변경하지 않는다.
- 고정 시각은 `2026-08-10T01:00:00Z`로 사용한다.
- 완료 증거는 `PASS | FAIL | BLOCKED | NOT RUN | NOT CONFIGURED | NOT APPLICABLE` 어휘로만 표현한다.

---

### Task 1: ReservationCreationIT 벽시계 의존 제거

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCreationIT.java`
- Verify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCreationIT.java`

**Interfaces:**
- Consumes: Spring Context의 `java.time.Clock` Bean과 `ReservationService`의 기존 생성 시각 판정
- Produces: `Clock.fixed(Instant.parse("2026-08-10T01:00:00Z"), ZoneOffset.UTC)`를 반환하는 테스트 전용 `@Primary` Bean

- [ ] **Step 1: 기존 실패를 RED 증거로 확인**

Run:

```powershell
.\gradlew.bat integrationTest --tests 'com.miriyum.domain.reservation.service.ReservationCreationIT' --no-build-cache
```

Expected: 네 테스트가 `OUTSIDE_RESERVATION_WINDOW` 또는 성공 결과 0건으로 실패한다. 2026-08-10 12:30 KST 실행에서 4 tests completed, 4 failed를 이미 관찰했다.

- [ ] **Step 2: 테스트 클래스에 고정 Clock 설정 추가**

다음 import를 추가한다.

```java
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
```

테스트 클래스에 설정 import를 추가한다.

```java
@Import(ReservationCreationIT.FixedClockConfig.class)
class ReservationCreationIT {
```

클래스 마지막에 nested 설정을 추가한다.

```java
@TestConfiguration
static class FixedClockConfig {

    @Bean
    @Primary
    Clock reservationCreationIntegrationClock() {
        return Clock.fixed(
                Instant.parse("2026-08-10T01:00:00Z"),
                ZoneOffset.UTC);
    }
}
```

- [ ] **Step 3: 동일 클래스를 GREEN으로 확인**

Run:

```powershell
.\gradlew.bat integrationTest --tests 'com.miriyum.domain.reservation.service.ReservationCreationIT' --no-build-cache
```

Expected: `4 tests completed, 0 failed`, `BUILD SUCCESSFUL`.

- [ ] **Step 4: integration-test-b 전체 회귀 확인**

Run:

```powershell
.\gradlew.bat integrationTestShardB --no-build-cache
```

Expected: `BUILD SUCCESSFUL`, 실패 및 skip 0건.

- [ ] **Step 5: 범위와 형식 확인**

Run:

```powershell
git diff --check
git status --short
git diff --name-only origin/dev...HEAD
```

Expected: Issue #215의 세 허용 경로만 출력되고 형식 오류가 없다.

- [ ] **Step 6: 구현 커밋 생성**

```powershell
git add -- backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCreationIT.java docs/superpowers/plans/2026-08-10-reservation-creation-it-fixed-clock.md
git commit -m 'test(reservation): 생성 IT clock 고정'
```

Expected: 테스트 파일과 계획 문서만 포함한 커밋이 생성된다.

- [ ] **Step 7: 게시 및 CI 재확인**

```powershell
git push -u origin fix/215-reservation-creation-it-clock
```

Draft PR을 `dev` 대상으로 생성하고 `Closes #215`, 실패 run, RED/GREEN, shard 검증, 운영 영향 없음과 PR #212 차단 해소 관계를 기록한다. 새 PR의 Backend CI 결과를 확인한 뒤 PR #212에는 선행 수정 PR 링크를 남긴다.
