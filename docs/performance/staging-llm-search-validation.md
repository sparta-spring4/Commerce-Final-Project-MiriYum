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
