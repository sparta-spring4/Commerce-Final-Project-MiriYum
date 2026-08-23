# SSE Runtime 배포·부하·복구 runbook

이 문서는 Issue #250의 Notification·Waiting SSE를 로컬 또는 승인된 staging에서 검증하는 절차다. SSE는 변경 신호일 뿐이며 모든 상태 판정은 수신 뒤 소유 HTTP API와 MySQL 원장으로 다시 확인한다. production host와 실사용자 계정에는 이 절차를 실행하지 않는다.

## 고정 실행기와 안전 경계

- 기존 HTTP 기준선은 `grafana/k6:2.1.0`을 유지한다.
- SSE 전용 이미지는 `grafana/xk6:1.4.11`로 `k6 v1.2.2`와 `github.com/phymbert/xk6-sse@v0.1.12`를 빌드한다. `xk6-sse`가 k6 v2 자동 확장 registry에 없으므로 dependency manifest 자동 해석을 사용하지 않는다.
- 실제 fixture는 ignored `performance/k6/fixtures/sse-test-data.local.json`, 비밀번호는 저장소 밖 환경 파일에 둔다. 이미지·Compose·fixture·결과에는 credential, Token, cookie, cursor, event ID, 계정·매장·팀 ID를 기록하지 않는다.
- local은 backend와 harness의 full SHA가 같아야 한다. staging은 사전 승인, clean harness 확인, 배포 SHA 증거와 필요한 split-SHA 승인이 모두 있어야 한다.
- loadtest override의 timeout 30초, heartbeat 5초, correction 2초, batch 100, 전체 연결 200, 계정별 연결 6은 로컬 시험 입력이며 운영 기본값이 아니다.

## 준비와 정적 검증

저장소 루트에서 다음 파일의 존재 여부만 확인한다. 값을 출력하지 않는다.

```powershell
$required = @(
  'deploy/local/.env',
  'performance/k6/fixtures/sse-test-data.local.json',
  'C:\secure\miriyum-k6-sse.env'
)
foreach ($path in $required) {
  if (-not (Test-Path -LiteralPath $path)) { throw "missing SSE input: $path" }
}
git check-ignore deploy/local/.env performance/k6/fixtures/sse-test-data.local.json
```

전용 이미지를 빌드하고 포함 버전과 script import를 확인한다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest build sse-loadtest

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --no-deps sse-loadtest version
```

출력은 `k6 v1.2.2`와 `github.com/phymbert/xk6-sse v0.1.12, k6/x/sse`를 모두 포함해야 한다. 다른 버전이 나오면 실행하지 않는다.

## 로컬 stack 시작

기존 MySQL volume을 보존한다. `down -v`는 사용하지 않는다.

```powershell
New-Item -ItemType Directory -Force performance/k6/results | Out-Null
$credentialFile = 'C:\secure\miriyum-k6-sse.env'
$commitSha = (git rev-parse HEAD).Trim()
$fixturePath = '/scripts/fixtures/sse-test-data.local.json'

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest up -d mysql valkey backend sse-proxy loadtest-proxy
```

`mysql`, `valkey`, `backend`, `sse-proxy`, `loadtest-proxy`가 healthy 또는 running인지 확인한다. Nginx는 세 SSE route만 비버퍼링으로 backend에 전달하고 일반 `/api/` 요청은 Caddy가 backend로 직접 전달한다.

## smoke와 증거 gate

먼저 세 endpoint kind를 포함한 단일 smoke를 실행한다. 실행 계정과 store 소유 관계는 fixture가 제공하고, 로그인으로 얻은 Refresh family는 Access Token 반환 전에 CSRF logout으로 회수한다.

```powershell
$smokeRunId = 'local-sse-smoke-YYYYMMDD-NN'
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile sse-loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e SSE_PROFILE=smoke `
  -e SSE_FIXTURE_PATH=$fixturePath `
  -e SSE_RUN_ID=$smokeRunId `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$commitSha `
  -e SSE_CONNECTIONS=3 `
  -e SSE_CONNECTIONS_PER_ACCOUNT=1 `
  -e SSE_HOLD_DURATION_SECONDS=30 `
  -e SSE_SLOW_CLIENT_DELAY_SECONDS=2 `
  -e SSE_ENDPOINT_KINDS=notification-consumer,waiting-consumer,waiting-store-operator `
  /scripts/sse/main.js
```

`results/$smokeRunId.json`의 schema, target fingerprint, fixture SHA-256, 두 full SHA, endpoint coverage와 전체 threshold 성공을 이후 프로필이 다시 검증한다. 문자열 run ID만으로 gate를 우회할 수 없다.

