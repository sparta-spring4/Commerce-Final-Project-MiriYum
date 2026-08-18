# 핵심 API k6 기준선

## #285 카카오 지오코딩 환경 연결 후 local 재측정

- 검증 commit: 카카오 환경 연결 `c19c73fff960d247896a33abc8a3e97a0076703e`, 예약·알림 runtime `89e38fe0c26622785ad3bb465347687e3d825656`
- 기록일: 2026-08-15
- 실행 환경: Windows 11 Pro 64-bit `10.0.26200`, Intel Core Ultra 9 275HX 24 physical/logical processors, RAM 31.43 GiB, local HTTPS proxy, ignored local fixture, 저장소 밖 합성 계정 자격증명과 카카오 Local REST API 키
- container 자원 경계: local Compose에 CPU·memory limit을 별도로 두지 않아 Docker Desktop이 사용할 수 있는 host 자원을 공유했다. 이 결과를 다른 개발 PC나 staging 사양으로 환산하지 않는다.
- 데이터 규모: 합성 계정 5개(auth 2, reservation 1, notification 2), 지오코딩 `VERIFIED` 공개 매장 1개, 서로 다른 예약 template 31개, 합성 알림 `DELIVERED` 6건(계정당 3건, page size 2)
- 공개 매장은 저장소의 공개 매장 등록 API로 생성했고 지오코딩 `VERIFIED`와 주소 버전 `1`을 확인했다. 키, 좌표, 매장 ID와 응답 본문은 증거에 기록하지 않았다.

### 인증·매장 검색

- smoke `local-auth-search-smoke-20260815-sync01`: `authRefresh` 1회와 실제 결과가 존재하는 `storeSearch` 1회가 통과했고 unexpected 4xx·5xx·dropped iteration은 모두 0이었다.
- baseline 입력: `authRefresh,storeSearch`, `MAX_VUS=2`, `ARRIVAL_RATE=2`, `DURATION_SECONDS=30`. 시나리오별로 1 VU와 1 iteration/s를 배분했다.

