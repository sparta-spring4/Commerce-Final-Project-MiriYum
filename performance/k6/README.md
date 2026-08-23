# k6 핵심 API 기준선

Issue #285의 인증·공개 검색·예약 생성·알림 이력 기준선을 로컬 Docker에서 먼저 검증하고, 같은 script commit과 fixture 계약을 승인된 staging에서 재실행한다. 이 결과는 최초 기준선이며 실서비스 SLO 판정값이 아니다.

## 안전 경계

- `TARGET_ENV`는 `local` 또는 `staging`만 허용한다. production hostname과 allowlist 밖 host는 HTTP 요청 전에 거부하며, local은 전용 HTTPS proxy 또는 loopback host만 허용한다.
- staging은 `STAGING_APPROVED=true`가 필요하다. `COMMIT_SHA`는 실제 배포 backend full SHA, `HARNESS_COMMIT_SHA`는 실행 중인 k6 script full SHA다. local은 둘이 반드시 같아야 하며 staging에서만 `STAGING_SPLIT_SHA_APPROVED=true`로 리뷰된 split SHA를 허용한다. staging은 아래 clean-checkout 검증을 통과한 뒤에만 `STAGING_HARNESS_SOURCE_VERIFIED=true`를 허용한다. local·staging baseline은 성공한 smoke의 run ID와 JSON artifact를 함께 요구하고, artifact의 profile·target·두 SHA·fixture·threshold를 현재 실행과 대조한다.
- staging의 reviewed hostname은 `staging-api.miriyum.click` 하나다. 외부 경계에서는 HTTP API 경로가 같은 HTTPS 경로로 `301` 전환되고 HTTPS API가 application-level `401` 또는 `405`로 backend까지 도달하며, 공개 `/actuator/health`는 의도대로 `404`로 차단되는지 확인한다. 실제 backend health `UP`은 공개 ingress가 아니라 배포·SSM이 `http://127.0.0.1:8080/actuator/health`에서 남긴 private 증거로 검증한다. 이 네 증거가 기록되기 전 staging k6는 `NOT RUN`이다.
- `MAX_VUS`와 `ARRIVAL_RATE`는 선택한 시나리오 전체에 배분되는 상한이다. 일반 `MAX_VUS` 상한은 100이지만 `authRefresh`를 선택하면 CSRF 준비 예산을 보존하도록 50 이하로 제한하며, duration은 setup bearer의 유효성을 보존하기 위해 최대 600초다.
- 실제 계정 비밀번호는 저장소 밖 환경 파일에서만 읽는다. Access/Refresh Token, cookie, cursor, 알림 제목, 응답 body와 자원 ID는 summary에 쓰지 않는다.
- `test-data.example.json`은 schema 예시이며 실행 가능한 데이터가 아니다. 실제 local fixture는 ignored `fixtures/test-data.local.json`, staging fixture는 저장소 밖 승인 경로를 사용한다.
- 기존 공개 API만으로 합성 계정·예약 슬롯·계정별 2페이지 이상의 전달 완료 알림을 준비할 수 없으면 실행을 중단하고 Issue #285를 `BLOCKED`로 보고한다. Repository 직접 seed나 production test endpoint를 추가하지 않는다.

## 실제 fixture 준비

`fixtures/test-data.example.json`을 `fixtures/test-data.local.json`으로 복사한 뒤 다음 조건을 실제 합성 데이터에 맞춘다.

- `auth.accountAliases`, 예약 template, `notification.accountAliases`가 서로 겹치지 않도록 분리한 합성 계정과 각 계정의 email/password 환경변수 이름
- baseline 인증 계정 풀은 전역 VU ID 충돌을 막기 위해 전체 `MAX_VUS` 이상이어야 하며, 한 VU는 실행 중 같은 계정으로 login과 refresh를 이어서 수행
- 공백이 아닌 1~100자 공개 검색 입력
- 예약별 account alias와 충돌하지 않는 store/date 조합. `performance/k6` 전용 fail-closed 계약으로 같은 account alias·store·date는 startTime, offset, party 또는 menu가 달라도 하나만 허용한다. 이는 운영 backend 정책을 바꾸지 않으며, 실제 운영에서는 서버가 계산한 서비스 구간이 겹치지 않는 같은 날 예약을 허용할 수 있다
- `notification.accountAliases`에 지정한 서로 다른 최소 2개 계정과, 각 계정의 `pageSize + 1`개 이상 공개 `IN_APP` 전달 완료 알림

