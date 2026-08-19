# SSE Runtime 검증 기록

- 소유 Issue: #250
- 기준일: 2026-08-19
- 상태: 정적 계약·전용 실행기 build `PASS`; 실제 local 부하·장애 `BLOCKED`; staging·browser·production `NOT RUN`
- 실행 절차: [SSE Runtime 배포·부하·복구 runbook](../deployment/sse-runtime-runbook.md)

## 검증 경계

SSE는 `data: {}` 변경 신호이며 결과 상태는 Notification 이력 또는 Waiting HTTP API와 MySQL에서 다시 읽는다. Valkey Pub/Sub, SSE payload, cursor 또는 keepalive는 원장·전달 성공·상태 성공의 근거가 아니다. 결과에는 credential, Authorization, Token, cookie, cursor, event ID, account/store/team/notification/reservation ID와 전체 URL을 남기지 않는다.

로컬 loadtest 입력은 timeout 30초, heartbeat 5초, correction 2초, correction batch 100, 전체 연결 상한 200, 계정별 연결 상한 6이다. 아직 운영 기본값이나 SLO로 승인하지 않았다.

## 현재 증거

| 항목 | 상태 | 증거 또는 차단 조건 |
|---|---|---|
| 기본 local stack SSE 비활성 | PASS | Compose 계약이 기본 backend에 `MIRIYUM_SSE_ENABLED`와 SSE proxy가 없음을 검증 |
| loadtest bounded SSE 설정 | PASS | Compose 계약이 일곱 SSE 설정, backend-only cursor secret과 별도 proxy를 검증 |
| 실제 Nginx first frame | PASS | Alpine fake upstream이 종료되기 전에 실제 Nginx를 거친 `notifications.changed`, `data: {}` frame을 2초 안에 수신 |
| SSE 실행기 build | PASS | `grafana/xk6:1.4.11` → `k6 v1.2.2` + `xk6-sse v0.1.12`; 실제 `k6/x/sse` import와 `main.js inspect` 성공 |
| 순수 k6 계약 | PASS | target·fixture·event·session·profile·safe summary를 `--network none`에서 검증 |
| 실제 local endpoint smoke | BLOCKED | ignored local SSE fixture와 저장소 밖 synthetic credential 파일 필요 |
| HTTP 비교 3회 | BLOCKED | 같은 SHA·fixture의 성공 SSE smoke proof 필요 |
| steady 25→50→100→200 | BLOCKED | 실제 local smoke와 승인된 synthetic scope 필요 |
| reconnect·slow-client | BLOCKED | 마지막 성공 steady 단계와 smoke proof 필요 |
| Valkey stop/recovery | BLOCKED | 사전 인증 synthetic 계정과 승인된 public owner mutation fixture 필요 |
| same-SHA backend replacement | BLOCKED | 실제 reconnect 실행 입력 필요 |
| staging | NOT RUN | 배포 SHA, clean harness, fixture, 시간·부하·operator·observer·중단 담당 승인 없음 |
| browser frontend | NOT RUN | #251·#410·#411 소유 범위 |
| production 활성화 | NOT RUN | #148 및 운영 승인 소유 범위 |

## 승격 규칙

실제 실행은 환경, backend full SHA, harness full SHA, profile, 연결 수, 유지 시간, endpoint kind, 전체 threshold 결과와 비식별 aggregate만 기록한다. 25→50→100→200 중 낮은 단계가 실패하면 상위 단계를 실행하지 않고 마지막 완전 성공 단계만 기록한다. Valkey 중단이나 backend 교체에서 public mutation 승인이 없으면 `BLOCKED`를 유지한다.

운영 timeout·heartbeat·correction·batch·연결 한도와 경보 임계치는 동일 입력의 반복 가능한 부하·장애 증거가 있을 때만 ADR-010과 서비스 정책에 승격한다. 실패 시 `MIRIYUM_SSE_ENABLED=false`로 되돌리고 HTTP/MySQL 재조회를 유지하며, 업무 원장이나 Valkey 데이터를 rollback하지 않는다.
