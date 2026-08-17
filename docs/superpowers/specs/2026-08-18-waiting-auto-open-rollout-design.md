# Waiting AUTO 접수 단계적 운영 활성화 설계

## 목적

이미 구현된 Waiting AUTO 접수 worker를 기본 비활성 상태에서 시작해 staging 검증과 배포 승인 후에만 운영으로 단계적으로 확장한다. 이 문서는 런타임, 스키마, 프론트엔드 동작을 변경하지 않는다.

## 범위와 비범위

범위는 환경 변수 계약, staging 검증 절차, 운영 활성화 게이트, CloudWatch 관측 증적, 중단 조건 및 롤백 절차다.

다음은 이 작업에서 하지 않는다.

- AUTO 접수 런타임 또는 DB 스키마 재설계
- 운영 전체에 대한 즉시 활성화
- 근거 없는 polling, lease, batch 수치 결정
- 수동 접수 또는 일시정지 접수 의미 변경
- 알림, SSE 활성화 또는 격리 작업 자동 삭제

## 배포 계약

`MIRIYUM_WAITING_AUTO_OPEN_ENABLED=false`는 안전한 기본값이다. 이 값이 `false`이면 나머지 AUTO 접수 변수는 비어 있거나 0이어도 worker가 생성되지 않고 작업을 claim하지 않는다.

`true`로 전환할 때는 아래 변수를 모두 유효하게 제공해야 한다.

- `MIRIYUM_WAITING_AUTO_OPEN_WORKER_ID`
- `MIRIYUM_WAITING_AUTO_OPEN_PLANNING_HORIZON`
- `MIRIYUM_WAITING_AUTO_OPEN_PLANNING_BATCH_SIZE`
- `MIRIYUM_WAITING_AUTO_OPEN_CLAIM_BATCH_SIZE`
- `MIRIYUM_WAITING_AUTO_OPEN_LEASE_DURATION`
- `MIRIYUM_WAITING_AUTO_OPEN_MAX_ATTEMPTS`
- `MIRIYUM_WAITING_AUTO_OPEN_INITIAL_RETRY_DELAY`
- `MIRIYUM_WAITING_AUTO_OPEN_MAXIMUM_RETRY_DELAY`
- `MIRIYUM_WAITING_AUTO_OPEN_INVALIDATION_BATCH_SIZE`
- `MIRIYUM_WAITING_AUTO_OPEN_POLL_DELAY`
- `MIRIYUM_WAITING_AUTO_OPEN_INITIAL_DELAY`

유효하지 않은 enabled 설정은 애플리케이션 시작을 실패시켜 잘못된 worker가 조용히 실행되는 일을 막는다. 운영 ECS의 두 태스크에는 동일한 기능 플래그와 설정을 배포한다. lease fencing은 재시작 또는 지연된 이전 worker가 새 claim 결과를 덮어쓰지 못하게 한다.

## 단계적 활성화

### 0. Draft PR 및 기본 상태 확인

1. 배포 템플릿과 문서에서 기본값이 `false`인지 확인한다.
2. production task definition에 `true`와 숫자 값을 커밋하지 않는다.
3. 설정 바인딩 및 enabled fail-fast 테스트만 로컬에서 실행한다.
4. 자동화 전체 검증은 GitHub CI를 정식 증적으로 남긴다.

중단 조건: 현재 staging/prod task definition을 확인하지 못했거나 기본값이 `false`가 아니면 이후 단계를 진행하지 않는다.

### 1. Staging 단일 범위 smoke

1. 테스트 전용 AUTO 매장과 가까운 미래의 운영 시간대를 준비한다.
2. staging에서만 유효한 worker 설정을 입력한다. 수치는 테스트 데이터 규모와 현재 staging 자원 관측을 근거로 Issue #412에 기록한다.
3. plan, claim, OPEN 완료를 DB 원장과 API 결과로 대조한다.
4. `STALE_SETTINGS`, `STALE_INTERVAL`, `COMPLETED`, `RECONCILIATION_REQUIRED` 상태를 각각 확인한다.

중단 조건: 예상하지 않은 매장 또는 시간대가 열리거나 원장과 실제 접수 창 상태가 일치하지 않거나 `RECONCILIATION_REQUIRED`가 해소되지 않으면 즉시 `ENABLED=false`로 되돌린다.

### 2. Staging 다중 worker 및 재시작 검증

1. 동일 설정으로 staging worker 두 개를 실행한다.
2. claim 경쟁, lease 만료, 한 worker 재시작을 재현한다.
3. 같은 시간대가 중복 OPEN되지 않는지와 stale fence 결과가 거부되는지를 원장으로 확인한다.
4. worker 지연, 오류, retry, 격리 상태를 집계 지표로 확인한다.

중단 조건: 중복 OPEN, stale claim 완료, lease 손실 뒤 잘못된 완료 또는 지표상 지속 오류가 있으면 운영 단계로 넘어가지 않는다.

### 3. 운영 제한 활성화

운영은 staging 증적을 Issue #412와 Draft PR에 연결하고 배포 담당자 승인을 받은 경우에만 시작한다. 첫 배포에서는 승인된 보수적 설정을 두 ECS 태스크에 동일하게 적용하고, 예정된 소수의 AUTO 시간대만 관찰한다. 수치 확대는 오류, 지연, DB 부하가 안정적이라는 후속 증적이 있을 때만 별도 승인으로 수행한다.

중단 조건: 오류 증가, backlog 증가, DB 또는 worker 지연, 잘못된 접수 창 OPEN, `RECONCILIATION_REQUIRED` 장기 체류가 하나라도 발생하면 즉시 롤백한다.

## 관측과 증적

CloudWatch Logs 및 Metrics에서 다음을 집계한다.

- execution outcome: `completed`, `invalidated`, `stale_claim`
- failure class: `integrity`, `transient_data`, `unexpected`
- retry 및 `RECONCILIATION_REQUIRED` 장기 체류 건수
- worker 처리 지연과 backlog 추세

증적에는 고객, 매장, job 식별자, 이메일, 전화번호, 토큰, 비밀값을 포함하지 않는다. 원시 로그의 식별자 대신 시간 범위와 집계 수치만 Issue 및 PR에 기록한다.

## 롤백

1. 모든 대상 환경에서 `MIRIYUM_WAITING_AUTO_OPEN_ENABLED=false`로 변경한다.
2. 같은 환경 변수 집합을 가진 새 task definition으로 배포한다.
3. 새 worker가 생성되지 않았는지, 기존 처리 중인 claim이 lease 만료 후 안전하게 정리되는지 확인한다.
4. 원장에서 `RECONCILIATION_REQUIRED`, retry, processing 잔여 건수를 확인하고 사람이 대사한다.
5. 원인이 확인되고 staging에서 재현, 검증되기 전에는 재활성화하지 않는다.

## 완료 기준

- disabled 상태는 추가 설정 없이 시작되고 claim을 만들지 않는다.
- enabled 상태의 잘못된 설정은 시작 단계에서 실패한다.
- staging에서 단일, 다중 worker, 재시작, stale 상태, retry 및 격리 흐름 증적이 있다.
- 운영 활성화 전 승인과 관측 기간이 Issue #412에 기록되어 있다.
- 운영 롤백 절차가 한 번의 flag 변경과 배포로 수행 가능함을 확인했다.