예약 baseline은 `reservationCreate`에 배분된 `ARRIVAL_RATE × DURATION_SECONDS + 1`만큼 서로 충돌하지 않는 template이 필요하다. 마지막 `+ 1`은 duration 경계에서 executor가 예약할 수 있는 iteration guard이며, 예를 들어 예약 단독 `1 iteration/s × 30초`에는 31개가 필요하다. template을 순환 재사용하지 않으므로 부족하면 init context에서 실패한다.

비밀번호 값은 저장소 밖 파일, 예를 들어 `C:\secure\miriyum-k6.env`에 둔다.

```dotenv
K6_CONSUMER_01_EMAIL=synthetic-consumer-01@example.test
K6_CONSUMER_01_PASSWORD=replace-outside-the-repository
K6_CONSUMER_02_EMAIL=synthetic-consumer-02@example.test
K6_CONSUMER_02_PASSWORD=replace-outside-the-repository
K6_CONSUMER_03_EMAIL=synthetic-consumer-03@example.test
K6_CONSUMER_03_PASSWORD=replace-outside-the-repository
K6_CONSUMER_04_EMAIL=synthetic-consumer-04@example.test
K6_CONSUMER_04_PASSWORD=replace-outside-the-repository
K6_CONSUMER_05_EMAIL=synthetic-consumer-05@example.test
K6_CONSUMER_05_PASSWORD=replace-outside-the-repository
```

## 정적 계약 검사

저장소 루트에서 고정 이미지로 실행한다.

```powershell
$tests = @('config-contract.js', 'contracts-contract.js', 'recovery-rate-limit-contract.js', 'runtime-options-contract.js', 'scenario-contract.js', 'smoke-proof-contract.js', 'capacity-proof-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$test"
}
```

OpenAI 보완 검색과 대체 메뉴의 staging 품질·비용 검증은 별도
[`search-llm`](../../docs/performance/staging-llm-search-validation.md) 하네스를 사용한다.
이 하네스의 네 계약 테스트도 같은 고정 k6 이미지에서 실행하며 실제 OpenAI 호출은 하지 않는다.

```powershell
$tests = Get-ChildItem performance/k6/tests/search-llm-*-contract.js
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$($test.Name)"
}
```

`main.js`는 k6 CLI의 `-e`로 비밀이 아닌 init 값을 전달해 inspect한다.

```powershell
$commitSha = git rev-parse HEAD
$harnessCommitSha = $commitSha
docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 inspect `
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

## 로컬 smoke

기본 개발 stack은 `docker-compose.dev.yml`만 사용한다. 부하 테스트를 할 때만 별도 override와 `loadtest` profile을 함께 지정한다.

빈 로컬 DB에서 검색·예약 fixture용 합성 매장을 공개 API로 생성하려면 카카오 개발자 앱의 REST API 키를 `deploy/local/.env`의 `MIRIYUM_STORE_GEOCODING_REST_API_KEY`에 입력한다. 이 값은 loadtest override를 통해 backend에만 전달되며 k6 container, fixture, 결과에는 전달하지 않는다. 매장 준비가 끝난 뒤 측정하는 검색·예약 요청은 카카오 API를 직접 호출하지 않는다. 다른 시나리오만 실행할 때는 빈 값으로 둘 수 있다. Kakao OAuth의 `MIRIYUM_KAKAO_REST_API_KEY`와는 용도와 값을 분리한다.

