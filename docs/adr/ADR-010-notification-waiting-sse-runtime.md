# ADR-010: Notification·Waiting 통합 SSE Runtime

- 상태: Accepted
- 결정일: 2026-08-19
- 현재 적용 단계: 고도화
- 소유 문서: `docs/06-system-architecture.md`
- 관련 정책: WAIT-010·WAIT-011·WAIT-016, NOTI-004, `docs/service-policies/05-waiting.md`, `docs/service-policies/16-notification.md`

## 문제와 현재 증거

PR #442가 Notification 이력과 소비자·매장 운영자 Waiting 변경을 위한 SSE endpoint 세 개, event 이름, `Last-Event-ID`, 고정 `data: {}`와 HTTP 재조회 계약을 `dev`에 병합했다. 그러나 이 계약은 `contract-only`이며 실제 Spring MVC stream, 재연결 cursor, 다중 instance fan-out, 연결 수명과 장애 보정 Runtime은 아직 없다.

SSE event는 업무 상태가 아니라 변경 신호다. 네트워크와 브라우저 재연결에서는 event가 중복·역순·유실될 수 있고, Valkey Pub/Sub도 내구성 있는 원장이 아니다. Notification과 Waiting의 최신 상태는 MySQL이 소유하므로, stream payload나 Valkey 수신만으로 전달 성공·알림 이력·Waiting 상태·`teamsAhead`를 확정하면 오래된 상태가 노출될 수 있다.

Issue #250은 최초 연결·재연결·다중 탭·다중 instance·Valkey 유실·느린 client·JWT 만료 뒤에도 HTTP/MySQL 최신 상태로 수렴할 것을 요구한다. 운영 connection 수와 재연결 폭주, timeout·heartbeat·보정 주기의 적정 수치는 아직 부하·장애 증거가 없다.

## 단계 제약과 적용 범위

이번 결정은 #250의 SSE Runtime PR 2에 적용한다.

- 포함: Spring MVC SSE transport, scope-bound opaque cursor, 로컬 connection registry, Valkey Pub/Sub wake-up hint, 주기 MySQL correction, Notification·Waiting high-watermark adapter, timeout·heartbeat·connection limit 설정과 Runtime 검증
- 제외: Nginx buffering·timeout, Compose·ECS 환경변수 wiring, 운영 기본 수치·경보 임계치, k6 부하·장애 검증과 runbook, frontend SSE client와 화면
- 데이터: 기존 `notification_tasks`, `notification_channel_attempts`, `waiting_teams`, `waiting_active_memberships`, `waiting_status_events`를 사용하고 이번 Runtime을 위한 Flyway migration은 만들지 않는다.
- 공개 계약: PR #442의 endpoint·event·payload·오류 계약을 유지하며 새 route·상태·오류 코드를 추가하지 않는다.

배포·프록시·부하·장애 검증은 #250 PR 3이, frontend 소비는 #251·#410·#411이 소유한다.

## 검토한 대안

### 공통 transport와 도메인별 adapter

연결·cursor·heartbeat·Valkey·보정을 `global.sse` 기술 계층이 소유하고 Notification과 Waiting은 각자의 high-watermark 조회와 HTTP controller adapter만 제공한다. 공통 wire 계약을 한 곳에서 유지하면서 도메인 원장 접근은 소유 경계 안에 남길 수 있다.

### 도메인별 독립 SSE Runtime

Notification과 Waiting이 각각 cursor, connection registry, heartbeat, Valkey listener와 correction scheduler를 구현한다. 초기 경로는 독립적이지만 같은 공개 계약의 보안·장애 처리 코드가 중복되고 audience 간 동작이 쉽게 달라진다.

### MySQL polling만 사용

모든 instance가 주기적으로 MySQL만 조회한다. 내구성은 단순하지만 정상 변경도 polling 주기만큼 지연되고 연결 수에 비례한 불필요한 조회가 발생한다. Issue #250이 승인한 Valkey wake-up hint 기반 다중 instance 전달을 충족하지 않는다.

## 선택과 선택 이유

