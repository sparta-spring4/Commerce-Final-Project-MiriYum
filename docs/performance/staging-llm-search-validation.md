# staging LLM 검색·대체 메뉴 검증

Issue #568의 exact-first OpenAI 보완 검색과 같은 매장·인근 매장 대체 메뉴를 승인된 staging 합성 데이터에서 분리 검증한다. production host, 실사용자, 운영 데이터에는 실행하지 않는다. 검색 결과와 가용성의 원장은 MySQL이며 OpenAI 응답은 음식 개념 해석에만 사용한다.

## 실행 차단 조건

- 실제 staging 실행은 #357 종료, PR #434가 포함된 backend 배포, private health `UP`, OpenAI Secret 전달, CloudWatch LLM meter 조회, 저장소 밖 합성 fixture와 실행 승인이 모두 확인된 뒤에만 진행한다. #592의 양방향 메뉴명 매칭과 #602의 concept abstention을 평가하는 실행은 두 PR이 모두 병합되고 같은 backend full SHA가 staging에 배포된 뒤 진행한다.
- `LLM_LIVE_TEST_APPROVED=true`가 없거나 계획·누적 합계가 200회 또는 USD 0.50을 초과하면 init context에서 HTTP 요청 전에 실패한다.
- 하네스는 scenario를 겹치지 않게 배치하고 각 scenario를 고정 `vus: 1`로 실행한다. 각 iteration 뒤 1초를 대기하므로 실행률은 1 iteration/s 미만이며 fixture case 수는 scenario별 상한과 duration 여유를 함께 통과해야 한다. 실제 OpenAI 최대 처리량이나 production 용량을 측정하지 않는다.
- fixture·환경 파일에는 자격증명, Token, Secret을 기록하지 않는다. ignored fixture의 실행 입력인 검색어와 store/menu ID는 공개 artifact·metric label·summary·오류 메시지로 복사하지 않는다.

## 입력 준비

실제 fixture는 저장소에 추가하지 않고 `performance/k6/fixtures/search-llm.local.json` 같은 ignored 경로에 둔다. 각 case는 안전한 alias, scenario와 실행에 필요한 공개 입력만 가진다. `exact`, `natural-language`, `same-store`, `nearby-store`, `fallback`을 분리하며 fallback case는 `fallbackMode`를 `disabled` 또는 `timeout`으로 명시한다.

`natural-language` case는 결과 개수만으로 통과할 수 없고 다음 품질 기대값 중 하나 이상을 가져야 한다.

- `expectedStoreIds`: 결과에 반드시 포함될 합성 매장 ID
- `expectedOrderedStoreIds`: 결과에서 상대 순서를 지켜야 할 합성 매장 ID. #592 검증에서는 exact, 정방향 expanded, 역방향 expanded 매장을 이 순서로 둔다.
- `excludedStoreIds`: 결과에 포함되면 안 되는 알려진 무관 합성 매장 ID
- `maximumItems`: 통제된 true-no-answer 입력에서 허용할 최대 결과 수. `minimumItems`보다 작을 수 없다.

각 ID 배열은 양의 정수만 중복 없이 가지며 기대 포함·순서 ID와 제외 ID는 겹칠 수 없다. 아래 모양은 설명용이며 실제 검색어와 ID는 ignored fixture에만 둔다.

```json
{
  "cases": [
    {
      "alias": "compound-reverse-match",
      "scenario": "natural-language",
      "searchInput": "<approved compound food expression>",
      "minimumItems": 3,
      "expectedStoreIds": [303],
      "expectedOrderedStoreIds": [101, 202, 303]
    },
    {
      "alias": "no-food-signal-quality",
      "scenario": "natural-language",
      "searchInput": "<approved true-no-answer expression>",
      "minimumItems": 0,
      "maximumItems": 0,
      "excludedStoreIds": [404]
    }
  ]
}
```

같은 매장 case는 현재 `SELLING`, 같은 주 분류, 원본 가격 ±20%, 충분한 공개 수량 후보를 가진다. 인근 매장 case는 같은 매장 적격 후보가 없고 원본 매장의 검증 좌표 기준 3km 이내 후보만 가진다. 실행 전후 합성 메뉴 판매 상태를 기록하되 ID와 이름은 공개 증거에 남기지 않는다.

## 정적 계약 검증

```powershell
$tests = Get-ChildItem performance/k6/tests/search-llm-*-contract.js
foreach ($test in $tests) {
  docker run --rm -v "${PWD}/performance/k6:/scripts:ro" grafana/k6:2.1.0 run "/scripts/tests/$($test.Name)"
  if ($LASTEXITCODE -ne 0) { throw "contract failed: $($test.Name)" }
}
```

