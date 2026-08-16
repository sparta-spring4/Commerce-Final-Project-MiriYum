# Issue #279 Review Remediation Design

## Goal

PR #382의 네 필수 리뷰 결함을 보완해 고위험 매장 제재가 최신 거래 영향, 2인 승인, 실제 멱등 실행, 기간 만료 경계를 모두 지키게 한다.

## Safety boundaries

- Store 단위 제재만 변경하며 같은 대표자의 다른 Store로 전파하지 않는다.
- 기존 확정 Reservation, Waiting, Pickup, Payment 상태를 취소·환불·직접 변경하지 않는다.
- 다른 도메인의 Entity나 Repository를 참조하지 않고 공개 DTO·Service port만 사용한다.
- 공통 `IdempotencyExecutor`, `HighRiskCommandGuard`, 불변 감사 원장을 재사용한다.
- 미래 `startsAt`은 지원하지 않고 정책 위반으로 거부한다. 즉시 시작과 자동 만료만 지원한다.

## Impact revalidation

영향 preview는 각 공개 port에서 반환한 대상 ID 집합을 정렬해 건수와 함께 digest에 포함한다. 고위험 제재 실행 직전 동일 port들을 다시 조회해 digest를 재계산한다. case version, Store enforcement version, sanction shape, ID 집합 또는 건수 중 하나라도 달라지면 `ADMIN_STORE_004`로 거부한다.

## Approval policy

`FEATURE_RESTRICTION`이 `RestrictedFeature` 전체 집합을 포함하면 `TEMPORARY_SUSPENSION` 및 `PERMANENT_EXIT`과 동일한 고위험 명령으로 분류한다. 생성자는 즉시 집행할 수 없고 `PENDING_APPROVAL` 상태가 되며, 다른 `SUPER_ADMIN`이 재인증과 `HighRiskCommandGuard` 검증을 통과해야 집행한다.

## Idempotency

사건 생성·배정·제재 생성·승인·해제 mutation은 모두 도메인 Service가 소유하는 트랜잭션 안에서 `IdempotencyExecutor`를 호출한다. business key는 principal과 command type 및 target에 결속하고 fingerprint는 정규화된 path/header version/body를 포함한다. 동일 key와 동일 fingerprint는 저장 응답을 replay하고, 다른 fingerprint는 `COMMON_007`로 거부한다. controller는 `IdempotentOutcome`의 상태와 저장 payload를 응답한다.

## Temporary sanction expiry

기간 중지는 즉시 시작만 허용한다. 만료 worker는 종료 시각이 지난 ACTIVE 제재를 제한된 batch로 조회하고 각 제재를 비관적 잠금한 후 현재 sanction version과 Store enforcement version에 결속해 해제한다. 수동 해제와 자동 만료가 경합하면 잠금을 먼저 획득한 전이만 성공하고 다른 경로는 이미 종료된 상태를 무해하게 건너뛴다. 자동 만료는 `EXPIRED`, 수동 해제는 `RELEASED`로 구분하며 감사 이벤트를 남긴다.

## Tests and contracts

- `StoreSanctionImpactServiceTest`: ID 집합 변화 및 실행 직전 재조회 거부
- `StoreSanctionPolicyCatalogTest`: 전체 기능 제한 승인 필요와 미래 시작 거부
- `StoreSanctionCommandServiceTest`: 고위험 승인 경로와 멱등 replay/fingerprint 충돌
- `AdminStoreHttpIT`: mutation replay 및 HTTP 저장 응답
- MySQL 경합 테스트: 자동 만료와 수동 해제 중 단일 상태 전이
- admin-store spec/OpenAPI와 테스트 allowlist를 실제 파일 및 응답 계약에 맞춘다.

## Migration

`EXPIRED` 상태나 worker 조회용 인덱스가 현재 schema check와 일치하지 않으면 작업 시점 최신 `origin/dev`와 열린 PR을 다시 확인해 다음 가용 migration을 사용한다. 기존 V48 번호는 변경하지 않는다.