## HTTP 비교와 SSE 프로필 순서

SSE 전후 HTTP 비교는 [k6 핵심 API 기준선](../../performance/k6/README.md)의 `notificationHistory` local baseline을 같은 SHA·fixture에서 3회 실행한다. 세 실행 모두 unexpected 4xx, 5xx, dropped iteration이 0이어야 하며 p50·p95·p99 범위만 기록한다.

아래 공통 명령에서 `$profile`, `$connections`, `$runId`를 순서대로 바꾼다. `steady`는 25 → 50 → 100 → 200으로 올리며 낮은 단계가 실패하면 즉시 중단한다. `reconnect`는 마지막 성공 steady 연결 수에서, `slow-client`는 한 계정의 상한 6 이하와 별도 정상 동반 연결을 함께 검증한다. `capacity`는 일반 단계에 섞지 않고 단일 계정·단일 endpoint에 정확히 7개 연결을 만들어 예상 429 1건과 나머지 연결·HTTP probe의 정상성을 확인한다.

```powershell
$profile = 'steady'
$connections = 25
$runId = "local-sse-$profile-YYYYMMDD-NN"
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile sse-loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e SSE_PROFILE=$profile `
  -e SSE_FIXTURE_PATH=$fixturePath `
  -e SSE_RUN_ID=$runId `
  -e SSE_SMOKE_PROOF_PATH=/results/$smokeRunId.json `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$commitSha `
  -e SSE_CONNECTIONS=$connections `
  -e SSE_CONNECTIONS_PER_ACCOUNT=6 `
  -e SSE_HOLD_DURATION_SECONDS=30 `
  -e SSE_SLOW_CLIENT_DELAY_SECONDS=2 `
  -e SSE_HTTP_PROBE_RATE=3 `
  -e SSE_HTTP_MAX_P95_RATIO=10 `
  -e SSE_ENDPOINT_KINDS=notification-consumer,waiting-consumer,waiting-store-operator `
  /scripts/sse/main.js
```

`slow-client`는 일반 profile 명령을 그대로 재사용하지 않는다. 먼저 정상 companion의 90초
emitter timeout과 구분되는 backpressure를 만들도록 heartbeat burst로 backend를 재생성한다.