## 예산과 CloudWatch 증거

실행 직전에 `MiriYum/Staging`의 다음 meter를 같은 run window로 조회해 누적 호출과 token을 확인한다.

- `miriyum.search.llm.calls`
- `miriyum.search.llm.latency`
- `miriyum.search.llm.outcomes`
- `miriyum.search.llm.tokens`

모델 가격은 저장소에 고정하지 않는다. 실행 시점에 승인된 input/output 단가와 누적 token으로 비용을 계산하고, 계획 비용과 누적 비용을 저장소 밖 환경 입력으로 전달한다. k6는 CloudWatch 조회 권한이 없으므로 summary가 delta 0을 추측하지 않고 `EXTERNAL_REQUIRED`로 남긴다. 실행 후 같은 시간 범위를 다시 조회해 calls, success/fallback outcome, input/output token, 추정 비용의 비식별 증분을 run ID·두 full SHA·fixture SHA-256과 함께 Issue 증거에 결합한다.

같은 값은 저장소 밖 `search-llm-budget.local.json`에도 `schemaVersion`, `approved=true`, run ID, 두 full SHA, planned/cumulative calls와 planned/cumulative USD만 기록한다. init context는 이 artifact가 현재 실행 입력과 정확히 일치해야만 요청을 허용한다. 단가, token 원문이나 Secret은 artifact에 넣지 않는다.

exact case는 전후 `calls` 증분이 0이어야 한다. 자연어와 대체 메뉴는 실제 call·success outcome·token 증분이 있어야 한다. 현재 backend meter는 `MATCHABLE`, `AMBIGUOUS`, `NO_FOOD_SIGNAL`을 별도 outcome으로 노출하지 않으므로 #602 case의 무관 후보 미포함은 live 품질 관찰이며 provider가 실제로 특정 abstention 값을 반환했다는 증거로 사용하지 않는다. 그 내부 판정을 구분해야 하면 #568 범위 밖의 backend 관측성 계약이 선행돼야 한다. 지표가 조회되지 않으면 성공으로 추측하지 않고 `NOT OBSERVABLE`로 기록한다.

## 실행

각 scenario는 독립 run ID로 실행한다. `LLM_SCENARIOS`에는 정확히 하나만 지정할 수 있으며, 하네스는 여러 scenario를 입력하면 init 단계에서 거부한다. 이 결속으로 run window의 CloudWatch meter delta를 해당 scenario의 증거로 사용한다. 아래 예시는 자연어 검색 한 묶음이며 `$plannedCalls`, 누적값과 비용은 실행 승인 증거에서 가져온다.

```powershell
$backendSha = '<deployed-full-sha>'
$harnessSha = (git rev-parse HEAD).Trim()
$runId = 'staging-llm-natural-language-YYYYMMDD-NN'

docker run --rm `
  -v "${PWD}/performance/k6:/scripts:ro" `
  -v "${PWD}/performance/k6/results:/results" `
  -e TARGET_ENV=staging `
  -e BASE_URL=https://staging-api.miriyum.click `
  -e ALLOWED_HOSTS=staging-api.miriyum.click `
  -e STAGING_APPROVED=true `
  -e STAGING_HARNESS_SOURCE_VERIFIED=true `
  -e STAGING_SPLIT_SHA_APPROVED=true `
  -e LLM_LIVE_TEST_APPROVED=true `
  -e COMMIT_SHA=$backendSha `
  -e HARNESS_COMMIT_SHA=$harnessSha `
  -e LLM_RUN_ID=$runId `
  -e LLM_FIXTURE_PATH=/scripts/fixtures/search-llm.local.json `
  -e LLM_BUDGET_PROOF_PATH=/scripts/fixtures/search-llm-budget.local.json `
  -e LLM_SCENARIOS=natural-language `
  -e LLM_DURATION_SECONDS=180 `
  -e LLM_PLANNED_CALLS=$plannedCalls `
  -e LLM_CUMULATIVE_CALLS=$cumulativeCalls `
  -e LLM_PLANNED_COST_USD=$plannedCostUsd `
  -e LLM_CUMULATIVE_COST_USD=$cumulativeCostUsd `
  grafana/k6:2.1.0 run /scripts/search-llm/main.js
