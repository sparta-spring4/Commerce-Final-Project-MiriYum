# k6 핵심 API 기준선

Issue #285의 인증·공개 검색·예약 생성·알림 이력 기준선을 로컬 Docker에서 먼저 검증하고, 같은 script commit과 fixture 계약을 승인된 staging에서 재실행한다. 이 결과는 최초 기준선이며 실서비스 SLO 판정값이 아니다.

## 안전 경계

- `TARGET_ENV`는 `local` 또는 `staging`만 허용한다. production hostname과 allowlist 밖 host는 HTTP 요청 전에 거부하며, local은 전용 HTTPS proxy 또는 loopback host만 허용한다.
- staging은 `STAGING_APPROVED=true`가 필요하다. local·staging baseline은 성공한 smoke의 run ID와 JSON artifact를 함께 요구하고, artifact의 profile·target·commit·fixture·threshold를 현재 실행과 대조한다.
- staging host는 저장소의 신뢰 allowlist가 비어 있는 동안 fail-closed다. 실제 host는 별도 리뷰 변경으로 먼저 고정해야 한다.
- `MAX_VUS`와 `ARRIVAL_RATE`는 선택한 시나리오 전체에 배분되는 상한이다. duration은 setup bearer의 유효성을 보존하기 위해 최대 600초다.
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
$tests = @('config-contract.js', 'contracts-contract.js', 'scenario-contract.js', 'smoke-proof-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$test"
}
```

`main.js`는 k6 CLI의 `-e`로 비밀이 아닌 init 값을 전달해 inspect한다.

```powershell
$commitSha = git rev-parse HEAD
docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 inspect `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=smoke `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.example.json `
  -e RUN_ID=local-inspect `
  -e COMMIT_SHA=$commitSha `
  /scripts/main.js
```

## 로컬 smoke

기본 개발 stack은 `docker-compose.dev.yml`만 사용한다. 부하 테스트를 할 때만 별도 override와 `loadtest` profile을 함께 지정한다.

```powershell
Copy-Item deploy/local/.env.example deploy/local/.env
New-Item -ItemType Directory -Force performance/k6/results
$credentialFile = 'C:\secure\miriyum-k6.env'
$commitSha = git rev-parse HEAD
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
  /scripts/main.js
```

`SCENARIOS`는 `authRefresh`, `storeSearch`, `reservationCreate`, `notificationHistory`의 쉼표 목록이며 생략하면 네 시나리오를 조합 실행한다. 조합 실행에서는 전체 `MAX_VUS`와 `ARRIVAL_RATE`를 시나리오 수에 정수 배분한다. `ARRIVAL_RATE`는 HTTP 요청 수가 아니라 iteration/s이며 인증과 알림 iteration은 최대 두 요청을 보낸다. `dropped_iterations`가 하나라도 생기면 해당 실행은 실패한다. 같은 입력으로 최소 두 번 실행하고 `results/{RUN_ID}.json`과 `.md`의 편차만 기록한다.

`SMOKE_PROOF_PATH`는 바로 앞 smoke가 생성한 `/results/{SMOKE_RUN_ID}.json`을 가리켜야 한다. baseline init context는 artifact의 `schemaVersion`, `profile=smoke`, run ID, target environment와 fingerprint, full commit SHA, fixture SHA-256, 실행 시나리오 포함 관계, 고정 smoke 상한, 전체 threshold 성공을 검증한다. 문자열 run ID만 전달하거나 다른 target·commit·fixture의 artifact를 재사용하면 HTTP 요청 전에 실패한다.

## staging gate

다음 값이 PR 또는 팀 기록에서 모두 확인되지 않으면 staging 요청을 보내지 않는다.

1. 실제 배포된 full commit SHA
2. 승인된 staging host allowlist와 합성 fixture 경로
3. 팀 공지·실행 시간·최대 VU·duration·arrival rate 승인
4. 같은 SHA의 staging smoke 성공 `RUN_ID`

staging smoke에는 `TARGET_ENV=staging`, HTTPS `BASE_URL`, `STAGING_APPROVED=true`를 전달한다. baseline에는 추가로 `PROFILE=staging-baseline`, `STAGING_SMOKE_RUN_ID`, `SMOKE_PROOF_PATH=/results/{STAGING_SMOKE_RUN_ID}.json`을 전달한다. 현재 신뢰 staging host allowlist는 의도적으로 비어 있으므로 승인된 hostname을 저장소 변경으로 먼저 고정하기 전에는 실행되지 않는다. production hostname, 실사용자 계정 또는 운영 데이터는 어떤 값으로도 실행하지 않는다.

## 종료와 결과 취급

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest down
```

기존 MySQL 개발 데이터를 임의로 지우지 않도록 `down -v`는 사용하지 않는다. 결과 문서에는 환경 사양, commit SHA, 입력 부하, scenario별 p50/p95/p99·RPS·expected 4xx·unexpected 4xx·5xx와 실행하지 못한 항목만 남긴다. raw HTTP output과 식별 가능한 생성 자원은 보관하지 않는다.