```powershell
$previousTimeout = [Environment]::GetEnvironmentVariable(
  'MIRIYUM_LOADTEST_SSE_TIMEOUT', 'Process'
)
$previousHeartbeat = [Environment]::GetEnvironmentVariable(
  'MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL', 'Process'
)

try {
  $env:MIRIYUM_LOADTEST_SSE_TIMEOUT = 'PT90S'
  $env:MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL = 'PT0.001S'
  docker compose --env-file deploy/local/.env `
    -f deploy/local/docker-compose.dev.yml `
    -f deploy/local/docker-compose.loadtest.yml `
    --profile loadtest up -d --no-deps --force-recreate backend sse-proxy loadtest-proxy
  if ($LASTEXITCODE -ne 0) {
    throw 'slow-client backend/proxy recreation failed'
  }

  $profile = 'slow-client'
  $connections = 2
  $runId = "local-sse-$profile-YYYYMMDD-NN"
  docker compose --env-file deploy/local/.env `
    -f deploy/local/docker-compose.dev.yml `
    -f deploy/local/docker-compose.loadtest.yml `
    --profile loadtest run --rm --env-from-file $credentialFile sse-slow-loadtest run `
    -e TARGET_ENV=local `
    -e BASE_URL=https://loadtest-proxy:8443 `
    -e ALLOWED_HOSTS=loadtest-proxy `
    -e SSE_PROFILE=$profile `
    -e SSE_FIXTURE_PATH=$fixturePath `
    -e SSE_RUN_ID=$runId `
    -e SSE_SMOKE_PROOF_PATH=/results/$smokeRunId.json `
    -e COMMIT_SHA=$commitSha `
    -e HARNESS_COMMIT_SHA=$commitSha `
    -e SSE_CONNECTIONS=$connections `
    -e SSE_CONNECTIONS_PER_ACCOUNT=2 `
    -e SSE_HOLD_DURATION_SECONDS=100 `
    -e SSE_SLOW_CLIENT_DELAY_SECONDS=40 `
    -e SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS=60 `
    -e SSE_COMPANION_MIN_LIFETIME_SECONDS=85 `
    -e SSE_SLOW_CLIENT_CONNECTIONS=1 `
    -e SSE_SLOW_CLIENT_TRIGGER_APPROVED=true `
    -e SSE_SLOW_CLIENT_IDEMPOTENCY_KEY=<승인된 UUID> `
    -e SSE_HTTP_PROBE_RATE=3 `
    -e SSE_HTTP_MAX_P95_RATIO=10 `
    -e SSE_ENDPOINT_KINDS=waiting-store-operator `
    /scripts/sse/main.js
  if ($LASTEXITCODE -ne 0) {
    throw 'slow-client validation failed'
  }
} finally {
  if ($null -eq $previousTimeout) {
    Remove-Item Env:MIRIYUM_LOADTEST_SSE_TIMEOUT -ErrorAction SilentlyContinue
  } else {
    $env:MIRIYUM_LOADTEST_SSE_TIMEOUT = $previousTimeout
  }
  if ($null -eq $previousHeartbeat) {
    Remove-Item Env:MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL -ErrorAction SilentlyContinue
  } else {
    $env:MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL = $previousHeartbeat
  }
  docker compose --env-file deploy/local/.env `
    -f deploy/local/docker-compose.dev.yml `
    -f deploy/local/docker-compose.loadtest.yml `
    --profile loadtest up -d --no-deps --force-recreate backend sse-proxy loadtest-proxy
  if ($LASTEXITCODE -ne 0) {
    throw 'SSE loadtest timing restoration failed'
  }
}
```

slow client는 최초 changed frame 뒤 40초 동안 한 번만 수신을 멈춘다. `sse-slow-loadtest`의
4 KiB TCP receive buffer, 1ms heartbeat와 로컬 Nginx의 제한된 send buffer가 backlog를 만들고,
slow 연결은 최초 changed frame과 실제 수신 중단을 확인한 뒤 60초 안에 종료돼야 한다. companion은
최초 changed frame을 수신한 콜백에서 후속 Waiting 변경을 실행하므로 고정 시간 대기 없이 구독 준비를 보장한다. 그 변경의
두 번째 changed frame은 backpressure로 먼저 정리될 수 있는 slow 연결이 아니라 companion이 검증한다. companion은
85초 이상 유지된 뒤 정상 90초 emitter timeout으로 종료돼야 하며 같은 100초 구간의 소유 HTTP
probe도 계속 성공해야 한다. burst 입력 복원 전에 다음 profile을 실행하지 않는다.

`capacity` 실행은
`SSE_ENDPOINT_KINDS`를 하나로 줄이고 `SSE_CONNECTIONS=7`,
`SSE_CONNECTIONS_PER_ACCOUNT=7`로 고정한다.

성공 기준은 요청한 모든 연결·event 계약 성공(단, `capacity`의 예상 429 1건은 예외), unexpected 4xx·5xx·transport·contract error·dropped iteration 0, 유한 timeout 종료, 동시 HTTP 이력 오류 0이다. `slow-client`는 여기에 slow cleanup 최대 60초와 companion 최소 85초를 함께 만족해야 한다. summary JSON과 Markdown은 승인된 aggregate만 보존한다.

## Valkey 중단과 backend 교체

장애 전 synthetic 계정의 세션과 승인된 public owner mutation을 준비한다. 해당 mutation이 없거나 owner 승인이 없으면 이 단계를 `BLOCKED`로 기록하고 임의 DB seed나 test-only endpoint를 만들지 않는다. `recovery` 프로필은 매장 운영자 scope 한 개만 사용하며, FIFO 선두 합성 팀을 `WAITING → CALLED`로 변경한 뒤 HTTP 상세에서 MySQL 상태를 확인하고 반드시 `CANCELLED`로 정리한다. SSE payload에는 변경 자원 식별자가 없으므로 recovery store는 실행 구간에 다른 Waiting writer가 없는 전용 합성 store여야 한다. fixture owner와 operator가 이 독점 조건을 확인하지 못하면 다른 변경의 frame을 correction 결과로 오인할 수 있으므로 실행하지 않는다.

먼저 세 endpoint smoke를 통과한 동일 SHA·fixture를 사용한다. 아래 실행은 로그인과 refresh session cleanup을 끝내고 SSE 연결의 초기 `waiting.changed` frame까지 확인한 뒤 `SSE_RECOVERY_READY`를 출력하고 15초 동안 대기한다. 운영자는 이 문구를 확인한 뒤 Valkey를 중단하고, 하네스는 대기가 끝나면 기존 SSE 연결을 유지한 채 승인된 mutation을 실행한다. 중단 명령·완료 시각과 전용 store의 다른 writer 부재 승인은 별도 staging 실행 증거로 남긴다. 이 증거가 없으면 두 번째 신호를 해당 mutation의 MySQL correction 결과로 해석하지 않는다.

```powershell
$recoveryRunId = 'staging-sse-recovery-YYYYMMDD-NN'
$triggerKey = '<승인된 호출 UUID>'
$cleanupKey = '<호출 UUID와 다른 승인된 취소 UUID>'

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile sse-loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e SSE_PROFILE=recovery `
  -e SSE_FIXTURE_PATH=$fixturePath `
  -e SSE_RUN_ID=$recoveryRunId `
  -e SSE_SMOKE_PROOF_PATH=/results/$smokeRunId.json `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$commitSha `
  -e SSE_CONNECTIONS=1 `
  -e SSE_CONNECTIONS_PER_ACCOUNT=1 `
  -e SSE_HOLD_DURATION_SECONDS=30 `
  -e SSE_SLOW_CLIENT_DELAY_SECONDS=1 `
  -e SSE_ENDPOINT_KINDS=waiting-store-operator `
  -e SSE_RECOVERY_ARM_DELAY_SECONDS=15 `
  -e SSE_RECOVERY_MAX_SECONDS=6 `
  -e SSE_RECOVERY_TRIGGER_APPROVED=true `
  -e SSE_RECOVERY_EXCLUSIVE_STORE_APPROVED=true `
  -e SSE_RECOVERY_TRIGGER_IDEMPOTENCY_KEY=$triggerKey `
  -e SSE_RECOVERY_CLEANUP_IDEMPOTENCY_KEY=$cleanupKey `
  /scripts/sse/main.js