```powershell
Copy-Item deploy/local/.env.example deploy/local/.env
New-Item -ItemType Directory -Force performance/k6/results
$credentialFile = 'C:\secure\miriyum-k6.env'
$commitSha = git rev-parse HEAD
$harnessCommitSha = $commitSha
$runId = 'local-smoke-20260814-01'

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest up -d mysql valkey backend loadtest-proxy

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=smoke `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.local.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  /scripts/main.js
```

smoke는 인증·검색·예약을 각각 1 iteration 실행하고, 알림은 최소 두 합성 계정을 한 번씩 조회한다. 전용 HTTPS proxy는 backend의 `Secure` refresh cookie 속성을 유지하면서 k6 cookie jar가 login 뒤 refresh에 cookie를 재전송하게 한다. 알림 이력은 요청 pageSize, cursor 패턴, item·resource·action·필수 전달 시각의 OpenAPI 값 계약과 실제 두 번째 페이지 항목까지 검증한다. 잘못된 fixture, 인증 실패, 응답 계약 위반, unexpected 4xx, 5xx 또는 비표준 status가 하나라도 있으면 baseline을 실행하지 않는다.

## 로컬 baseline

먼저 한 시나리오씩 작은 값으로 반복해 원인을 분리한다. 아래 값은 명령 예시일 뿐 승인된 목표가 아니다.

```powershell
$runId = 'local-search-baseline-20260814-01'
$localSmokeRunId = 'local-smoke-20260814-01'
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=local-baseline `
  -e LOCAL_SMOKE_RUN_ID=$localSmokeRunId `
  -e SMOKE_PROOF_PATH=/results/$localSmokeRunId.json `
  -e SCENARIOS=storeSearch `
  -e MAX_VUS=2 `
  -e DURATION_SECONDS=30 `
  -e ARRIVAL_RATE=2 `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.local.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$commitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  /scripts/main.js
```

`SCENARIOS`는 `authRefresh`, `storeSearch`, `reservationCreate`, `notificationHistory`의 쉼표 목록이며 생략하면 네 시나리오를 조합 실행한다. 조합 실행에서는 전체 `MAX_VUS`와 `ARRIVAL_RATE`를 시나리오 수에 정수 배분한다. `ARRIVAL_RATE`는 HTTP 요청 수가 아니라 iteration/s이다. 인증 iteration은 login·refresh 두 측정 요청 뒤 logout 정리 요청을 보내며 client cookie jar에 CSRF 토큰이 없을 때만 준비 요청을 한 번 추가한다. 예약 iteration은 생성 요청만 측정하고, 201 응답으로 생성된 합성 예약을 같은 iteration의 `phase=cleanup` 취소 요청으로 정리한다. k6는 `noCookiesReset=true`로 같은 VU의 cookie jar를 iteration 사이에 유지해 VU runtime의 CSRF 토큰 캐시와 수명을 맞추며, VU별 cookie jar 격리는 그대로 유지한다. 이 option을 제거하면 두 번째 iteration부터 CSRF header만 남아 logout cleanup이 403으로 실패한다. 알림 iteration은 두 페이지를 측정한다. `dropped_iterations`가 하나라도 생기면 해당 실행은 실패한다. 같은 입력으로 최소 두 번 실행하고 `results/{RUN_ID}.json`과 `.md`의 편차만 기록하되, 아래 IP rate-limit 창을 공유하는 반복 실행은 새 창에서 시작해야 한다.

backend 기본 IP rate limit은 login 성공 표본을 단일 source IP 기준 5회/600초로 제한한다. `docker-compose.loadtest.yml`은 local baseline에서만 login `600,000회/600초`, refresh `60,000회/60초`를 주입해 하네스 최대 `1,000 iterations/s × 600초`가 보호 기본값 때문에 잘리지 않게 한다. CSRF preparation은 별도 값을 주입하지 않고 backend 기본 예산을 상속하며, 이 예산이 `authRefresh`의 `AUTH_MAX_VUS` 상한을 정한다. 이 override를 사용한 결과는 순수 인증 지연시간·처리량 기준선이며 기본 rate-limit 보호 동작의 검증 결과가 아니다. override 없이 실행해 429가 섞인 `authRefresh` 결과는 p50/p95/p99 또는 #286 최적화 근거로 사용하지 않는다.