```

동일 SHA 실행이 아니면 `STAGING_SPLIT_SHA_APPROVED=true`는 사용할 수 없다. 승인된 split SHA일 때만 설정한다. exact는 최대 10 case, 자연어는 60, 같은 매장은 30, 인근 매장은 30, fallback은 20 case를 넘을 수 없다.

fallback은 다른 scenario와 같은 실행에 섞지 않는다. 배포 담당자가 승인한 `disabled` 또는 통제된 timeout 설정으로 backend를 재배포하고 private health `UP`을 확인한 뒤 `LLM_SCENARIOS=fallback`, 동일한 `LLM_FALLBACK_MODE`로 실행한다. 두 모드를 각각 별도 run ID로 검증한다. 하네스는 5xx 없이 기존 exact 검색 또는 일반 대체 후보 계약이 유지되는지만 확인한다.

## 결과와 원복

summary에는 scenario별 API p50/p95/p99, actual RPS, 오류, run ID, 두 full SHA와 fixture fingerprint만 남는다. 검색어, 응답 body, ID와 credential은 남기지 않는다. CloudWatch 증분은 위 절차의 외부 증거로 결합한다.

실패·성공·중단과 관계없이 staging OpenAI enabled/model/timeout 설정과 합성 메뉴 판매 상태를 승인된 실행 전 값으로 복구하고 같은 SHA를 재배포한다. private health `UP`, exact 검색과 일반 대체 추천의 정상 응답을 확인한다. 원복 증거가 없으면 #568 실행을 완료로 기록하지 않는다.

## 2,000질의 × 5회 black-box 대규모 평가 (#668)

이 평가는 `backend/scripts/dev-data/search-profile-demo-500-stores.sql`로 생성된 합성 매장 500개와 메뉴 5,000개만 대상으로 한다. 기존 로컬 300개 메뉴 계열 평가셋과 staging fixture의 60개 메뉴 template은 서로 다른 corpus이므로 gold label을 재사용하지 않는다. seed `20260825`와 원본 SQL SHA-256에 결속된 staging 전용 manifest를 결정적으로 생성한다.

질의 2,000개의 고정 분포는 메뉴명 없는 감각 표현 800개, 지역·가격·업종 복합 조건 400개, 별칭·양방향 표현 300개, 동일 메뉴 매장 정렬 200개, 음식 외 음성 질의 150개, 일반 token·불가능 조합 방어 150개다. 파일럿은 같은 평가셋에서 40/20/15/10/8/7개를 층화 추출한다. 파일럿 반복 0은 본평가의 첫 반복으로 checkpoint에서 재사용한다.

```powershell
cd performance/search-eval-v2
$env:PYTHONPATH = 'src'
$artifact = '<repository-outside-artifact-directory>'

