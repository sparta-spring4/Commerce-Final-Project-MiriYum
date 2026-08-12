# ReservationCreationIT Fixed Clock Design

> 이 문서는 Issue #215의 구현 설계 이력이며 활성 제품·정책 정본이 아니다.

## 문제

`ReservationCreationIT`는 서비스 날짜를 `2026-08-10`, 예약 시작 시각을 `12:00 Asia/Seoul`로 고정하지만 Spring Context의 `Clock`은 시스템 시각을 사용한다. 실제 시각이 예약 시작 이후가 되면 `ReservationService`가 요청을 `OUTSIDE_RESERVATION_WINDOW`로 거절하여, 예약 생성 동작과 무관하게 네 통합 테스트가 모두 실패한다.

PR #212의 Backend CI는 2026-08-10 12:20 KST에 이 조건으로 실패했고, 동일 HEAD를 12:30 KST에 로컬 실행해 4/4 실패를 재현했다. 해당 테스트 파일은 PR #212의 Pickup 변경분에 포함되지 않는다.

## 목표와 비목표

목표는 `ReservationCreationIT`가 실제 벽시계와 무관하게 동일한 예약 생성 시나리오를 검증하도록 만드는 것이다. 운영 코드, 공개 계약, DB schema, 다른 통합 테스트 및 CI workflow는 변경하지 않는다.

## 선택한 설계

`ReservationCreationIT` 내부에 nested `@TestConfiguration`을 두고 `@Primary` `Clock` Bean을 제공한다. 테스트 클래스는 `@Import`로 이 설정을 명시적으로 적용한다.

고정 Instant는 `2026-08-10T01:00:00Z`로 한다. 이는 매장 시간대 `Asia/Seoul`에서 10:00이며 다음 조건을 모두 만족한다.

- 활성 정책 시각 `2026-08-01T00:00:00Z` 이후다.
- 예약 시작 `2026-08-10 12:00 Asia/Seoul` 이전이다.
- 기존 서비스 날짜, 요일, 영업 구간, 예약 구간 및 수용량 fixture를 변경하지 않는다.

동적으로 미래 날짜를 계산하는 방식은 요일·자정·DST에 다시 의존하므로 사용하지 않는다. 전체 통합 테스트의 공통 Clock을 바꾸는 방식은 영향 범위가 불필요하게 넓으므로 사용하지 않는다.

## 변경 경계

생산 코드의 `Clock` 사용 방식은 그대로 유지한다. 테스트 클래스에 `Clock`, `ZoneOffset`, `TestConfiguration`, `Bean`, `Import`, `Primary` import와 고정 Clock 설정만 추가한다. 테스트 메서드와 assertion은 변경하지 않아 기존 네 실패 자체가 회귀 검증이 된다.

## 오류와 위험

고정 시간이 정책 활성 이전이거나 예약 시작 이후이면 같은 실패가 유지된다. 고정 시간이 다른 영업일이면 매장 schedule 검증 의미가 달라질 수 있다. 선택한 Instant는 동일 서비스 날짜의 월요일 오전 10시이므로 기존 fixture 의미를 보존한다.

남은 위험은 다른 통합 테스트에 유사한 벽시계 의존성이 있을 수 있다는 점이다. Issue #215에서는 관찰된 `ReservationCreationIT`만 수정하며 다른 테스트는 범위 밖이다.

## 검증

1. 수정 전 동일 클래스가 `OUTSIDE_RESERVATION_WINDOW`로 4/4 실패하는 RED 증거를 사용한다.
2. 고정 Clock 적용 후 동일 클래스를 실행해 네 테스트가 통과하는지 확인한다.
3. `integrationTestShardB` 전체를 실행해 shard 회귀를 확인한다.
4. `git diff --check`와 Issue #215 허용 경로 검사를 실행한다.

## 롤백

변경은 테스트 설정에만 한정된다. 문제가 있으면 해당 커밋을 revert하면 운영 데이터나 migration 영향 없이 이전 테스트 동작으로 돌아간다.