공통 SSE transport와 도메인별 high-watermark adapter를 선택한다. MySQL을 유일한 최신 상태 원장으로 유지하고 Valkey는 낮은 지연을 위한 비내구성 wake-up hint로만 사용한다.

### 공통 transport 경계

`global.sse`는 업무 규칙 없이 다음 기술 책임만 소유한다.

- `SseCursorCodec`: contract version, audience, 인증 계정, 선택적 store scope와 MySQL high-watermark를 HMAC으로 보호한 최대 512자 base64url cursor로 인코딩·검증한다.
- `SseConnectionRegistry`: 로컬 연결을 scope별로 등록하고 전역·계정별 상한, 완료·오류·timeout cleanup과 마지막 전송 watermark를 관리한다.
- `SseStreamService`: 연결 전 설정·cursor·권한 검증이 끝난 뒤 `SseEmitter`를 열고 최초 또는 유효한 재연결에서 현재 MySQL watermark에 결속된 changed event를 정확히 한 번 보낸다.
- `SseWakeUpBroker`: 하나의 versioned Valkey channel에서 HMAC scope key와 신호 종류만 발행·구독한다. 계정·store ID, JWT, cursor 원문은 payload나 로그에 넣지 않는다.
- `SseCorrectionScheduler`: 현재 instance에 연결된 고유 scope만 유한 batch로 다시 조회해 유실된 wake-up을 회수한다.

한 연결의 send는 직렬화한다. 중복·역순 wake-up을 받아도 domain adapter가 읽은 현재 MySQL watermark가 마지막 전송값보다 클 때만 새 changed event를 보낸다. keepalive는 SSE comment이며 event ID를 만들거나 cursor를 전진시키지 않는다.

### 도메인 high-watermark 경계

Notification adapter는 인증된 소비자 계정에 대해 `notification_tasks`와 IN_APP channel attempt가 모두 `DELIVERED`이고 공개 이력에 필요한 title·`delivered_at`이 존재하는 행의 최대 `notification_id`를 반환한다. PENDING·FAILED·CANCELLED와 외부 채널은 watermark를 증가시키지 않는다.

Waiting adapter는 `waiting_status_events.waiting_status_event_id`를 단조 watermark로 사용한다.

- 소비자에게 활성 membership이 있으면 본인 팀 사건과 같은 store·business date에서 더 앞선 queue sequence의 공개 상태 사건 중 `teamsAhead`에 영향을 주는 최대 event ID를 반환한다.
- 활성 membership이 없으면 해당 소비자가 소유한 최신 팀의 최대 event ID만 반환해 terminal 상태 신호를 회수하고, 종결 뒤 같은 매장의 무관한 후속 사건을 계속 전달하지 않는다.
- 매장 운영자에게는 권한을 다시 검증한 store의 공개 팀 생성·상태 변경 사건 최대 event ID를 반환한다.
- Waiting 설정, AUTO worker 내부 상태와 Notification worker 상태는 watermark 대상이 아니다.

Waiting 상태 사건 저장은 소유 도메인의 단일 appender로 모은다. appender는 업무 트랜잭션과 함께 immutable event를 저장하고 commit 이후에만 wake-up을 요청한다. Notification은 IN_APP `DELIVERED` 전이가 commit된 이후에만 소비자 scope wake-up을 요청한다. after-commit Valkey 발행 실패는 이미 성공한 업무 트랜잭션을 되돌리거나 성공으로 기록하지 않으며 correction이 회수한다.

### cursor와 인증 수명

SSE cursor는 전용 `MIRIYUM_SSE_CURSOR_SECRET`을 사용하고 JWT secret·Notification history cursor secret과 공유하지 않는다. secret은 최소 32자이며 누락·부적합하면 SSE endpoint만 `503 COMMON_012`로 실패 폐쇄한다. 다른 audience·계정·store·계약 version에 발급된 cursor, 형식 오류와 HMAC 불일치는 stream을 열기 전 `400 COMMON_001`로 거절한다.

