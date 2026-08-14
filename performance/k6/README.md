# k6 핵심 API 기준선

Issue #285의 인증·공개 검색·예약 생성·알림 이력 기준선을 로컬 Docker에서 먼저 검증하고, 같은 script commit과 fixture 계약을 승인된 staging에서 재실행한다. 이 결과는 최초 기준선이며 실서비스 SLO 판정값이 아니다.

## 안전 경계

- `TARGET_ENV`는 `local` 또는 `staging`만 허용한다. production hostname과 allowlist 밖 host는 HTTP 요청 전에 거부한다.
- staging은 `STAGING_APPROVED=true`가 필요하고, baseline은 성공한 smoke의 `STAGING_SMOKE_RUN_ID`도 요구한다.
- `MAX_VUS`와 `ARRIVAL_RATE`는 선택한 시나리오 전체에 배분되는 상한이다. duration은 setup bearer의 유효성을 보존하기 위해 최대 600초다.
- 실제 계정 비밀번호는 저장소 밖 환경 파일에서만 읽는다. Access/Refresh Token, cookie, cursor, 알림 제목, 응답 body와 자원 ID는 summary에 쓰지 않는다.
- `test-data.example.json`은 schema 예시이며 실행 가능한 데이터가 아니다. 실제 local fixture는 ignored `fixtures/test-data.local.json`, staging fixture는 저장소 밖 승인 경로를 사용한다.
- 기존 공개 API만으로 합성 계정·예약 슬롯·계정별 2페이지 이상의 전달 완료 알림을 준비할 수 없으면 실행을 중단하고 Issue #285를 `BLOCKED`로 보고한다. Repository 직접 seed나 production test endpoint를 추가하지 않는다.

## 실제 fixture 준비

`fixtures/test-data.example.json`을 `fixtures/test-data.local.json`으로 복사한 뒤 다음 조건을 실제 합성 데이터에 맞춘다.

- 최소 2개 계정 alias와 각 계정의 email/password 환경변수 이름
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
```

## 정적 계약 검사

저장소 루트에서 고정 이미지로 실행한다.

```powershell
$tests = @('config-contract.js', 'contracts-contract.js', 'scenario-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$test"
}
```

`main.js`는 k6 CLI의 `-e`로 비밀이 아닌 init 값을 전달해 inspect한다.

```powershell
$commitSha = git rev-parse HEAD
docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 inspect `
  -e TARGET_ENV=local `
  -e BASE_URL=http://backend:8080 `
  -e ALLOWED_HOSTS=backend `
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
  --profile loadtest up -d mysql valkey backend

docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=http://backend:8080 `
  -e ALLOWED_HOSTS=backend `
  -e PROFILE=smoke `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.local.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$commitSha `
  /scripts/main.js
```

smoke는 인증·검색·예약을 각각 1 iteration 실행하고, 알림은 최소 두 합성 계정을 한 번씩 조회한다. 잘못된 fixture, 인증 실패, 응답 계약 위반, unexpected 4xx, 5xx 또는 비표준 status가 하나라도 있으면 baseline을 실행하지 않는다.

## 로컬 baseline

먼저 한 시나리오씩 작은 값으로 반복해 원인을 분리한다. 아래 값은 명령 예시일 뿐 승인된 목표가 아니다.

```powershell
$runId = 'local-search-baseline-20260814-01'
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest run --rm --env-from-file $credentialFile loadtest run `
  -e TARGET_ENV=local `
  -e BASE_URL=http://backend:8080 `
  -e ALLOWED_HOSTS=backend `
  -e PROFILE=local-baseline `
  -e SCENARIOS=storeSearch `
  -e MAX_VUS=2 `
  -e DURATION_SECONDS=30 `
  -e ARRIVAL_RATE=2 `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.local.json `
  -e RUN_ID=$runId `
  -e COMMIT_SHA=$commitSha `
  /scripts/main.js
```

`SCENARIOS`는 `authRefresh`, `storeSearch`, `reservationCreate`, `notificationHistory`의 쉼표 목록이며 생략하면 네 시나리오를 조합 실행한다. 조합 실행에서는 전체 `MAX_VUS`와 `ARRIVAL_RATE`를 시나리오 수에 정수 배분한다. 같은 입력으로 최소 두 번 실행하고 `results/{RUN_ID}.json`과 `.md`의 편차만 기록한다.

## staging gate

다음 값이 PR 또는 팀 기록에서 모두 확인되지 않으면 staging 요청을 보내지 않는다.

1. 실제 배포된 full commit SHA
2. 승인된 staging host allowlist와 합성 fixture 경로
3. 팀 공지·실행 시간·최대 VU·duration·arrival rate 승인
4. 같은 SHA의 staging smoke 성공 `RUN_ID`

staging smoke에는 `TARGET_ENV=staging`, HTTPS `BASE_URL`, `STAGING_APPROVED=true`를 전달한다. baseline에는 추가로 `PROFILE=staging-baseline`과 `STAGING_SMOKE_RUN_ID`를 전달한다. production hostname, 실사용자 계정 또는 운영 데이터는 어떤 값으로도 실행하지 않는다.

## 종료와 결과 취급

```powershell
docker compose --env-file deploy/local/.env `
  -f deploy/local/docker-compose.dev.yml `
  -f deploy/local/docker-compose.loadtest.yml `
  --profile loadtest down
```

기존 MySQL 개발 데이터를 임의로 지우지 않도록 `down -v`는 사용하지 않는다. 결과 문서에는 환경 사양, commit SHA, 입력 부하, scenario별 p50/p95/p99·RPS·expected 4xx·unexpected 4xx·5xx와 실행하지 못한 항목만 남긴다. raw HTTP output과 식별 가능한 생성 자원은 보관하지 않는다.