공개 매장 검색의 기본 한도도 source IP 기준 60회/60초지만 local loadtest override는 `60,000회/60초`를 주입한다. override를 사용하지 않는 실행에서는 위 `storeSearch` 예시의 `2 iterations/s × 30초`가 한 창의 60회를 모두 소비하므로 다음 실행은 이전 실행 종료 후 최소 60초를 기다린다. 어느 환경이든 예상 429가 발생한 결과는 검색 처리량 또는 p50/p95/p99 기준선으로 사용하지 않으며 #286에 전달하지 않는다.

`SMOKE_PROOF_PATH`는 바로 앞 smoke가 생성한 `/results/{SMOKE_RUN_ID}.json`을 가리켜야 한다. baseline init context는 artifact의 `schemaVersion`, `profile=smoke`, run ID, target environment와 fingerprint, backend·harness full SHA, fixture SHA-256, 실행 시나리오 포함 관계, 고정 smoke 상한, 전체 threshold 성공을 검증한다. 문자열 run ID만 전달하거나 다른 target·SHA·fixture의 artifact를 재사용하면 HTTP 요청 전에 실패한다.

## staging gate

다음 값이 PR 또는 팀 기록에서 모두 확인되지 않으면 staging 요청을 보내지 않는다.

1. 실제 배포된 backend full SHA(`COMMIT_SHA`)와 실행할 k6 harness full SHA(`HARNESS_COMMIT_SHA`)
2. 승인된 staging host allowlist와 합성 fixture 경로
3. 두 SHA가 다르면 별도 리뷰된 split-SHA 승인과 `STAGING_SPLIT_SHA_APPROVED=true`
4. harness checkout의 `HEAD`가 `HARNESS_COMMIT_SHA`이고 `performance/k6`의 tracked·staged·untracked 변경이 모두 없다는 검증
5. 팀 공지·실행 시간·최대 VU·duration·arrival rate, operator와 독립 observer/stop 담당 승인
6. `reservationCreate`는 배분된 arrival rate와 duration의 경계 guard까지 포함한 비충돌 template 수를 검토하고 `STAGING_RESERVATION_FIXTURE_APPROVED=true`로 승인
7. 두 SHA와 동일 fixture의 staging smoke 성공 `RUN_ID`

staging smoke 전에 승인된 harness checkout에서 다음을 실행한다. 출력이 하나라도 있거나 HEAD가 다르면 중단하며 `STAGING_HARNESS_SOURCE_VERIFIED`를 설정하지 않는다.

```powershell
$actualHarnessSha = (git rev-parse HEAD).Trim()
$harnessChanges = @(git status --porcelain=v1 --untracked-files=all -- performance/k6)
if ($actualHarnessSha -ne $harnessCommitSha -or $harnessChanges.Count -ne 0) {
  throw 'staging k6 requires a clean checkout at HARNESS_COMMIT_SHA'
}
$stagingHarnessSourceVerified = 'true'
```

staging smoke에는 `TARGET_ENV=staging`, HTTPS `BASE_URL`, `STAGING_APPROVED=true`, `STAGING_HARNESS_SOURCE_VERIFIED=true`, 두 full SHA를 전달한다. baseline에는 추가로 `PROFILE=staging-baseline`, `STAGING_SMOKE_RUN_ID`, `SMOKE_PROOF_PATH=/results/{STAGING_SMOKE_RUN_ID}.json`을 전달한다. `reservationCreate`를 포함하면 경계 guard까지 충족하는 비충돌 template을 준비하고 `STAGING_RESERVATION_FIXTURE_APPROVED=true`를 전달해야 하며, 승인되지 않았으면 요청 전에 실패한다. 신뢰 staging host는 `staging-api.miriyum.click` 하나이며, 위 외부 TLS·API 도달·Actuator 차단과 private backend health `UP` 증거 전에는 `NOT RUN`으로 유지한다. production hostname, 실사용자 계정 또는 운영 데이터는 어떤 값으로도 실행하지 않는다.

## staging 단일 task 용량 측정