.\.venv\Scripts\python.exe -m miriyum_search_eval.cli staging-generate --artifact-dir $artifact
.\.venv\Scripts\python.exe -m miriyum_search_eval.cli staging-pilot --artifact-dir $artifact
# 파일럿 CloudWatch 실측을 cloudwatch-pilot-proof.json으로 결합한 뒤에만 실행한다.
.\.venv\Scripts\python.exe -m miriyum_search_eval.cli staging-run --artifact-dir $artifact
.\.venv\Scripts\python.exe -m miriyum_search_eval.cli staging-report --artifact-dir $artifact
```

유료 HTTP 명령은 다음 환경값을 모두 요구하며 하나라도 없으면 network 요청 전에 실패한다.

- `STAGING_EVAL_RUN_ID`: 재개 전체에서 고정한 안전한 run ID
- `STAGING_EVAL_BACKEND_SHA`: staging에 실제 배포된 backend full SHA
- `STAGING_EVAL_HARNESS_SHA`: 깨끗한 checkout의 평가 하네스 full SHA
- `STAGING_EVAL_APPROVED=true`
- `STAGING_EVAL_HARNESS_VERIFIED=true`
- `STAGING_EVAL_CLOUDWATCH_VERIFIED=true`
- `STAGING_EVAL_REQUESTED_MODEL`: staging 배포 설정에서 확인한 요청 모델
- `STAGING_EVAL_TEMPERATURE`: staging 배포 설정에서 확인한 temperature
- `STAGING_EVAL_SYSTEM_INSTRUCTION_SHA256`: 배포 SHA의 system instruction SHA-256
- `STAGING_EVAL_JSON_SCHEMA_SHA256`: 배포 SHA의 structured-output JSON schema SHA-256

환경값은 실행 대상 식별자일 뿐 운영 증거를 대신하지 않는다. artifact 디렉터리의 `staging-preflight.json`도 다음 항목을 같은 run ID·backend/harness/dataset·모델·temperature·instruction/schema SHA에 결속해 가져야 한다.

- 실제 배포 host와 backend full SHA readback, 확인 시각
- private health `UP`와 확인 시각
- 합성 corpus 500개 매장·5,000개 메뉴 및 seed SQL SHA-256 검증
- CloudWatch meter 읽기 권한과 확인 시각
- 최대 10,000 logical call과 USD 상한이 명시된 유료 실행 승인
- 시작·종료 시각과 분당 40회가 명시된 공유 rate-limit window 승인

배포·health·CloudWatch 확인 증거는 실행 시작 기준 30분 이내여야 한다. 현재 시각이 승인 window 밖이거나 증거가 누락·불일치하면 `urlopen` 전에 실패하고, 하네스는 모든 재시도 직전과 pacing 대기 직후에도 wall clock을 다시 확인해 긴 본평가 중 window가 끝나면 다음 요청을 차단한다. `staging-execution-timing.json`은 파일럿과 본평가의 실제 시작·종료를 같은 provenance로 기록한다. CloudWatch meter window는 이 실제 구간 전체를 포함하고 승인 window 안에 있으며 검증 시각보다 미래가 아니어야 한다. 파일럿 gate와 본평가 전 CloudWatch proof도 같은 provenance와 파일럿의 결정적 request ID 100개를 대조하므로, 다른 실행의 통과 파일을 복사해 본평가를 시작할 수 없다.

기본 host는 `https://staging-api.miriyum.click`만 허용한다. 하네스는 순차 실행, 호출 시작 간 최소 1.5초, timeout 10초, 429·5xx·timeout 최대 4회 재시도, 연속 최종 실패 3건 circuit breaker를 적용한다. 각 HTTP 시도 전에 intent를 `staging-calls.checkpoint.jsonl`에 append하고 `fsync`한 뒤 요청한다. intent만 있고 결과가 없는 비정상 종료는 과금 불확실 상태로 차단해 자동 재전송하지 않는다. run·backend·harness·dataset·모델 설정·query·repeat에 결속된 결정적 request ID를 사용한다. response body와 header, 인증 정보는 기록하지 않고 예약 범위 안의 합성 `storeId` 문자열과 계약 version, 상태, latency만 남긴다. 예약 범위 밖 ID는 원문을 저장하지 않고 corpus contamination 형식 오류로 처리한다.

파일럿은 정확한 층화 request ID 100개의 성공, format/final provider 실패 0건, 재시도 10회 이하, 양성 @8 40% 이상, 성공한 음성 질의 오탐률 25% 이하일 때만 gate를 통과한다. checkpoint 전체 시도에서 회복된 429·5xx·timeout도 별도로 집계한다. 본평가 전 `cloudwatch-pilot-proof.json`은 같은 provenance와 meter 시작·종료 시각, 정확히 100회 LLM 호출, 0보다 큰 input/output token과 실제 비용, 비어 있지 않은 returned model 목록을 가져야 한다. 파일럿 단가로 계산한 10,000회 예상 비용이 preflight 승인 USD 상한을 넘으면 본평가를 차단한다. 해당 증명이 없거나 서로 다르면 9,900개 추가 호출을 시작하지 않는다.

정답률은 고유 질의 2,000개의 반복 0만 통계 단위로 삼고 Wilson 95% 구간을 계산한다. Recall·MRR·nDCG의 분모는 양성 질의 1,700개이고, 음성 300개는 성공 응답만을 분모로 오탐률과 전체 true-negative 정확도를 별도 계산한다. 10,000회 호출은 서로 다른 repeat index 5개가 모두 성공한 질의의 top-1 안정률과 pairwise Jaccard에만 사용한다. 공개 API가 최대 50개만 반환하므로 @1/@3/@5/@8/@20/@50, MRR, nDCG, 음성 질의 오탐률, 지역·가격·업종 위반률과 latency만 실제로 관측한다. exact/forward/reverse/alias 표는 질의 구성 strata별 회수율이며 내부 match branch의 실행 증거가 아니다. 내부 LLM semantic hit, 애플리케이션 predicate hit, 전체 후보, 현재 corpus에 없는 폐점·비공개·과거 버전 누출률은 결과에서 `notObservable`로 명시하며 추정값으로 채우지 않는다. 10,000개 HTTP matrix 뒤에는 `cloudwatch-full-proof.json`의 calls, input/output token, returned model, 실제 비용과 HTTP attempt 수를 대조해야 보고서 상태가 `FINAL_WITH_CLOUDWATCH`가 된다.
