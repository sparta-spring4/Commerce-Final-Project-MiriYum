# 핵심 API k6 기준선

- 소유 Issue: [#285](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/285)
- 기록일: 2026-08-14
- 기준 `dev`: `bb46e4e140d964a53d19d1ab97ce90a300551a9f`
- 검증 script commit: `012caa479ad64a128ead70595d59a621f8b9314c`
- 단계: 고도화
- 해석: 최초 환경별 기준선을 수집하기 위한 harness 증거이며 실서비스 SLO 판정이 아니다.

## 실행 상태

| 구간 | 상태 | 관찰 결과 |
|---|---|---|
| k6 계약 테스트 | PASS | 고정 k6 이미지에서 config 21, 공통 계약 27, scenario 27, smoke proof 8, summary 6 checks가 모두 성공했다. |
| k6 계약 CI workflow | PASS | `rhysd/actionlint:1.7.7`이 path-filtered workflow를 오류 없이 검증했으며 workflow는 `--network none`으로 외부·local API 접근을 차단한 고정 k6 이미지에서 계약 테스트만 실행한다. |
| k6 smoke profile inspect | PASS | 인증 1 iteration, 검색 1, 예약 1, 알림은 명시한 2개 합성 계정에 대해 2 iterations로 해석됐다. |
| k6 local-baseline profile inspect | PASS | 동일 target·commit·fixture의 smoke artifact를 전달했을 때 `storeSearch`, 1 VU·1 arrival/s·10초가 하나의 constant-arrival-rate executor로 해석됐다. commit이 다른 artifact는 init context에서 요청 전에 거부됐다. |
| k6 Secure cookie TLS probe | PASS | 고정 k6 이미지의 실제 VU cookie jar가 Caddy 내부 TLS를 거쳐 mock login의 `Secure` refresh cookie를 다음 refresh 요청에 재전송했다. |
| k6 mock runtime summary | NOT RUN | 응답 검증과 summary schema가 변경된 현재 script commit에서는 네트워크 mock을 반복하지 않았다. summary 입력·비식별 출력은 고정 k6 계약 테스트로 검증했고 실제 backend smoke는 계속 `NOT CONFIGURED`다. |
| Local Compose 합성 | PASS | load-test override 사용 시 `mysql`, `valkey`, `backend`, `loadtest-proxy`, `loadtest`; 기본 Compose 단독 사용 시 기존 `mysql`, `backend`만 존재했다. |
| Backend 단위 테스트 | PASS | 최종 브랜치 상태에서 backend 작업 디렉터리의 `.\gradlew.bat test`가 exit 0이었다. |
| Backend assemble | PASS | `.\gradlew.bat assemble`이 compile·bootJar·jar를 포함해 exit 0이었다. |
| Backend 통합 테스트 | FAIL | 전체 task와 shard A가 Testcontainers JDBC readiness/context 전환 중 각각 15분·10분 안에 종료되지 않았다. |
| Backend build | NOT RUN | `build`가 실패한 통합 gate에 의존하므로 동일 장시간 실행을 반복하지 않았다. PR의 공식 A~D CI shard 성공이 필요하다. |
| Local smoke·baseline | NOT CONFIGURED | ignored `performance/k6/fixtures/test-data.local.json`과 `deploy/local/.env`가 없어 요청을 보내지 않았다. |
| Staging smoke·baseline | NOT CONFIGURED | 승인된 시간·부하 상한·합성 fixture·배포 SHA·smoke run ID와 저장소에서 리뷰한 staging hostname allowlist가 없어 요청을 보내지 않았다. |

`NOT CONFIGURED`는 성공이 아니다. 시나리오별로 격리한 실제 합성 계정, 서로 충돌하지 않는 예약 template, 계정별 2페이지 이상의 공개 `IN_APP` 전달 완료 알림이 준비되기 전에는 local 또는 staging 지연시간·처리량을 추정하지 않는다.

## 실행 환경

| 항목 | 실제 값 |
|---|---|
| Docker Client | `29.4.3` |
| Docker Compose | `v5.1.3` |
| k6 image | `grafana/k6:2.1.0` |
| k6 image digest | `sha256:65c920dc067d5e2e00befbf982af6ad6ad0117034e8b1c65817c7975c52d4669` |
| Caddy image | `caddy:2.10.2-alpine` |
| Caddy image digest | `sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d` |
| Local DB·Valkey·backend runtime | 실행하지 않음 |
| Staging runtime·인스턴스 사양 | 확인되지 않음 |

## 실제 명령과 결과

### k6 계약 테스트

```powershell
$tests = @('config-contract.js', 'contracts-contract.js', 'scenario-contract.js', 'smoke-proof-contract.js', 'summary-contract.js')
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run --quiet "/scripts/tests/$test"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

관찰 결과:

- `config-contract.js`: 21/21 checks 성공
- `contracts-contract.js`: 27/27 checks 성공
- `scenario-contract.js`: 27/27 checks 성공
- `smoke-proof-contract.js`: 8/8 checks 성공
- `summary-contract.js`: 6/6 checks 성공
- 계약 테스트의 의도적 예약 conflict와 인증 rate-limit은 합계 `expected_4xx=2`, 공개 계약에 없는 `RESERVATION_004`와 notification invariant conflict는 `unexpected_4xx=2`로 분리됐다. 이는 실제 환경 오류율이 아니다.

### profile inspect

네 시나리오 smoke:

```powershell
docker run --rm `
  -v "${PWD}/performance/k6:/scripts:ro" `
  grafana/k6:2.1.0 inspect `
  -e TARGET_ENV=local `
  -e BASE_URL=https://loadtest-proxy:8443 `
  -e ALLOWED_HOSTS=loadtest-proxy `
  -e PROFILE=smoke `
  -e FIXTURE_PATH=/scripts/fixtures/test-data.example.json `
  -e RUN_ID=local-inspect `
  -e COMMIT_SHA=0123456789abcdef0123456789abcdef01234567 `
  /scripts/main.js
```

`storeSearch` local-baseline options:

```powershell
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
  -e COMMIT_SHA=0123456789abcdef0123456789abcdef01234567 `
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

| scenario | 입력 | p50 | p95 | p99 | RPS | expected 4xx | unexpected 4xx | 5xx |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| authRefresh | NOT CONFIGURED | — | — | — | — | — | — | — |
| storeSearch | NOT CONFIGURED | — | — | — | — | — | — | — |
| reservationCreate | NOT CONFIGURED | — | — | — | — | — | — | — |
| notificationHistory | NOT CONFIGURED | — | — | — | — | — | — | — |

### Staging

| scenario | 입력 | p50 | p95 | p99 | RPS | expected 4xx | unexpected 4xx | 5xx |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| authRefresh | NOT CONFIGURED | — | — | — | — | — | — | — |
| storeSearch | NOT CONFIGURED | — | — | — | — | — | — | — |
| reservationCreate | NOT CONFIGURED | — | — | — | — | — | — | — |
| notificationHistory | NOT CONFIGURED | — | — | — | — | — | — | — |

## 위험과 다음 실행 gate

- local fixture가 없으므로 실제 API 계약, DB 상태와 Valkey·cursor 연결은 아직 검증되지 않았다.
- 로컬 backend integration task가 Testcontainers readiness/context 종료에서 시간 초과됐다. 현재 변경과 독립적인 환경·suite 종료 문제지만 CI A~D shard가 성공하기 전에는 회귀 검증이 완료되지 않는다.
- 공유 staging의 동시 트래픽과 데이터 상태는 측정 noise가 될 수 있다. 승인 시간과 실행 전후 CloudWatch 구간을 함께 기록해야 한다.
- staging host는 호출자가 함께 넘기는 allowlist만 신뢰하지 않는다. 저장소의 신뢰 allowlist에 승인 hostname이 리뷰되어 들어오기 전까지 모든 staging 실행을 차단한다.
- 예약 baseline은 배분된 `ARRIVAL_RATE × DURATION_SECONDS` 수만큼 충돌하지 않는 template을 소모한다. 반복 사용으로 예상 409 비율을 왜곡하지 않는다.
- 인증은 IP rate limit을 의도된 429로 분리한다. 알림·예약 setup은 fixture에서 실제로 쓰는 계정만 로그인한다.
- 로컬 k6 요청은 전용 HTTPS proxy를 통해서만 보내고, 내부 CA 인증서 검증 완화는 `TARGET_ENV=local`에만 적용한다. backend의 refresh cookie 보안 속성은 낮추지 않는다.
- 혼합 프로필의 인증 refresh 계정, 예약 계정, 알림 이력 계정은 서로 격리하며 알림 조회는 fixture가 약속한 두 번째 페이지가 실제로 없으면 계약 실패로 처리한다.
- 알림 200 응답도 OpenAPI의 cursor, pageSize, PublicId, purpose, title, resource, action, offset date-time 계약을 모두 만족해야 하며 두 번째 페이지에 실제 항목이 없으면 실패한다.
- k6의 전역 VU ID가 다중 시나리오에서 연속적이지 않을 수 있으므로 auth pool은 전체 `MAX_VUS` 이상을 요구해 modulo 계정 선택이 동시에 같은 계정을 가리키지 않게 한다.
- 인증 login 또는 refresh가 429로 끝나면 분류 counter에는 남기되 완성된 인증 iteration으로 인정하지 않는다. constant-arrival-rate의 `dropped_iterations`도 0이 아니면 실행을 실패시키고 안전 summary에 남긴다.
- backend 기본 IP rate limit은 login 5회/600초, refresh 30회/60초다. local loadtest override는 하네스 최대 입력을 수용하도록 각각 `600,000회/600초`, `60,000회/60초`를 backend에만 주입한다. 이 조건의 결과는 순수 인증 지연시간·처리량 기준선이며 기본 보호 동작 검증이 아니다. override 없이 실행해 429가 섞인 `authRefresh` 결과는 p50/p95/p99 또는 #286 근거로 사용하지 않는다.
- 공개 매장 검색의 기본 한도는 source IP 기준 60회/60초이고 local loadtest override는 `60,000회/60초`를 주입한다. override가 없는 환경에서 `2 iterations/s × 30초`를 반복하려면 이전 실행 종료 후 최소 60초를 기다려 새 창에서 시작한다. 어느 환경이든 예상 429가 발생한 결과는 검색 처리량·p50/p95/p99 기준선 또는 #286 근거로 사용하지 않는다.
- CSRF 준비 제한은 local loadtest override에 복제하지 않고 backend 기본 예산 60회/60초를 상속한다. 일반 `MAX_VUS` 상한은 100이지만 `authRefresh`를 선택한 baseline은 이 예산 아래의 50으로 제한한다. 예약·알림처럼 auth refresh를 선택하지 않은 시나리오는 CSRF 예산 때문에 50으로 제한하지 않는다.
- 성공한 인증 login은 refresh 결과와 관계없이 같은 cookie jar에서 CSRF 토큰을 준비한 뒤 현재 session을 logout한다. k6의 `noCookiesReset=true`가 같은 VU의 cookie jar를 iteration 사이에 유지하므로 VU runtime의 CSRF 토큰 캐시와 수명이 일치하며, VU별 cookie jar 격리는 유지된다. CSRF 토큰과 쿠키는 client별로 재사용해 IP당 준비 요청 제한을 iteration 수만큼 소비하지 않는다. setup의 Reservation·Notification bearer 준비 로그인도 Access Token을 반환하기 전에 Refresh Token family를 회수한다. CSRF·logout 요청은 `phase=cleanup`이라 성능 threshold와 summary에서 제외되며, cleanup 실패는 실행 실패다. 정상 종료에서는 k6가 만든 Valkey family가 남지 않는다. 강제 중단으로 cleanup이 실행되지 못하면 합성 계정 전체 로그인 종료 또는 환경 소유자가 승인한 Valkey 정리 절차로 잔존 family를 회수한 뒤 다음 실행을 허용한다.
- setup bearer의 15분 수명보다 짧게 끝내기 위해 duration을 최대 600초로 제한했다. 더 긴 시험은 token 회전 계약을 별도 설계한 뒤 수행한다.
- raw HTTP output, Token, cookie, cursor, 알림 제목과 자원 ID는 증거로 보관하지 않는다.

다음 실행은 `performance/k6/README.md`의 local smoke 순서를 따르며, 성공한 `LOCAL_SMOKE_RUN_ID`와 그 run이 생성한 검증 가능한 JSON artifact 없이는 local baseline 구성이 거부된다. staging은 배포 full SHA, 합성 fixture, 공지 시간, 부하 상한, 저장소에서 리뷰한 trusted hostname, `STAGING_APPROVED=true`, 성공한 `STAGING_SMOKE_RUN_ID`와 동일 실행 artifact가 모두 있을 때만 실행한다.

## 후속 이슈 연결

현재 SQL 실행 시간, rows examined 또는 실행 계획 증거가 없으므로 [#286](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/286)에 병목을 주장하거나 인덱스 변경을 제안하지 않는다. 실제 local/staging 결과에서 쿼리 병목이 관찰된 경우에만 환경·commit·scenario·부하 입력과 함께 #286으로 연결하고, 다른 병목은 소유 도메인 Issue로 분리한다.