Issue #567의 용량 측정은 smoke·baseline 성공 뒤 `PROFILE=staging-capacity`로 실행한다. `ARRIVAL_RATE`는 iteration/s이고 `CAPACITY_TARGET_RPS`는 승인된 계획값을 결과에 연결하는 메타데이터다. 실제 HTTP RPS는 결과 JSON의 scenario별 `httpRequests.rate`로 판단한다. backend task 수를 1개로 고정했다는 배포 증거와 CloudWatch CPU·메모리 측정은 k6가 만들지 않으며, 동일 run ID와 시간 범위로 [`docs/performance/staging-backend-capacity.md`](../../docs/performance/staging-backend-capacity.md)에 연결한다.

1단계는 동일 target·backend SHA·harness SHA·fixture의 성공한 staging smoke artifact가 필요하다. 2단계부터는 바로 이전 단계의 성공 JSON도 `CAPACITY_PREVIOUS_PROOF_PATH`로 전달한다. 이전 artifact의 target fingerprint, 두 SHA, fixture fingerprint, profile, threshold, 단계 번호와 시나리오 구성이 일치하지 않으면 요청 전에 중단한다. 또한 이전 단계의 scenario별 실제 `httpRequests.rate` 합계가 해당 artifact의 `capacity.targetRps`보다 작으면 다음 단계로 승격하지 않는다. `storeSearch`를 선택하면 실제 OpenAI 비호출 상태를 배포 설정과 CloudWatch `miriyum.search.llm.calls` 증가량 0으로 검증하고 `CAPACITY_LLM_DISABLED_CONFIRMED=true`를 전달해야 한다.

```powershell
$runId = 'staging-capacity-stage-01-YYYYMMDD-NN'
docker run --rm `
  --env-file $credentialFile `
  -v "${PWD}/performance/k6:/scripts:ro" `
  -v "${PWD}/performance/k6/results:/results" `
  grafana/k6:2.1.0 run `
  -e TARGET_ENV=staging `
  -e BASE_URL=https://staging-api.miriyum.click `
  -e ALLOWED_HOSTS=staging-api.miriyum.click `
  -e PROFILE=staging-capacity `
  -e STAGING_APPROVED=true `
  -e STAGING_HARNESS_SOURCE_VERIFIED=true `
  -e CAPACITY_TEST_APPROVED=true `
  -e CAPACITY_STAGE_NUMBER=1 `
  -e CAPACITY_TARGET_RPS=10 `
  -e CAPACITY_LLM_DISABLED_CONFIRMED=true `
  -e STAGING_SMOKE_RUN_ID=$stagingSmokeRunId `
  -e SMOKE_PROOF_PATH=/results/$stagingSmokeRunId.json `
  -e SCENARIOS=storeSearch `
  -e MAX_VUS=10 `
  -e DURATION_SECONDS=60 `
  -e ARRIVAL_RATE=10 `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.staging.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$deployedCommitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  -e STAGING_SPLIT_SHA_APPROVED=$splitShaApproved `
  /scripts/main.js
```

2단계 이상은 `CAPACITY_STAGE_NUMBER`를 하나씩 올리고 `-e CAPACITY_PREVIOUS_PROOF_PATH=/results/{직전 RUN_ID}.json`을 추가한다. 단계 사이에는 p95, unexpected 4xx/5xx, dropped iterations, task CPU·메모리를 검토하고 사전 합의한 중단 조건을 넘으면 다음 단계를 실행하지 않는다. production에는 실행하지 않는다.

### 예외 제거와 기본 429 복구

성공·실패·중단과 무관하게 [`docs/deployment/docker-ecr-ssm-cd.md`](../../docs/deployment/docker-ecr-ssm-cd.md)의 절차로 `Backend CD (Staging)`을 같은 `COMMIT_SHA`와 `rate_limit_exception=disable`로 다시 실행한다. backend container에서 값이 비었음을 환경 원문을 출력하지 않는 존재 여부 검사로 확인하고, 직전 로그인 창이 남아 있지 않은 새 600초 창에서만 아래 verifier를 실행한다. 자격증명 파일에는 `K6_RECOVERY_EMAIL`과 `K6_RECOVERY_PASSWORD`만 두며 저장소 밖에서 읽는다.