consumer와 store-operator는 기존 Bearer JWT namespace를 사용한다. store stream은 연결 시점에 소유 Waiting 공개 권한 Service로 store 조회 권한을 다시 확인한다. 연결 수명은 설정 timeout과 Access JWT 남은 수명 중 짧은 값으로 제한한다. 만료·client 종료·전송 오류·timeout은 해당 연결만 제거하며 다른 연결과 업무 API를 실패시키지 않는다.

### 설정 경계

SSE Runtime은 기본 비활성화한다. 활성화할 때 전용 cursor secret, connection timeout, heartbeat interval, correction interval, 전역 connection 상한과 계정별 connection 상한을 모두 명시하고 양수·순서·최대 범위를 검증한다. 누락되거나 유효하지 않은 정책은 SSE만 `503`으로 닫는다.

정확한 운영 기본 수치, proxy timeout과 경보 임계치는 이 ADR에서 발명하지 않는다. PR 3의 연결·재연결·Valkey 중단·느린 client·rolling replacement 증거로 확정하고 이 ADR의 날짜별 개정 이력에 기록한다.

## 거절한 대안과 이유

도메인별 독립 Runtime은 동일한 cursor·보안·연결 수명·장애 복구를 두 번 구현해 계약 drift와 운영 복잡도를 만든다. 업무 규칙을 `global`에 옮기지 않는 공통 transport와 소유 adapter 조합이 더 작다.

MySQL polling만 사용하는 방식은 Valkey 장애 시 fallback으로는 필요하지만 정상 경로까지 polling 지연과 DB 조회에 의존한다. Valkey를 원장으로 승격하지 않는 wake-up hint와 MySQL correction 조합이 지연과 내구성 경계를 동시에 분리한다.

Redis Streams, Kafka, 분산 lock과 별도 SSE gateway는 현재 connection 규모·전달 backlog·독립 배포 요구의 증거가 없다. 새 내구성 원장이나 분산 조정 실패 경로를 추가하므로 이번 단계에서는 도입하지 않는다.

## 긍정적 결과

- SSE 중복·역순·유실 뒤에도 MySQL watermark와 HTTP 재조회로 최신 상태에 수렴한다.
- Valkey 장애가 Notification·Waiting 업무 트랜잭션을 롤백하지 않는다.
- cursor가 audience·계정·store·version에 결속되어 교차 scope 재사용을 실패 폐쇄한다.
- 연결 수명과 개수가 유한해 느린 client와 JWT 만료가 thread·DB connection을 무한 점유하지 않는다.
- Notification과 Waiting은 상대 Entity·Repository를 직접 참조하지 않고 소유 공개 Service·DTO만 노출한다.

## 알려진 단점과 새 위험

- 연결된 고유 scope마다 correction 조회가 필요해 connection 수에 따라 MySQL 부하가 증가한다.
- Valkey Pub/Sub은 유실 가능하므로 correction interval만큼 갱신이 지연될 수 있다.
- Spring MVC async 연결, heartbeat와 cleanup lifecycle을 운영·관측해야 한다.
- after-commit wake-up과 correction 사이에는 짧은 중복 신호가 생길 수 있다.
- 운영 수치가 아직 확정되지 않아 PR 2만으로 production 규모 적합성을 주장할 수 없다.

## 관측 신호와 재검토 조건

식별자 없는 다음 신호를 PR 3에서 검증하고 운영 지표 후보로 확정한다.

- 현재 연결 수, 계정별 상한 거절 수, timeout·JWT 만료·client 종료 수
- Valkey publish·subscribe 실패와 구독 재시작 수
- correction이 회수한 watermark 증가 수와 correction 조회 지연
- event send 실패·느린 client 제거 수
- 재연결 속도와 HTTP API DB pool·thread pool 영향

합의된 임계값을 지속해서 넘거나 correction 조회가 HTTP API 기준을 침해하면 scope별 polling batch, fan-out topology, 내구성 event log 또는 별도 gateway를 다시 비교한다.

## 전환 전 단순 개선

