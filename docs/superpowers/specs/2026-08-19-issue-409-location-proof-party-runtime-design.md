# Issue #409 GPS 위치 증명·일행 참여 관리 Runtime 설계

## 1. 목적과 기준점

Issue #409는 로그인 소비자의 GPS 기반 원격 웨이팅 등록에 서버 판정 위치 증명을 결속하고,
기존 웨이팅 팀에 로그인 일행이 참여·이탈하며 수락 기반으로 대표자를 이전하는 Runtime을
제공한다. 기준점은 2026-08-19의 `origin/dev` `bb1bed93038369766f4c3a17e08a2af9cbe64813`다.

선행 병합은 다음과 같다.

- #307 계약: PR #309, merge `a3adf693`
- #272 계정 전체 활성 membership Runtime: PR #345, merge `4ae495f4`
- #408 소비자 등록·조회·취소 API: PR #416, merge `adf508f1`

격리 worktree의 기존 focused 기준선은 `WaitingCreationServiceTest`,
`WaitingConsumerCommandFacadeTest`, `WaitingOpenApiContractTest` 19 tests, 0 failures다.

## 2. 범위와 비범위

### 포함

- 계정·매장·`WAITING_REGISTRATION` 목적에 결속된 GPS 위치 증명 세션
- 3km 반경, 정확도, 측정 시각과 조작 의심 신호의 서버 판정
- 짧은 TTL과 한 번 소비
- 증명 소비와 팀·순번·대표자 membership 생성의 멱등·원자 처리
- 만료형 일회 초대 발급·철회·수락
- 구성원 본인 이탈과 대표자의 구성원 제거
- 대표자 이전 제안·수락·거절·만료·철회
- 다중 팀 membership과 계정 전체 활성 membership 최대 1건
- 구성 변경 감사와 개인정보 최소화

### 제외

- 매장 현장 QR·일회성·회전 코드의 발급·검증·소비 Runtime과 운영자 API — #446
- 매장별 1km~5km 등록 반경 설정
- `partySize` 변경
- 좌석·테이블 적합성, 순번 미루기
- 전화번호·기기 지문으로 구성원을 추정하는 초대
- 비회원 현장 웨이팅, 프론트엔드, SSE·알림 전달 Runtime

현장 증명은 GPS 실패를 성공으로 추정하지 않는 계약 경계만 유지한다. 후속 Runtime이
활성화되기 전에는 재측정 실패가 원격 등록 실패로 끝난다.

## 3. 확정 제품 불변식

- `partySize`는 실제 방문 인원수이고 membership은 연결된 로그인 계정이다.
- 합류·이탈·제거는 `partySize`를 바꾸지 않는다.
- 활성 membership 수는 `partySize`를 넘지 않는다.
- 초대 수락은 기존 팀에 membership만 추가하고 팀이나 queue sequence를 만들지 않는다.
- 대표자와 구성원 모두 계정 전체에서 활성 membership을 최대 1건만 가진다.
- 일행 구성 변경과 대표자 이전 수락은 `WAITING` 상태에서만 가능하다.
- 대표자는 이전 수락 전까지 바뀌지 않는다.
- 종결은 팀의 모든 활성 membership을 제거한다.

## 4. 검토한 접근

### 선택: 전용 영속 모델

위치 증명 세션, 일행 초대, 대표자 이전 제안, 일행 감사를 서로 다른 모델로 둔다. 각 모델의
필수 필드와 상태 제약을 DB가 표현할 수 있고, 잠금 순서와 개인정보 비보존을 독립적으로
검증할 수 있어 선택했다.

### 제외: 범용 command/token 테이블

하나의 type 기반 테이블은 테이블 수를 줄이지만 nullable 필드와 상태 분기를 늘리고 목적별
DB 제약을 약화한다. 보안·동시성 계약이 강한 이번 범위에는 맞지 않는다.

### 제외: 서명 위치 토큰 중심 설계