```powershell
$recoveryRunId = 'staging-rate-limit-recovery-YYYYMMDD-NN'
docker run --rm `
  --env-file $credentialFile `
  -v "${PWD}/performance/k6:/scripts:ro" `
  -v "${PWD}/performance/k6/results:/results" `
  grafana/k6:2.1.0 run `
  -e TARGET_ENV=staging `
  -e BASE_URL=$approvedStagingBaseUrl `
  -e ALLOWED_HOSTS=$approvedStagingHost `
  -e STAGING_APPROVED=true `
  -e STAGING_HARNESS_SOURCE_VERIFIED=true `
  -e RECOVERY_VERIFICATION_APPROVED=true `
  -e RATE_LIMIT_EXCEPTION_REMOVED=true `
  -e RATE_LIMIT_WINDOW_CONFIRMED=true `
  -e COMMIT_SHA=$deployedCommitSha `
  -e HARNESS_COMMIT_SHA=$harnessCommitSha `
  -e STAGING_SPLIT_SHA_APPROVED=$splitShaApproved `
  -e RUN_ID=$recoveryRunId `
  /scripts/recovery-rate-limit.js
```

verifier는 단일 VU·단일 iteration으로 순차 실행한다. 로그인 5회가 모두 성공하고 각 session이 logout된 뒤 6번째 로그인만 정확히 `429`여야 성공한다. summary에는 두 SHA, 성공 횟수 `5`, 최종 상태 `429`, threshold 결과만 남으며 실제 IP·email·password·Token·cookie·Authorization header·요청/응답 원문은 남기지 않는다. `--http-debug`를 사용하지 않는다.

## 종료와 결과 취급

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest down
```

기존 MySQL 개발 데이터를 임의로 지우지 않도록 `down -v`는 사용하지 않는다. 결과 문서에는 환경 사양, commit SHA, 입력 부하, scenario별 p50/p95/p99·RPS·expected 4xx·unexpected 4xx·5xx와 실행하지 못한 항목만 남긴다. raw HTTP output과 식별 가능한 생성 자원은 보관하지 않는다.

인증 시나리오는 성공한 로그인마다 같은 cookie jar에서 CSRF 토큰을 준비하고 현재 session을 logout한다. CSRF 토큰과 쿠키는 client별로 재사용해 IP당 준비 요청 제한을 iteration 수만큼 소비하지 않는다. setup에서 Reservation·Notification용 Access Token을 준비하는 로그인도 Access Token을 반환하기 전에 Refresh Token family를 같은 방식으로 회수한다. 정리 요청은 `phase=cleanup`으로 태그되어 측정 threshold와 summary에서 제외되지만, CSRF 준비나 logout이 실패하면 해당 iteration 또는 setup은 실패한다. 따라서 정상 종료한 실행은 k6가 만든 Refresh Token family를 Valkey에 남기지 않는다. 강제 중단·프로세스 종료처럼 cleanup 요청 자체가 실행되지 못한 경우에는 합성 계정의 전체 로그인 종료 또는 해당 환경 소유자가 승인한 Valkey 정리 절차로 잔존 family를 회수한 뒤 다시 실행한다.

예약 시나리오는 생성된 `reservationId`를 결과 artifact에 노출하지 않고 같은 Access Token과 별도 deterministic idempotency key로 소비자 취소를 수행한다. 취소 응답은 HTTP 200, `status=CANCELLED`, `cancelledBy=CONSUMER` 계약을 모두 만족해야 하며 실패하면 해당 iteration을 실패시킨다. 생성 응답의 전체 계약이 잘못됐더라도 공개 ID를 안전하게 추출할 수 있으면 먼저 취소한 뒤 계약 실패를 보고한다. 강제 중단처럼 취소 요청이 실행되지 못한 경우에는 합성 fixture 소유자가 잔존 예약을 정리한 뒤 재실행한다.

## SSE 변경 신호 부하 하네스

SSE는 기존 HTTP 기준선과 실행기를 공유하지 않는다. `xk6-sse v0.1.12`가 k6 v2 자동 확장 registry에 없으므로 `sse-loadtest`는 `grafana/xk6:1.4.11`로 `k6 v1.2.2`와 확장을 고정 빌드한다. 기존 `loadtest`는 계속 `grafana/k6:2.1.0`을 사용한다.

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