- 기존 immutable Waiting event와 Notification delivered task ID를 watermark로 사용해 새 outbox table을 만들지 않는다.
- Valkey payload는 상태 본문이 아니라 scope wake-up만 담아 재전송 정합성 책임을 MySQL에 유지한다.
- connection registry는 현재 instance에 연결된 scope만 보정하고 동일 scope의 여러 탭은 한 번 조회한 결과를 공유한다.
- cursor와 설정 검증을 stream open 전에 끝내 JSON 오류와 SSE 오류 경계를 섞지 않는다.

## 다음 전환 후보

부하·장애 증거가 현재 구조의 기준을 지속해서 넘을 때만 Redis Streams·전용 durable outbox·별도 SSE gateway·메시지 broker를 비교한다. 후보 이름만으로 도입을 승인하지 않는다.

## 검증 방법

- cursor 단위 테스트: 위변조, version·audience·account·store 교차 재사용, 길이·문자 형식, secret fail-closed
- registry·lifecycle 단위 테스트: 전역·계정별 상한, 중복 등록, send 직렬화, timeout·JWT 만료·오류 cleanup
- Controller slice: endpoint 세 개, `text/event-stream`, 기존 JWT namespace·store 권한, 최초·재연결 frame과 `400·401·403·404·429·503`
- MySQL 통합: Notification 공개 `DELIVERED` 필터와 Waiting 소비자·운영자 scope watermark, terminal membership, 중복·역순 사건 수렴
- Valkey 통합: 두 instance fan-out, 중복 wake-up 병합, publish 유실·구독 재시작 뒤 correction 회수
- lifecycle 통합: 느린 client·연결 종료·JWT 만료가 다른 stream과 업무 API 자원을 점유하지 않음
- 회귀: backend unit·integration shard, OpenAPI route inventory, `git diff --check`, Issue #250 exact allowlist

## 마이그레이션과 되돌리기

DB migration은 없다. Runtime은 기본 OFF이며 전용 설정이 모두 유효한 환경에서만 활성화한다. PR 2는 애플리케이션 설정 key와 Runtime을 제공하고 deploy wiring은 PR 3에서 별도 검증한다.

문제가 발생하면 SSE Runtime을 비활성화하고 client가 기존 HTTP 조회를 유지하게 한다. Valkey channel과 로컬 registry는 업무 원장이 아니므로 별도 데이터 rollback이 없다. 코드 rollback 시 PR #442의 공개 계약은 `contract-only`로 되돌리거나 후속 계약 PR에서 상태를 명시적으로 복원한다.

## 전환 뒤 확인할 새 단점

PR 3에서 실제 proxy buffering, heartbeat 전달, timeout, 연결·재연결 폭주, Valkey 중단·복구, rolling replacement와 느린 client를 관찰한다. account·store·cursor·JWT 원문을 metric label이나 로그에 넣지 않고, 운영 기본 수치와 경보 임계치는 이 증거 뒤에만 확정한다.

## 관련 문서

- [시스템 아키텍처](../06-system-architecture.md)
- [데이터 및 API 계약](../07-data-and-api-contracts.md)
- [품질 운영 및 규칙](../09-quality-operations-and-rules.md)
- [Notification 정책](../service-policies/16-notification.md)
- [Waiting 정책](../service-policies/05-waiting.md)
- [Notification 기능 명세](../specs/notification/spec.md)
- [Waiting 기능 명세](../specs/waiting/spec.md)
- [ADR-002 단계적 기술 도입](ADR-002-staged-technology-adoption.md)
- [ADR-006 JWT·Valkey 전환](ADR-006-jwt-valkey-refresh-token.md)
- [Issue #250](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/250)
- [PR #442](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/442)

## 날짜별 개정 이력

| 날짜 | 적용 단계 | 관측 증거 | 결정 또는 변경 | 검증 결과 | 새 단점·후속 조건 |
|---|---|---|---|---|---|
| 2026-08-19 | 고도화 | PR #442 계약 병합과 Issue #250 Runtime 인수 조건 | 공통 SSE transport, 소유 high-watermark adapter, Valkey wake-up hint와 MySQL correction 선택 | 설계 승인; Runtime·부하·장애 검증은 후속 PR | 운영 수치·경보 임계치는 PR 3 증거 뒤 확정 |