```

staging에서는 위와 같은 입력을 승인된 staging host·SHA·clean harness gate로 바꾼다. summary의 `recoveryDuration.max`, `recoveryHttpVerified.count=1`, `recoveryCleanupSuccessful.count=1`과 threshold 전체 성공을 기록한다. 계정·매장·팀·cursor·Token·idempotency key 원문은 기록하지 않는다.

staging 장애 주입은 EC2 shell에서 아래 로컬 Compose 명령을 직접 실행하지 않는다. 하네스가 `SSE_RECOVERY_READY`를 출력한 뒤 GitHub Actions의 `Staging Load-Test Control`을 `dev`에서 실행하고, `action=interrupt-valkey`, 실제 최신 성공 `staging-backend` 배포 full SHA, 빈 `source_ip`를 입력한다. 이 고정 action은 Valkey만 10초 중단하고 같은 SSM 명령에서 자동 재기동·health 확인까지 수행한다. 실패·취소·timeout이면 같은 SHA로 `recover-valkey`를 즉시 실행하고 성공 및 private health `UP` 전에는 테스트를 계속하지 않는다. 세부 권한과 실행 순서는 [Staging Load-Test Operator Runbook](staging-load-test-operator-runbook.md)을 따른다.

아래 명령은 local 환경에서만 사용한다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest stop valkey

# 승인된 public owner API로 사건 1건을 생성하고 HTTP/MySQL 최신 상태를 확인한다.
# 계정·Token·cursor·자원 ID는 프로세스 메모리에만 두고 명령 출력이나 문서에 남기지 않는다.

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest start valkey
```

중단 중 업무 트랜잭션이 롤백되지 않고 correction 뒤 changed 신호와 HTTP/MySQL 조회가 수렴해야 한다. 복구 후 같은 범위의 reconnect를 실행한다.

backend replacement는 proxy를 유지한 채 같은 SHA 이미지만 다시 만든다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest up -d --no-deps --force-recreate backend
```

health 복구 뒤 reconnect와 HTTP 이력이 다시 수렴해야 한다. 장애 전·중·cooldown의 thread, MySQL connection, Valkey connection과 container CPU·memory는 aggregate 범위만 기록하고 raw URI·label·식별자는 남기지 않는다.

## 롤백과 종료

문제가 있으면 SSE proxy를 중지하고 loadtest override 없이 backend를 재생성해 `MIRIYUM_SSE_ENABLED` 주입을 제거한다. HTTP/MySQL 조회는 계속 사용하며 Valkey나 MySQL 데이터를 되돌리지 않는다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest stop sse-proxy loadtest-proxy

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  up -d --no-deps --force-recreate backend
```

정상 종료도 volume을 삭제하지 않는다.

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest down
```

staging은 운영자·독립 observer·중단 담당, 시간대, 최대 연결, 실제 배포 full SHA와 clean harness full SHA, synthetic fixture, external ingress와 private health 증거가 승인되기 전 `NOT RUN`이다. production 활성화와 browser E2E는 이 runbook의 실행 권한 밖이다.