서명 토큰도 한 번 소비와 등록 원자성을 위해 중앙 replay ledger가 필요하다. key rotation과
불명확한 결과 복구 비용만 추가되므로 전용 세션보다 이점이 없다.

## 5. 위치 판정 계약

### HTTP 경계

`POST /api/v1/consumers/me/stores/{storeId}/waiting-location-proofs`

요청은 다음 필드를 갖는다.

- `measurementStatus`: `MEASURED`, `PERMISSION_DENIED`, `POSITION_UNAVAILABLE`
- `latitude`, `longitude`, `accuracyMeters`, `measuredAt`: `MEASURED`일 때만 필수
- `integrityStatus`: `CLEAR`, `MANIPULATION_SUSPECTED`

응답 결과는 `VERIFIED`, `OUTSIDE_RADIUS`, `ACCURACY_INSUFFICIENT`,
`PERMISSION_DENIED`, `MEASUREMENT_STALE`, `POSITION_UNAVAILABLE`,
`MANIPULATION_SUSPECTED` 중 하나다. 유효한 판정 요청은 실패 결과도 HTTP 200으로 반환한다.

### `WAITING_LOCATION_V1`

- 플랫폼 반경: 3,000m
- 최대 허용 정확도 반경: 100m
- 측정 최대 경과: 30초
- 미래 시각 허용 오차: 5초
- 통과 세션 TTL: 판정 후 120초
- 통과식: `haversine(store, request) + accuracyMeters <= 3,000m`

권한 거부, 위치 미수신, 낮은 정확도, 오래되거나 미래 허용치를 넘는 측정, 조작 의심은
통과하지 않는다. IP 위치나 운영자 수동 통과는 없다.

### Store 경계

Waiting은 Store Entity·Repository를 참조하지 않는다. Store가 공개하는
`StoreService`/`StoreWaitingLocationProfile`만 사용한다. DTO에는 승인된 기준점 좌표와
`geocodingAddressVersion` 기반 좌표 version, 판정 가능 여부만 둔다. Store 좌표는 판정 호출
스택에서만 사용하고 Waiting 영속 모델로 복제하지 않는다.

### 개인정보

발급 요청 자체에는 `Idempotency-Key`를 받지 않는다. 좌표가 전역 멱등 request fingerprint에
해시 형태로라도 남는 것을 막기 위해서다. 네트워크 재시도는 별도 단기 세션을 만들 수 있고,
등록에서는 선택한 세션 하나만 소비한다.

DB에는 session ID, 계정·매장·목적, 결과·정확도 범주, 정책·매장 좌표 version, 발급·판정·
만료·소비 시각, 소비 team ID만 저장한다. 정확 좌표, 계산 거리, 원본 정확도와 측정 시각은
응답·DB·감사·로그에 저장하지 않는다.

## 6. 등록 원자성

기존 등록 요청에 `locationProofSessionId`를 추가하고 임시
`miriyum.waiting.consumer-registration.location-proof-connected` gate를 제거한다. 등록
request fingerprint에는 session ID만 포함한다.

최초 멱등 실행의 잠금·쓰기 순서는 다음과 같다.

1. 멱등 명령 선점
2. 위치 증명 세션 `FOR UPDATE`
3. 계정·매장·목적, `VERIFIED`, TTL, 미소비 확인
4. 기존 Store/Schedule/Waiting 접수 gate
5. 계정 활성 membership 확인
6. FIFO sequence 잠금·할당
7. 팀과 대표자 membership 생성
8. 증명 `consumedAt`과 team ID 기록
9. 전이 감사·상태 사건·멱등 결과 확정

전체가 기존 `READ_COMMITTED` 등록 트랜잭션에 포함된다. 실패하면 증명 소비도 롤백한다.
미통과·만료·이미 소비·binding 불일치는 노출을 줄이기 위해 모두
`WAITING_013 LOCATION_PROOF_INVALID`로 응답한다.

## 7. 일행 영속 모델

### `waiting_active_memberships`