| run ID | scenario | measured requests | actual RPS | p50 ms | p95 ms | p99 ms | expected 4xx | unexpected 4xx | 5xx | dropped iterations |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `local-auth-search-baseline-20260815-sync01` | authRefresh | 60 | 1.999055 | 41.956 | 73.329 | 85.784 | 0 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync01` | storeSearch | 31 | 1.032845 | 17.473 | 24.665 | 32.316 | 0 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync02` | authRefresh | 62 | 2.061352 | 36.472 | 64.931 | 68.746 | 0 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync02` | storeSearch | 31 | 1.030676 | 13.767 | 15.397 | 17.281 | 0 | 0 | 0 | 0 |

### 예약 생성

- 공개 운영시간·예약 slot·시간 정책·빈 정기휴무 버전과 날짜별 capacity를 공개 API로 준비했다. smoke `local-reservation-smoke-20260815-sync03`은 1 request, p50·p95·p99 21.438 ms로 통과했다.
- baseline 입력: `reservationCreate`, `MAX_VUS=1`, `ARRIVAL_RATE=1`, `DURATION_SECONDS=30`. 서로 다른 날짜의 template 30개와 executor 경계용 guard template 1개를 사용했다.

| run ID | measured requests | actual RPS | p50 ms | p95 ms | p99 ms | expected 4xx | unexpected 4xx | 5xx | dropped iterations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `local-reservation-baseline-20260815-sync02` | 31 | 1.029766 | 21.640 | 26.115 | 56.316 | 0 | 0 | 0 | 0 |
| `local-reservation-baseline-20260815-sync03` | 30 | 0.997591 | 20.883 | 24.926 | 27.601 | 0 | 0 | 0 | 0 |

첫 실행 `local-reservation-baseline-20260815-sync01`은 설정 검증이 요구한 30개 template보다 executor가 경계 iteration을 하나 더 예약해 31번째 template 부재로 threshold가 실패했으므로 기준선에서 제외했다. 현재 validation의 `ARRIVAL_RATE × DURATION_SECONDS` 계산과 실제 constant-arrival-rate 예약 수가 경계에서 다를 수 있어 guard template을 사용했으며, harness 계약 보강은 [#358](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/358)로 분리했다.

### 알림 이력

- 합성 소비자 두 계정에 공개 예약 API로 확정 이벤트를 각각 3건 만들었다. smoke `local-notification-smoke-20260815-sync02`는 계정별 두 페이지, 총 4 requests를 검증했고 p50 4.637 ms, p95 9.879 ms, p99 10.554 ms로 통과했다.
- baseline 입력: `notificationHistory`, `MAX_VUS=2`, `ARRIVAL_RATE=2`, `DURATION_SECONDS=30`. 한 iteration이 두 페이지를 읽으므로 measured requests는 iteration 수의 두 배다.

| run ID | measured requests | actual RPS | p50 ms | p95 ms | p99 ms | expected 4xx | unexpected 4xx | 5xx | dropped iterations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `local-notification-baseline-20260815-sync01` | 120 | 3.982300 | 4.596 | 6.006 | 6.168 | 0 | 0 | 0 | 0 |
| `local-notification-baseline-20260815-sync02` | 122 | 4.047441 | 4.757 | 6.138 | 7.647 | 0 | 0 | 0 | 0 |

이 기준선 수집 당시에는 기본 JDBC 세션의 `NOW()`보다 예약 알림 `scheduled_at`이 약 9시간 뒤로 기록되어 작업이 `PENDING`에 머무는 것을 관찰했다. 저장소를 변경하지 않고 일회성 worker의 JDBC session timezone만 `Asia/Seoul`로 강제했을 때 해당 6건이 `DELIVERED`로 수렴하고 smoke·baseline이 통과했다. 따라서 이 수치는 알림 조회 API 기준선으로만 사용한다.

[#359](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/359)부터 Notification repository는 offset 입력을 UTC `DATETIME`으로 저장하고 worker·history가 읽을 때 UTC `Instant`로 복원한다. local loadtest와 staging JDBC URL은 기존 `Asia/Seoul` 계약을 유지하며, 다른 도메인의 timestamp 의미를 바꿀 수 있는 전역 datasource timezone 변경은 추가하지 않는다. 공통 `application.yml`의 worker 기본값은 계속 비활성이다. local loadtest override와 staging Compose에서만 `enabled`, policy version, 환경별 worker ID, batch, lease, max attempts, retry, poll, initial delay의 전체 묶음을 제공해 worker를 활성화한다. 일회성 session timezone override로 worker를 실행하거나 환경변수 일부만 채우는 방식은 사용하지 않는다.

네 시나리오의 두 baseline은 각자 동일 smoke 증거와 fixture fingerprint를 사용해 threshold를 통과했고 expected/unexpected 4xx·5xx·dropped iteration은 모두 0이었다. 모든 예약 fixture는 실행 후 공개 취소 API로 정리했다. 이 local 결과만으로 #286의 SQL 병목이나 실서비스 SLO를 주장하지 않는다.

## #338 authRefresh 반복 cleanup 회귀 검증

- 검증 commit: `9302bb6c468514ad35b2ef9fc11bf047cd41e759`
- smoke `local-auth-search-smoke-20260814-02`: `authRefresh` 1회와 `storeSearch` 1회가 통과했고 unexpected 4xx·5xx·dropped iteration은 모두 0이었다.
- baseline 입력: local HTTPS proxy, `authRefresh`, `MAX_VUS=2`, `ARRIVAL_RATE=2`, `DURATION_SECONDS=30`.
- `local-auth-baseline-20260814-02`: 61 iterations, 122 measured requests, p50 40.247 ms, p95 68.365 ms, p99 76.349 ms, unexpected 4xx·5xx·dropped iteration 0.
- `local-auth-baseline-20260814-03`: 61 iterations, 122 measured requests, p50 37.697 ms, p95 65.720 ms, p99 69.530 ms, unexpected 4xx·5xx·dropped iteration 0.
- 잔여 family 비교 실행 `local-auth-baseline-20260814-04`: 60 iterations, 120 measured requests, p50 35.290 ms, p95 63.671 ms, p99 65.627 ms, unexpected 4xx·5xx·dropped iteration 0. 수정 전 실패 실행이 남긴 활성 family 기준값 62개가 실행 뒤에도 62개로 유지되어 새 활성 family가 누적되지 않았다. 기존 62개는 이 PR에서 직접 삭제하지 않는다.
- 2026-08-14 실행 당시 공개 매장 생성은 로컬 `MIRIYUM_KAKAO_LOCAL_REST_API_KEY`가 구성되지 않아 지오코딩 단계에서 HTTP 503으로 실패했다. 따라서 결과가 0건인 검색 smoke만 수행했으며 `storeSearch` baseline 수치나 #286 병목 증거로 사용하지 않는다. 이후 #285의 loadtest override에는 이 값을 backend에만 선택 전달하도록 연결했지만, 실제 키와 합성 매장을 준비해 재측정하기 전까지 기존 결과의 상태는 바뀌지 않는다.

- 소유 Issue: [#285](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/285)
- 기록일: 2026-08-14
- 기준 `dev`: `bb46e4e140d964a53d19d1ab97ce90a300551a9f`
- 검증 script commit: `012caa479ad64a128ead70595d59a621f8b9314c`
- 단계: 고도화
- 해석: 최초 환경별 기준선을 수집하기 위한 harness 증거이며 실서비스 SLO 판정이 아니다.

## 실행 상태

| 구간 | 상태 | 관찰 결과 |
|---|---|---|
| k6 계약 테스트 | PASS | 고정 k6 이미지에서 config 38, 공통 계약 27, recovery rate-limit 10, runtime options 2, scenario 45, smoke proof 9, summary 6 — 총 137 checks가 성공했다. |
| k6 계약 CI workflow | PASS | `rhysd/actionlint:1.7.7`이 path-filtered workflow를 오류 없이 검증했으며 workflow는 `--network none`으로 외부·local API 접근을 차단한 고정 k6 이미지에서 계약 테스트만 실행한다. |
| k6 smoke profile inspect | PASS | 인증 1 iteration, 검색 1, 예약 1, 알림은 명시한 2개 합성 계정에 대해 2 iterations로 해석됐다. |
| k6 local-baseline profile inspect | PASS | 동일 target·commit·fixture의 smoke artifact를 전달했을 때 `storeSearch`, 1 VU·1 arrival/s·10초가 하나의 constant-arrival-rate executor로 해석됐다. commit이 다른 artifact는 init context에서 요청 전에 거부됐다. |
| k6 Secure cookie TLS probe | PASS | 고정 k6 이미지의 실제 VU cookie jar가 Caddy 내부 TLS를 거쳐 mock login의 `Secure` refresh cookie를 다음 refresh 요청에 재전송했다. |
| k6 mock runtime summary | NOT RUN | 응답 검증과 summary schema가 변경된 현재 script commit에서는 네트워크 mock을 반복하지 않았다. summary 입력·비식별 출력은 고정 k6 계약 테스트로 검증했고 실제 backend smoke·baseline은 아래 별도 실행에서 검증했다. |
| Local Compose 합성 | PASS | load-test override 사용 시 `mysql`, `valkey`, `backend`, `loadtest-proxy`, `loadtest`; 기본 Compose 단독 사용 시 기존 `mysql`, `backend`만 존재했다. |
| Backend 단위 테스트 | PASS | 최종 브랜치 상태에서 backend 작업 디렉터리의 `.\gradlew.bat test`가 exit 0이었다. |
| Backend assemble | PASS | `.\gradlew.bat assemble`이 compile·bootJar·jar를 포함해 exit 0이었다. |
| Backend 통합 테스트 | FAIL | 전체 task와 shard A가 Testcontainers JDBC readiness/context 전환 중 각각 15분·10분 안에 종료되지 않았다. |
| Backend build | NOT RUN | `build`가 실패한 통합 gate에 의존하므로 동일 장시간 실행을 반복하지 않았다. PR의 공식 A~D CI shard 성공이 필요하다. |
| Local smoke·baseline | PASS | ignored fixture와 저장소 밖 자격증명을 사용해 네 시나리오 smoke와 baseline 2회를 실행했고 모든 threshold가 통과했다. actual RPS·지연·오류는 환경별 결과 표에 기록했다. |
| Staging smoke·baseline | NOT RUN | reviewed hostname과 #358·#359 선행 계약은 `dev`에 반영됐고 외부 HTTP→HTTPS `301`, HTTPS API `405`, 공개 Actuator `404`를 관찰했다. 하지만 private backend health `UP` 원증거, split-SHA·시간·부하 상한·합성 fixture·operator·observer·예외 주입·제거 승인이 없어 staging k6 요청은 보내지 않았다. 후속 실행은 [#357](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/357)이 소유한다. |

staging의 `NOT RUN`은 성공이 아니다. local 결과는 위 비식별 fixture 규모와 실행 artifact에서 관찰한 값이며 staging 지연시간·처리량으로 추정하지 않는다.

## 실행 환경

| 항목 | 실제 값 |
|---|---|
| Docker Client | `29.4.3` |
| Docker Compose | `v5.1.3` |
| k6 image | `grafana/k6:2.1.0` |
| k6 image digest | `sha256:65c920dc067d5e2e00befbf982af6ad6ad0117034e8b1c65817c7975c52d4669` |
| Caddy image | `caddy:2.10.2-alpine` |
| Caddy image digest | `sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d` |
| Host | Windows 11 Pro 64-bit `10.0.26200`, Intel Core Ultra 9 275HX, 24 physical/logical processors, RAM 31.43 GiB |
| Local DB·Valkey·backend runtime | `mysql`, `valkey`, `backend`, `loadtest-proxy` container 실행; Compose CPU·memory limit 미설정 |
| Staging runtime·인스턴스 사양 | 확인되지 않음 |

## 실제 명령과 결과

### k6 계약 테스트

```powershell
$tests = @('config-contract.js', 'contracts-contract.js', 'recovery-rate-limit-contract.js', 'runtime-options-contract.js', 'scenario-contract.js', 'smoke-proof-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run --quiet "/scripts/tests/$test"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

관찰 결과:

- `config-contract.js`: 38/38 checks 성공
- `contracts-contract.js`: 27/27 checks 성공
- `recovery-rate-limit-contract.js`: 10/10 checks 성공
- `runtime-options-contract.js`: 2/2 checks 성공
- `scenario-contract.js`: 45/45 checks 성공
- `smoke-proof-contract.js`: 9/9 checks 성공
- `summary-contract.js`: 6/6 checks 성공
- 계약 테스트의 의도적 예약 conflict와 인증 rate-limit은 합계 `expected_4xx=2`, 공개 계약에 없는 `RESERVATION_004`와 notification invariant conflict는 `unexpected_4xx=2`로 분리됐다. 이는 실제 환경 오류율이 아니다.

### profile inspect

네 시나리오 smoke:

```powershell
$commitSha = git rev-parse HEAD
$harnessCommitSha = $commitSha
docker run --rm `
  -v "${PWD}/performance/k6:/scripts:ro" `
  grafana/k6:2.1.0 inspect `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=smoke `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.example.json `
  -e RUN_ID=local-inspect `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  /scripts/main.js
```

`storeSearch` local-baseline options:

```powershell
$commitSha = git rev-parse HEAD
$harnessCommitSha = $commitSha
docker run --rm `
  -v "${PWD}/performance/k6:/scripts:ro" `
  -v "${PWD}/performance/k6/results:/results:ro" `
  grafana/k6:2.1.0 inspect `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=local-baseline `
  -e LOCAL_SMOKE_RUN_ID=review-smoke `
  -e SMOKE_PROOF_PATH=/results/review-smoke-proof.json `
  -e SCENARIOS=storeSearch `
  -e MAX_VUS=1 `
  -e DURATION_SECONDS=10 `
  -e ARRIVAL_RATE=1 `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.example.json `
  -e RUN_ID=local-baseline-inspect `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  /scripts/main.js
```

두 명령 모두 exit 0이었다. baseline inspect에는 smoke summary schema와 같은 ignored 임시 artifact를 `/results`에 read-only mount하여 사용했고, target environment·target fingerprint·commit SHA·fixture SHA-256·시나리오 포함 관계·고정 smoke 상한·threshold 성공을 현재 입력과 대조했다. 같은 artifact에 다른 commit SHA를 전달한 부정 검사는 `smoke proof commit does not match`로 exit 1이었고 임시 artifact는 검증 뒤 삭제했다. example fixture는 schema 검증 전용이므로 이 결과는 실제 API smoke 또는 성능 증거가 아니다.

### Secure cookie TLS probe

고정 `grafana/k6:2.1.0`과 `caddy:2.10.2-alpine`을 임시 격리 Docker network에서 실행했다. mock login은 backend와 같은 `Secure; HttpOnly; SameSite=Strict` refresh cookie를 반환했고, 같은 VU의 다음 refresh 요청은 cookie를 자동 재전송해 200을 받았다. 1/1 check와 두 HTTP 요청이 성공했다. 이 결과는 로컬 TLS 전송 경계와 k6 cookie jar 동작만 검증하며 실제 backend smoke 성공이나 성능 수치로 해석하지 않는다.

직전 검증 script commit의 TLS mock `storeSearch` smoke와 안전 summary 결과는 현재 script commit의 실행 증거로 재사용하지 않는다. 현재 summary schema, threshold 성공 판정과 비식별 필드 제한은 `summary-contract.js`에서 검증했으며 mock 지연시간은 실제 backend 기준선으로 기록하지 않는다.

### Compose 합성

```powershell
docker compose --env-file deploy/local/.env.example `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest config --services

docker compose --env-file deploy/local/.env.example `
  -f deploy/local/docker-compose.dev.yml `
  config --services
```

첫 명령은 `mysql`, `valkey`, `backend`, `loadtest-proxy`, `loadtest`, 두 번째 명령은 `mysql`, `backend`를 출력했다. Valkey command도 Compose 렌더 뒤 `$${MIRIYUM_VALKEY_PASSWORD}` 참조를 유지해 예시 password 값을 command 문자열에 펼치지 않았다. `loadtest-proxy`는 내부 CA로 발급한 로컬 HTTPS 인증서를 사용해 backend의 `Secure` refresh cookie를 그대로 왕복시키며, backend health와 proxy TLS listener가 준비된 뒤에만 k6가 시작된다.

### Local smoke·baseline

실제 실행은 아래 Compose 명령에 표의 비식별 입력을 대입했다. `$credentialFile`의 저장소 밖 실제 경로, 계정 원문과 카카오 키는 기록하지 않는다.

```powershell
$harnessCommitSha = $commitSha
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=$profile `
  -e LOCAL_SMOKE_RUN_ID=$smokeRunId `
  -e SMOKE_PROOF_PATH=/results/$smokeRunId.json `
  -e SCENARIOS=$scenarios `
  -e MAX_VUS=$maxVus `
  -e DURATION_SECONDS=$durationSeconds `
  -e ARRIVAL_RATE=$arrivalRate `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.local.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  /scripts/main.js
```

smoke에서는 `PROFILE=smoke`를 사용하고 `LOCAL_SMOKE_RUN_ID`와 `SMOKE_PROOF_PATH`를 전달하지 않았다. baseline에 사용한 실제 입력과 결과 artifact는 다음과 같다.

| baseline run ID | prerequisite smoke run ID | commit SHA | scenarios | max VUs | arrival rate(iteration/s) | duration seconds | artifact |
|---|---|---|---|---:|---:|---:|---|
| `local-auth-search-baseline-20260815-sync01` | `local-auth-search-smoke-20260815-sync01` | `c19c73fff960d247896a33abc8a3e97a0076703e` | `authRefresh,storeSearch` | 2 | 2 | 30 | ignored JSON·Markdown summary |
| `local-auth-search-baseline-20260815-sync02` | `local-auth-search-smoke-20260815-sync01` | `c19c73fff960d247896a33abc8a3e97a0076703e` | `authRefresh,storeSearch` | 2 | 2 | 30 | ignored JSON·Markdown summary |
| `local-reservation-baseline-20260815-sync02` | `local-reservation-smoke-20260815-sync03` | `89e38fe0c26622785ad3bb465347687e3d825656` | `reservationCreate` | 1 | 1 | 30 | ignored JSON·Markdown summary |
| `local-reservation-baseline-20260815-sync03` | `local-reservation-smoke-20260815-sync03` | `89e38fe0c26622785ad3bb465347687e3d825656` | `reservationCreate` | 1 | 1 | 30 | ignored JSON·Markdown summary |
| `local-notification-baseline-20260815-sync01` | `local-notification-smoke-20260815-sync02` | `89e38fe0c26622785ad3bb465347687e3d825656` | `notificationHistory` | 2 | 2 | 30 | ignored JSON·Markdown summary |
| `local-notification-baseline-20260815-sync02` | `local-notification-smoke-20260815-sync02` | `89e38fe0c26622785ad3bb465347687e3d825656` | `notificationHistory` | 2 | 2 | 30 | ignored JSON·Markdown summary |

각 baseline JSON의 `schemaVersion`, target·commit·fixture fingerprint, threshold 성공과 scenario별 `httpRequests.rate`를 대조했다. 위 actual RPS는 측정 request rate이며 iteration/s 입력과 구분한다. `authRefresh`는 iteration마다 login·refresh 두 요청, `notificationHistory`는 두 페이지 요청을 측정하므로 actual RPS가 iteration arrival rate보다 크다.

### Backend 회귀

```powershell
Push-Location backend
.\gradlew.bat test
.\gradlew.bat assemble
.\gradlew.bat --no-daemon clean integrationTest
.\gradlew.bat --no-daemon integrationTestShardA
Pop-Location
```

`test`와 `assemble`은 exit 0이었다. `clean integrationTest`는 15분, `integrationTestShardA`는 10분 제한까지 종료되지 않았다. timeout 뒤 thread dump에서 test worker가 `JdbcDatabaseContainer.createConnection()` 재시도 또는 Spring test context lifecycle 종료 latch를 기다리는 상태를 확인했다. timeout으로 남은 해당 실행의 Java PID와 Testcontainers만 정리했고 저장소 파일은 수정하지 않았다. 이 PR은 backend 소스를 변경하지 않지만, 로컬 통합·전체 build를 성공으로 표시하지 않으며 PR의 `Backend CI` A~D shard 결과를 병합 gate로 사용한다.

## 환경별 성능 결과

### Local

| run ID | scenario | 입력 | p50 ms | p95 ms | p99 ms | actual RPS | expected 4xx | unexpected 4xx | 5xx |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| `local-auth-search-baseline-20260815-sync01` | authRefresh | 1 VU·1 iter/s·30s 배분 | 41.956 | 73.329 | 85.784 | 1.999055 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync02` | authRefresh | 1 VU·1 iter/s·30s 배분 | 36.472 | 64.931 | 68.746 | 2.061352 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync01` | storeSearch | 1 VU·1 iter/s·30s 배분 | 17.473 | 24.665 | 32.316 | 1.032845 | 0 | 0 | 0 |
| `local-auth-search-baseline-20260815-sync02` | storeSearch | 1 VU·1 iter/s·30s 배분 | 13.767 | 15.397 | 17.281 | 1.030676 | 0 | 0 | 0 |
| `local-reservation-baseline-20260815-sync02` | reservationCreate | 1 VU·1 iter/s·30s | 21.640 | 26.115 | 56.316 | 1.029766 | 0 | 0 | 0 |
| `local-reservation-baseline-20260815-sync03` | reservationCreate | 1 VU·1 iter/s·30s | 20.883 | 24.926 | 27.601 | 0.997591 | 0 | 0 | 0 |
| `local-notification-baseline-20260815-sync01` | notificationHistory | 2 VU·2 iter/s·30s | 4.596 | 6.006 | 6.168 | 3.982300 | 0 | 0 | 0 |
| `local-notification-baseline-20260815-sync02` | notificationHistory | 2 VU·2 iter/s·30s | 4.757 | 6.138 | 7.647 | 4.047441 | 0 | 0 | 0 |

### Staging

| scenario | 입력 | p50 | p95 | p99 | RPS | expected 4xx | unexpected 4xx | 5xx |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| authRefresh | NOT RUN — #357 실행 승인 대기 | — | — | — | — | — | — | — |
| storeSearch | NOT RUN — #357 실행 승인 대기 | — | — | — | — | — | — | — |
| reservationCreate | NOT RUN — #357 fixture·실행 승인 대기 | — | — | — | — | — | — | — |
| notificationHistory | NOT RUN — #357 실행 승인 대기 | — | — | — | — | — | — | — |

### #357 실행 하네스 보강

staging 요청을 보내지 않은 상태에서 #429 병합 SHA `b468ccb34a367db43c8114e2acade7afad0c3aed`의 고정 `grafana/k6:2.1.0` 이미지와 `--network none`으로 7개 계약 suite, 총 137/137 checks를 검증했다. `config-contract.js` 38/38, `contracts-contract.js` 27/27, `recovery-rate-limit-contract.js` 10/10, `runtime-options-contract.js` 2/2, `scenario-contract.js` 45/45, `smoke-proof-contract.js` 9/9, `summary-contract.js` 6/6 checks가 성공했다. 이는 로컬 정적 계약 증거일 뿐 staging 배포·smoke·baseline·기본 429 복구의 runtime 증거가 아니다.

2026-08-18 사전 점검에서 HTTP API 경로의 같은 HTTPS 경로 전환 `301`, HTTPS token-refresh 경로의 비인증 `HEAD`에 대한 API-level `405`와 `Allow: POST`, 공개 `/actuator/health`의 의도된 `404`를 관찰했다. 최근 성공한 `staging-backend` 배포 marker는 backend SHA `adf508f10fed3bfd061869a3abce7abe9dad075d`를 가리켰다. 다만 private `127.0.0.1:8080/actuator/health`의 `UP` 원응답은 이 점검에서 직접 읽지 않았고, backend와 harness SHA가 다르므로 실제 실행 전 split-SHA 승인이 필요하다.

- `COMMIT_SHA`: 실제 배포 backend full SHA
- `HARNESS_COMMIT_SHA`: 실행 중인 k6 script full SHA
- local은 두 SHA가 같아야 하고, staging split SHA는 `STAGING_SPLIT_SHA_APPROVED=true`가 별도 승인된 경우에만 허용한다.
- staging은 `HEAD=HARNESS_COMMIT_SHA`이고 `performance/k6`에 tracked·staged·untracked 변경이 없는 checkout을 확인한 뒤에만 `STAGING_HARNESS_SOURCE_VERIFIED=true`를 전달한다. caller가 SHA 문자열만 주장하는 것은 증거가 아니다.
- smoke artifact는 두 SHA를 모두 기록하며 baseline이 둘 다 대조한다.
- `reservationCreate`의 #358 executor 경계 계약은 `dev`에 병합됐다. 실제 staging에서는 배분된 arrival rate와 duration의 경계 guard까지 포함한 비충돌 template을 별도 검토하고 `STAGING_RESERVATION_FIXTURE_APPROVED=true`가 승인된 경우에만 포함한다.
- 복구 verifier는 예외 제거·동일 backend SHA 재배포·새 로그인 창 확인 뒤 단일 VU로 5회 성공과 session logout을 수행하고 6번째 로그인의 정확한 `429`만 승인한다.
- 실제 IP·email·password·Token·cookie·Authorization header·요청/응답 원문은 summary와 보관 로그에 남기지 않는다.

## 위험과 다음 실행 gate

- local fixture와 실제 키는 계속 Git에서 제외하며, 이번 실행의 비식별 규모·fingerprint·요약만 문서화했다. fixture 원문이나 실행 자격증명을 commit하지 않는다.
- 로컬 backend integration task가 Testcontainers readiness/context 종료에서 시간 초과됐다. 현재 변경과 독립적인 환경·suite 종료 문제지만 CI A~D shard가 성공하기 전에는 회귀 검증이 완료되지 않는다.
- staging 지오코딩 키 배선의 선행 작업은 [#362](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/362)로 `dev`에 병합됐다. 실제 staging fixture 준비 성공은 #357 실행 증거에서 별도로 확인한다.
- 공유 staging의 동시 트래픽과 데이터 상태는 측정 noise가 될 수 있다. 승인 시간과 실행 전후 CloudWatch 구간을 함께 기록해야 한다.
- staging host는 호출자가 함께 넘기는 allowlist만 신뢰하지 않고 저장소에 리뷰된 `staging-api.miriyum.click`만 허용한다. 외부 경계에서는 HTTP→HTTPS `301`, HTTPS API-level `401` 또는 `405`, 공개 Actuator `404` 차단을 확인하고, 실제 backend health `UP`은 배포·SSM의 private loopback 증거로 확인한다.
- 예약 baseline은 배분된 `ARRIVAL_RATE × DURATION_SECONDS`와 executor 경계 guard를 포함한 충돌하지 않는 template을 소모한다. 반복 사용으로 예상 409 비율을 왜곡하지 않으며 #358 보강은 `dev`에 병합됐다. 별도 `STAGING_RESERVATION_FIXTURE_APPROVED=true`가 승인되기 전에는 staging `reservationCreate`를 실행하지 않는다.
- 인증은 IP rate limit을 의도된 429로 분리한다. 알림·예약 setup은 fixture에서 실제로 쓰는 계정만 로그인한다.
- 로컬 k6 요청은 전용 HTTPS proxy를 통해서만 보내고, 내부 CA 인증서 검증 완화는 `TARGET_ENV=local`에만 적용한다. backend의 refresh cookie 보안 속성은 낮추지 않는다.
- 혼합 프로필의 인증 refresh 계정, 예약 계정, 알림 이력 계정은 서로 격리하며 알림 조회는 fixture가 약속한 두 번째 페이지가 실제로 없으면 계약 실패로 처리한다.
- 알림 200 응답도 OpenAPI의 cursor, pageSize, PublicId, purpose, title, resource, action, offset date-time 계약을 모두 만족해야 하며 두 번째 페이지에 실제 항목이 없으면 실패한다.
- k6의 전역 VU ID가 다중 시나리오에서 연속적이지 않을 수 있으므로 auth pool은 전체 `MAX_VUS` 이상을 요구해 modulo 계정 선택이 동시에 같은 계정을 가리키지 않게 한다.
- 인증 login 또는 refresh가 429로 끝나면 분류 counter에는 남기되 완성된 인증 iteration으로 인정하지 않는다. constant-arrival-rate의 `dropped_iterations`도 0이 아니면 실행을 실패시키고 안전 summary에 남긴다.
- backend 기본 IP rate limit은 login 5회/600초, refresh 30회/60초다. local loadtest override는 하네스 최대 입력을 수용하도록 각각 `600,000회/600초`, `60,000회/60초`를 backend에만 주입한다. 이 조건의 결과는 순수 인증 지연시간·처리량 기준선이며 기본 보호 동작 검증이 아니다. override 없이 실행해 429가 섞인 `authRefresh` 결과는 p50/p95/p99 또는 #286 근거로 사용하지 않는다.
- staging은 전체 한도를 높이지 않는다. [#377](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/377)로 병합된 단일 공인 IPv4 예외를 승인된 실행 직전에만 주입하고, smoke·baseline의 성공·실패·중단과 무관하게 값을 제거한 뒤 같은 backend SHA를 재배포해 기본 정책의 `429` 복구를 확인한다. IPv6는 허용하지 않는다. 예외는 login·refresh에만 적용하며 최초 실제 적용 시 프로세스당 한 번만 IP 없는 경고 이벤트를 남긴다. IP·token·cookie·raw HTTP output은 결과에 남기지 않는다.
- 공개 매장 검색의 기본 한도는 source IP 기준 60회/60초이고 local loadtest override는 `60,000회/60초`를 주입한다. override가 없는 환경에서 `2 iterations/s × 30초`를 반복하려면 이전 실행 종료 후 최소 60초를 기다려 새 창에서 시작한다. 어느 환경이든 예상 429가 발생한 결과는 검색 처리량·p50/p95/p99 기준선 또는 #286 근거로 사용하지 않는다.
- CSRF 준비 제한은 local loadtest override에 복제하지 않고 backend 기본 예산 60회/60초를 상속한다. 일반 `MAX_VUS` 상한은 100이지만 `authRefresh`를 선택한 baseline은 이 예산 아래의 50으로 제한한다. 예약·알림처럼 auth refresh를 선택하지 않은 시나리오는 CSRF 예산 때문에 50으로 제한하지 않는다.
- 성공한 인증 login은 refresh 결과와 관계없이 같은 cookie jar에서 CSRF 토큰을 준비한 뒤 현재 session을 logout한다. k6의 `noCookiesReset=true`가 같은 VU의 cookie jar를 iteration 사이에 유지하므로 VU runtime의 CSRF 토큰 캐시와 수명이 일치하며, VU별 cookie jar 격리는 유지된다. CSRF 토큰과 쿠키는 client별로 재사용해 IP당 준비 요청 제한을 iteration 수만큼 소비하지 않는다. setup의 Reservation·Notification bearer 준비 로그인도 Access Token을 반환하기 전에 Refresh Token family를 회수한다. CSRF·logout 요청은 `phase=cleanup`이라 성능 threshold와 summary에서 제외되며, cleanup 실패는 실행 실패다. 정상 종료에서는 k6가 만든 Valkey family가 남지 않는다. 강제 중단으로 cleanup이 실행되지 못하면 합성 계정 전체 로그인 종료 또는 환경 소유자가 승인한 Valkey 정리 절차로 잔존 family를 회수한 뒤 다음 실행을 허용한다.
- setup bearer의 15분 수명보다 짧게 끝내기 위해 duration을 최대 600초로 제한했다. 더 긴 시험은 token 회전 계약을 별도 설계한 뒤 수행한다.
- raw HTTP output, Token, cookie, cursor, 알림 제목과 자원 ID는 증거로 보관하지 않는다.

다음 local 실행은 `performance/k6/README.md`의 smoke 순서를 따르며, 성공한 `LOCAL_SMOKE_RUN_ID`와 그 run이 생성한 검증 가능한 JSON artifact 없이는 baseline 구성이 거부된다. staging 실행은 [#357](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/357)에서 배포 full SHA, clean checkout으로 검증한 harness full SHA, 합성 fixture, 공지 시간, 부하 상한, operator·observer, 저장소에서 리뷰한 trusted hostname, 외부 HTTP→HTTPS·API 도달·Actuator 차단 증거와 private backend health `UP` 증거, `STAGING_APPROVED=true`, `STAGING_HARNESS_SOURCE_VERIFIED=true`, 필요한 split-SHA 승인, #377 예외의 주입·제거 담당자, 성공한 `STAGING_SMOKE_RUN_ID`와 동일 실행 artifact가 모두 있을 때만 수행한다. `reservationCreate`는 별도 fixture 승인 전까지 제외한다. 실행 종료 뒤에는 같은 backend SHA의 복구 배포와 비식별 recovery summary까지 있어야 완료로 기록한다.

## 후속 이슈 연결

- [#356](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/356): 지오코딩 키의 범용 환경변수 전환과 staging backend 배선
- [#340](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/340): staging 실행 기간 한정 단일 IP 인증 rate-limit 예외와 복구 절차
- [#357](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/357): 승인된 staging 핵심 API k6 smoke·기준선
- [#358](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/358): 예약 `constant-arrival-rate` template 경계 계약
- [#359](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/359): Notification worker 시간대·기본 활성화 계약

현재 SQL 실행 시간, rows examined 또는 실행 계획 증거가 없으므로 [#286](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/286)에 병목을 주장하거나 인덱스 변경을 제안하지 않는다. 실제 local/staging 결과에서 쿼리 병목이 관찰된 경우에만 환경·commit·scenario·부하 입력과 함께 #286으로 연결하고, 다른 병목은 소유 도메인 Issue로 분리한다.
