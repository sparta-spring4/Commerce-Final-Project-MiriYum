# Waiting AUTO 접수 단계적 활성화 Runbook

## 목적

이 Runbook은 이미 구현된 Waiting AUTO 접수 worker를 staging에서 검증한 뒤, 승인된 범위 안에서만 운영으로 확장하기 위한 배포 절차다. 이 문서만으로 production 활성화를 승인하지 않는다.

## 안전한 기본 상태

production ECS task definition과 staging Compose는 아래 기본 상태를 유지한다.

```text
MIRIYUM_WAITING_AUTO_OPEN_ENABLED=false
```

이 값이 `false`이면 나머지 AUTO 접수 변수는 비어 있거나 0이어도 worker가 시작되지 않고 job을 claim하지 않는다. production task definition은 CI가 이 기본 상태를 검사한다.

## 활성화 전 공통 확인

1. Issue #412에 staging 대상 매장, 테스트 시간대, 담당자, 관측 시간 범위를 기록한다.
2. 테스트 대상은 AUTO 접수 모드인 전용 staging 매장만 사용한다.
3. 현재 task definition 또는 staging `.env`를 백업해 롤백 기준을 남긴다.
4. CloudWatch Logs에서 backend 로그 그룹을 열고 활성화 시작 시각을 기록한다.
5. worker ID, planning horizon, batch, lease, retry, poll 값은 현재 staging 자원과 테스트 데이터 규모를 근거로 Issue에 남긴다. 소스 코드의 테스트 숫자를 운영 기본값으로 복사하지 않는다.

## enabled=true에 필요한 변수

다음 값이 하나라도 비어 있거나 0이면 애플리케이션은 시작에 실패해야 한다.

```text
MIRIYUM_WAITING_AUTO_OPEN_WORKER_ID
MIRIYUM_WAITING_AUTO_OPEN_PLANNING_HORIZON
MIRIYUM_WAITING_AUTO_OPEN_PLANNING_BATCH_SIZE
MIRIYUM_WAITING_AUTO_OPEN_CLAIM_BATCH_SIZE
MIRIYUM_WAITING_AUTO_OPEN_LEASE_DURATION
MIRIYUM_WAITING_AUTO_OPEN_MAX_ATTEMPTS
MIRIYUM_WAITING_AUTO_OPEN_INITIAL_RETRY_DELAY
MIRIYUM_WAITING_AUTO_OPEN_MAXIMUM_RETRY_DELAY
MIRIYUM_WAITING_AUTO_OPEN_INVALIDATION_BATCH_SIZE
MIRIYUM_WAITING_AUTO_OPEN_POLL_DELAY
MIRIYUM_WAITING_AUTO_OPEN_INITIAL_DELAY
```

`MAXIMUM_RETRY_DELAY`는 `INITIAL_RETRY_DELAY`보다 작을 수 없다. 두 ECS 태스크에는 같은 flag와 설정을 사용한다.

## Staging 1차 smoke

1. staging 배포 담당자가 staging `.env`에 `MIRIYUM_WAITING_AUTO_OPEN_ENABLED=true`와 승인된 변수 집합을 입력한다.
2. staging backend를 재배포하고 `/actuator/health`가 `UP`인지 확인한다.
3. 테스트 시간대가 가까워졌을 때 plan, claim, OPEN 완료를 확인한다.
4. DB 원장의 `COMPLETED` 상태와 실제 waiting reception window가 모두 OPEN인지 대조한다.
5. 아래 상태를 각각 테스트하고 결과를 Issue #412에 식별자 없이 기록한다.

   - 설정 변경 뒤 `STALE_SETTINGS`
   - 운영 시간 변경 뒤 `STALE_INTERVAL`
   - 정상 처리 `COMPLETED`
   - 재시도 한도 초과 또는 불확실한 실패 `RECONCILIATION_REQUIRED`

## Staging 2차 다중 worker 및 재시작

1. 같은 설정으로 staging worker 두 개를 준비한다.
2. 동시에 claim되는 시간대를 만들고, 한 worker를 재시작한다.
3. 동일 시간대가 한 번만 OPEN되는지 확인한다.
4. 이전 lease owner가 늦게 완료를 시도해도 stale fence가 결과를 반영하지 않는지 확인한다.
5. retry, 격리, 처리 지연이 증가하지 않는지 관찰한다.

## CloudWatch 관측

CloudWatch Logs Insights에서 backend 로그 그룹과 활성화 시작 시각 이후 범위를 선택한다. 원시 log의 job ID, store ID 등 식별자는 Issue 또는 PR에 캡처하지 않는다.

정상/무효화 결과 집계:

```text
fields @timestamp, @message
| filter @message like /event=waiting_auto_open_execution/
| parse @message /outcome=(?<outcome>[^ ]+)/
| stats count(*) as count by outcome
```

오류 원인 집계:

```text
fields @timestamp, @message
| filter @message like /event=waiting_auto_open_failure/
| parse @message /failure_class=(?<failure_class>[^ ]+)/
| stats count(*) as count by failure_class
```

Micrometer 지표는 `miriyum.waiting.auto_open.execution`의 `outcome` 태그와 `miriyum.waiting.auto_open.failure`의 `failure_class` 태그를 기준으로 집계한다. 증적에는 시간 범위, 건수, 오류 유무만 남긴다.

## 중단 조건

다음 중 하나라도 발생하면 추가 확장이나 운영 활성화를 중단한다.

- 예상하지 않은 매장 또는 시간대의 OPEN
- 같은 시간대의 중복 OPEN
- stale claim이 완료 처리됨
- 오류, retry, backlog, worker 지연의 지속 증가
- `RECONCILIATION_REQUIRED` 장기 체류
- DB 원장과 실제 reception window 상태 불일치

## 즉시 롤백

1. 대상 환경에서 `MIRIYUM_WAITING_AUTO_OPEN_ENABLED=false`로 변경한다.
2. 새 환경 변수 집합으로 backend를 재배포한다.
3. health check를 통과한 새 task가 worker를 만들지 않는지 확인한다.
4. 원장에서 processing, retry, `RECONCILIATION_REQUIRED` 잔여 건수를 확인한다.
5. 원인 분석과 staging 재현 검증이 끝날 때까지 `true`로 되돌리지 않는다.

## 운영 전환 게이트

운영 활성화는 다음을 모두 충족한 뒤 배포 담당자가 별도로 승인한다.

- staging 1차 smoke 증적
- staging 다중 worker 및 재시작 증적
- CloudWatch 집계 증적과 민감정보 비노출 확인
- 중단 조건 미발생
- 롤백 절차와 담당자 확인

운영의 첫 활성화는 보수적인 승인 설정으로 시작하며, 수치 확대는 별도 관측 결과와 승인 뒤에만 수행한다.