- `UNIQUE (waiting_team_id)`를 제거한다.
- `INDEX (waiting_team_id)`를 추가한다.
- `UNIQUE (consumer_account_id)`를 유지한다.
- 대표자는 `waiting_teams.consumer_account_id`가 계속 소유한다.
- 대표자는 같은 팀의 활성 membership을 항상 가진다.

MySQL은 순환 관계의 지연 FK를 지원하지 않아 대표자 membership 존재를 별도 FK로 표현하지
않는다. 팀과 membership을 같은 트랜잭션에서 생성·변경하고 서비스·통합 테스트로 불변식을
검증한다.

### 초대

`waiting_party_invitations`는 team, 발급 대표자, SHA-256 token hash, 발급 당시 team version,
상태와 만료·철회·수락 시각을 저장한다. 원문 token은 15분 TTL이며 최초 발급 응답에서만
반환한다. 멱등 저장 payload에는 원문을 넣지 않고 `IdempotentOutcome.replayed`가 `false`인
호출 스택에서만 응답에 결합한다. replay는 같은 초대 metadata와 `invitationCode=null`을
반환한다. 최초 응답을 잃은 대표자는 기존 초대를 철회하고 새 초대를 발급한다. 초대는 한 번만
수락할 수 있다.

### 대표자 이전

`waiting_representative_transfer_offers`는 team, 제안 대표자, 대상 membership, 제안 당시 team
version, 상태와 만료·결정 시각을 저장한다. TTL은 5분이고 nullable active-team key의 unique
제약으로 팀당 활성 제안을 최대 하나로 제한한다.

### 감사

`waiting_party_audits`는 합류·이탈·제거·이전 제안/결과에 대해 행위자, 대상 membership,
전후 team version, bounded 사유, 명령 ID와 시각만 기록한다. 연락처·이름·위치 원문은 없다.

## 8. 일행 HTTP 계약

- `POST /api/v1/consumers/me/waiting-teams/{teamId}/invitations`
- `POST /api/v1/consumers/me/waiting-teams/{teamId}/invitations/{invitationId}/revocations`
- `POST /api/v1/consumers/me/waiting-invitation-acceptances`
- `POST /api/v1/consumers/me/waiting-teams/{teamId}/membership-departures`
- `POST /api/v1/consumers/me/waiting-teams/{teamId}/memberships/{membershipId}/removals`
- `POST /api/v1/consumers/me/waiting-teams/{teamId}/representative-transfer-offers`
- `POST /api/v1/consumers/me/waiting-teams/{teamId}/representative-transfer-offers/{offerId}/acceptances`
- 같은 offer 경계의 `rejections`, `revocations`

모든 쓰기는 UUID `Idempotency-Key`를 사용한다. 초대 수락은 초대에 저장한 발급 당시 team
version을 검증하고, 나머지 구성 변경 요청은 `expectedVersion`을 받는다. 성공한 구성 변경은
version을 1 증가시킨다. 초대 발급·철회와 이전 제안·거절·철회는 구성을 바꾸지 않아 team
version을 올리지 않는다.

현재 조회의 `memberships`에는 opaque `membershipId`, `REPRESENTATIVE|MEMBER`, `joinedAt`,
요청자 여부 `self`만 둔다. 계정 ID·이름·연락처는 반환하지 않는다. 구성원은 상태를 조회하지만
팀 취소는 대표자만 할 수 있다.

## 9. 일행 잠금과 경합

초대·제안에서 team ID를 비잠금 조회한 뒤 모든 명령은 다음 순서를 지킨다.

1. 멱등 명령 선점
2. team `FOR UPDATE`
3. 초대 또는 이전 제안 `FOR UPDATE`
4. membership 확인·변경
5. team version과 감사 확정

초대 수락은 team 잠금으로 capacity를 직렬화한다. 다른 팀을 향한 같은 계정의 병렬 등록·수락은
계정 전체 unique 제약이 최종 방어선이고 기존 bounded deadlock retry를 적용한다.