SSE fixture는 계정 환경변수 이름과 공개 example ID만 보관하며 inline credential을 거절한다. `smoke`, `reconnect`, `steady`, `slow-client`, `capacity`, `recovery` 프로필과 세 endpoint kind를 허용한다. 일반 프로필은 전체 연결 200·계정별 연결 6·유지 시간 600초를 넘을 수 없고, `capacity`만 단일 endpoint/계정에 7개 연결을 만들어 6개 상한 초과의 예상 429 1건을 검증한다. `steady`·`reconnect`·`slow-client`·`capacity`는 소유 HTTP probe(`SSE_HTTP_PROBE_RATE`, `SSE_HTTP_MAX_P95_RATIO`)를 병행한다. `slow-client`는 4 KiB TCP receive buffer를 가진 전용 `sse-slow-loadtest`에서 최초 changed frame 뒤 31~60초 범위로 한 번만 수신을 중단하고, burst heartbeat 환경에서 별도 정상 companion보다 먼저 서버 주도로 정리돼야 한다. 정확히 하나인 companion의 최초 changed callback이 후속 Waiting 변경을 실행하므로 고정 시간 대기 경쟁 조건 없이 준비 순서를 보장한다. slow 역할은 최초 changed frame과 실제 pause를, companion은 후속 변경의 두 번째 changed frame을 검증한다. `SSE_SLOW_CLIENT_MAX_CLEANUP_SECONDS < SSE_COMPANION_MIN_LIFETIME_SECONDS < SSE_HOLD_DURATION_SECONDS` 순서를 강제하며, 명시적 승인과 UUID `SSE_SLOW_CLIENT_IDEMPOTENCY_KEY`가 있어야 후속 Waiting 변경을 만든다. smoke 이후 프로필은 같은 target fingerprint, fixture SHA-256, backend·harness full SHA와 endpoint coverage를 가진 성공 JSON artifact를 요구한다.

`recovery` setup은 Valkey 장애 주입을 준비하기 전에 owner API에서 유효한 `WAITING` 팀이 있는지 읽기 전용으로 확인한다. 목록 요청·응답 계약이 실패하거나 팀이 없으면 `SSE_RECOVERY_READY`를 출력하지 않고 종료한다. 성공한 recovery의 cleanup은 대상 팀을 terminal `CANCELLED`로 만들므로 같은 팀을 다음 실행에 재사용할 수 없다. 재실행 전에는 새 합성 `WAITING` 팀을 준비해야 한다. fixture SHA-256은 정적 fixture 파일의 동일성만 증명하며, 변하는 staging DB 상태나 WAITING 팀 존재를 증명하지 않는다. summary는 목록 요청·fixture·call 요청·call 응답 계약 실패를 고정된 aggregate 건수로만 구분한다.

local `recovery`는 승인된 고정 arm delay를 사용한다. staging `recovery`는 fixed delay를 허용하지 않고 `SSE_RECOVERY_RENDEZVOUS_APPROVED=true`와 정확한 repository·Issue·GitHub Actions run ID·일회성 UUID를 요구한다. 준비된 workflow가 dispatch actor의 정확한 `SSE_RECOVERY_FIRE` 코멘트를 받은 뒤 가까운 미래의 Valkey 중단 epoch를 예약하며, 하네스는 `github-actions[bot]`이 남긴 같은 scope의 `SSE_RECOVERY_ARMED` 코멘트만 공개 GitHub API에서 인증 header 없이 조회한다. marker가 없거나 scope·author·시각이 맞지 않으면 Waiting mutation 전에 실패한다. marker 본문과 rendezvous 식별자는 summary에 포함하지 않는다.

실행·Valkey 중단·backend 교체·롤백 명령은 [SSE Runtime runbook](../../docs/deployment/sse-runtime-runbook.md), 실제 상태와 비식별 aggregate는 [SSE Runtime 검증 기록](../../docs/performance/sse-runtime-validation.md)에만 기록한다. summary는 `miriyum-k6-sse-summary-v1` allowlist 밖 metadata와 metric을 버리고 Token, cursor, ID, email, URL 또는 응답 원문을 직렬화하지 않는다.
