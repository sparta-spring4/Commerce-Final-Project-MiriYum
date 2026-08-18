# k6 핵심 API 기준선

Issue #285의 인증·공개 검색·예약 생성·알림 이력 기준선을 로컬 Docker에서 먼저 검증하고, 같은 script commit과 fixture 계약을 승인된 staging에서 재실행한다. 이 결과는 최초 기준선이며 실서비스 SLO 판정값이 아니다.

## 안전 경계

- `TARGET_ENV`는 `local` 또는 `staging`만 허용한다. production hostname과 allowlist 밖 host는 HTTP 요청 전에 거부하며, local은 전용 HTTPS proxy 또는 loopback host만 허용한다.
- staging은 `STAGING_APPROVED=true`가 필요하다. `COMMIT_SHA`는 실제 배포 backend full SHA, `HARNESS_COMMIT_SHA`는 실행 중인 k6 script full SHA다. local은 둘이 반드시 같아야 하며 staging에서만 `STAGING_SPLIT_SHA_APPROVED=true`로 리뷰된 split SHA를 허용한다. staging은 아래 clean-checkout 검증을 통과한 뒤에만 `STAGING_HARNESS_SOURCE_VERIFIED=true`를 허용한다. local·staging baseline은 성공한 smoke의 run ID와 JSON artifact를 함께 요구하고, artifact의 profile·target·두 SHA·fixture·threshold를 현재 실행과 대조한다.
- staging의 reviewed hostname은 `staging-api.miriyum.click` 하나다. `https://staging-api.miriyum.click/actuator/health` HTTPS smoke 성공 증거가 기록되기 전 staging k6는 `NOT RUN`이며, 이 전제 없이 실행하지 않는다.
- `MAX_VUS`와 `ARRIVAL_RATE`는 선택한 시나리오 전체에 배분되는 상한이다. 일반 `MAX_VUS` 상한은 100이지만 `authRefresh`를 선택하면 CSRF 준비 예산을 보존하도록 50 이하로 제한하며, duration은 setup bearer의 유효성을 보존하기 위해 최대 600초다.
- 실제 계정 비밀번호는 저장소 밖 환경 파일에서만 읽는다. Access/Refresh Token, cookie, cursor, 알림 제목, 응답 body와 자원 ID는 summary에 쓰지 않는다.
- `test-data.example.json`은 schema 예시이며 실행 가능한 데이터가 아니다. 실제 local fixture는 ignored `fixtures/test-data.local.json`, staging fixture는 저장소 밖 승인 경로를 사용한다.
- 기존 공개 API만으로 합성 계정·예약 슬롯·계정별 2페이지 이상의 전달 완료 알림을 준비할 수 없으면 실행을 중단하고 Issue #285를 `BLOCKED`로 보고한다. Repository 직접 seed나 production test endpoint를 추가하지 않는다.

## 실제 fixture 준비

`fixtures/test-data.example.json`을 `fixtures/test-data.local.json`으로 복사한 뒤 다음 조건을 실제 합성 데이터에 맞춘다.

- `auth.accountAliases`, 예약 template, `notification.accountAliases`가 서로 겹치지 않도록 분리한 합성 계정과 각 계정의 email/password 환경변수 이름
- baseline 인증 계정 풀은 전역 VU ID 충돌을 막기 위해 전체 `MAX_VUS` 이상이어야 하며, 한 VU는 실행 중 같은 계정으로 login과 refresh를 이어서 수행
- 공백이 아닌 1~100자 공개 검색 입력
- 예약별 account alias, 충돌하지 않는 store/date/time/party/menu 조합
- `notification.accountAliases`에 지정한 서로 다른 최소 2개 계정과, 각 계정의 `pageSize + 1`개 이상 공개 `IN_APP` 전달 완료 알림

예약 baseline은 `reservationCreate`에 배분된 `ARRIVAL_RATE × DURATION_SECONDS`만큼 서로 충돌하지 않는 template이 필요하다. template을 순환 재사용하지 않으므로 부족하면 init context에서 실패한다.

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
$tests = @('config-contract.js', 'contracts-contract.js', 'recovery-rate-limit-contract.js', 'runtime-options-contract.js', 'scenario-contract.js', 'smoke-proof-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$test"
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

`SCENARIOS`는 `authRefresh`, `storeSearch`, `reservationCreate`, `notificationHistory`의 쉼표 목록이며 생략하면 네 시나리오를 조합 실행한다. 조합 실행에서는 전체 `MAX_VUS`와 `ARRIVAL_RATE`를 시나리오 수에 정수 배분한다. `ARRIVAL_RATE`는 HTTP 요청 수가 아니라 iteration/s이다. 인증 iteration은 login·refresh 두 측정 요청 뒤 logout 정리 요청을 보내며 client cookie jar에 CSRF 토큰이 없을 때만 준비 요청을 한 번 추가한다. k6는 `noCookiesReset=true`로 같은 VU의 cookie jar를 iteration 사이에 유지해 VU runtime의 CSRF 토큰 캐시와 수명을 맞추며, VU별 cookie jar 격리는 그대로 유지한다. 이 option을 제거하면 두 번째 iteration부터 CSRF header만 남아 logout cleanup이 403으로 실패한다. 알림 iteration은 두 페이지를 측정한다. `dropped_iterations`가 하나라도 생기면 해당 실행은 실패한다. 같은 입력으로 최소 두 번 실행하고 `results/{RUN_ID}.json`과 `.md`의 편차만 기록하되, 아래 IP rate-limit 창을 공유하는 반복 실행은 새 창에서 시작해야 한다.

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
6. `reservationCreate`는 #358이 `dev`에 병합된 뒤에만 `STAGING_RESERVATION_FIXTURE_APPROVED=true`; 그전에는 `SCENARIOS`에서 명시적으로 제외
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

staging smoke에는 `TARGET_ENV=staging`, HTTPS `BASE_URL`, `STAGING_APPROVED=true`, `STAGING_HARNESS_SOURCE_VERIFIED=true`, 두 full SHA를 전달한다. baseline에는 추가로 `PROFILE=staging-baseline`, `STAGING_SMOKE_RUN_ID`, `SMOKE_PROOF_PATH=/results/{STAGING_SMOKE_RUN_ID}.json`을 전달한다. #358 전에는 `SCENARIOS=authRefresh,storeSearch,notificationHistory`처럼 예약을 명시적으로 제외해야 하며, 생략해 네 시나리오 기본값을 선택하면 요청 전에 실패한다. 신뢰 staging host는 `staging-api.miriyum.click` 하나이며, HTTPS health smoke 성공 증거 전에는 `NOT RUN`으로 유지한다. production hostname, 실사용자 계정 또는 운영 데이터는 어떤 값으로도 실행하지 않는다.

### 예외 제거와 기본 429 복구

성공·실패·중단과 무관하게 [`docs/deployment/docker-ecr-ssm-cd.md`](../../docs/deployment/docker-ecr-ssm-cd.md)의 절차로 `MIRIYUM_STAGING_LOAD_TEST_SOURCE_IP` 값을 비우고 같은 `COMMIT_SHA`를 다시 배포한다. backend container에서 값이 비었음을 환경 원문을 출력하지 않는 존재 여부 검사로 확인하고, 직전 로그인 창이 남아 있지 않은 새 600초 창에서만 아래 verifier를 실행한다. 자격증명 파일에는 `K6_RECOVERY_EMAIL`과 `K6_RECOVERY_PASSWORD`만 두며 저장소 밖에서 읽는다.

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