대표자 이전 수락이 먼저면 대표자와 version이 변경되어 이전 대표자의 취소는 소유권/version
검사에서 실패한다. 취소가 먼저면 팀이 종결되고 모든 membership이 제거되어 수락이 실패한다.
어느 순서든 부분 이전은 없다.

기존 원장·closure·예약 전환의 종결 코드는 team membership을 모두 삭제하고 삭제 수가 1 이상인지
검증한다. 0건이면 `WAITING_008` 중앙 membership 불변식 충돌로 실패 폐쇄한다.

## 10. 오류 계약

기존 `WAITING_003`, `WAITING_005`, `WAITING_006`, `WAITING_008`, `WAITING_011`을 유지한다.
새 오류는 다음 다섯 개로 제한한다.

- `WAITING_013 LOCATION_PROOF_INVALID`
- `WAITING_014 PARTY_INVITATION_INVALID`
- `WAITING_015 PARTY_MUTATION_NOT_ALLOWED`
- `WAITING_016 REPRESENTATIVE_TRANSFER_INVALID`
- `WAITING_017 PARTY_CAPACITY_EXCEEDED`

모두 업무 충돌을 나타내는 HTTP 409다. 타인 팀·초대·제안 존재 여부는 구체적으로 노출하지 않는다.

## 11. TDD와 검증

구현 묶음마다 예상 이유의 RED를 기록한 뒤 최소 production 코드를 작성한다.

- migration: 다중 team membership, 계정 전체 unique, 새 테이블 제약
- 위치: 거리·정확도·시각·권한·무결성·TTL·binding·한 번 소비
- 등록: 증명 소비와 팀·순번·membership의 원자성·멱등 replay
- 일행: 초대, 합류, 이탈, 제거, capacity와 상태 제한
- 이전: 제안·결정·만료와 수락/취소 경합
- 개인정보: 고유 원본 좌표가 DB·응답·감사·멱등 payload·캡처 로그에 없음
- 계약: Waiting OpenAPI와 consumer audience path 1:1

실제 MySQL/Testcontainers에서는 같은 증명의 같은/다른 멱등 키 병렬 등록, 같은 초대 병렬 수락,
같은 계정의 다른 팀 초대 병렬 수락, 마지막 한 자리 병렬 수락, 대표자 이전 수락과 취소 경합을
검증한다.

로컬에서는 관련 단위 테스트와 `WaitingConsumerApiIT`, `WaitingPartyConcurrencyIT`만 실행한다.
전체 `build`, `check`, `integrationTest`, 전체 shard는 실행하지 않고 GitHub CI를 full-suite 정본으로
사용한다. CI가 없으면 전체 검증은 `PENDING`으로 보고한다. OpenAPI는 고정 Redocly lint/bundle,
계약 테스트와 `git diff --check`를 실행한다.

## 12. Exact file allowlist

production 수정 전에 Issue #409 본문에 게시한 2026-08-19 allowlist가 유일한 허용 목록이다.
목록 밖 파일이 필요하면 먼저 Issue #409에 경로와 이유를 추가한 뒤 수정한다. migration은 현재
최고 V59 다음 `V60__create_waiting_location_party_runtime.sql`이며 production 수정 직전과 커밋
직전에 `origin/dev`를 fetch해 번호 충돌을 다시 확인한다.

## 13. 위험과 후속

- 웹 GPS 입력만으로 단말 spoofing을 완전히 증명할 수 없다. 명시된 조작 의심 신호는 실패
  폐쇄하지만 더 강한 attestation은 별도 보안 확장이다.
- Store 좌표 변경과 짧은 TTL 사이에는 판정 당시 좌표 version을 유지한다. 과거 판정을 소급
  변경하지 않는 정책과 일치한다.
- team 대표자와 대표자 membership의 관계는 순환 FK가 아니라 트랜잭션 불변식이다.
- 현장 QR·회전 코드 후속 #446은 매장 운영자 발급 권한, rotation/key 관리, QR 노출·캡처 재사용 방지,
  현장 토큰과 GPS 세션의 목적 분리, 한 번 소비 및 등록 원자성을 별도 설계해야 한다.
