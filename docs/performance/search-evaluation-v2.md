# MiriYum 검색 통합 평가 v2

## 실행 기준

- 소유 Issue: [#593](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/593)
- 평가 커밋: `3a2d5bef2db30e98b8c6e8bb60267feadd35ff12`
- 데이터 seed / schema: `20260823` / `miriyum-search-eval-v2`
- 합성 매장 / 메뉴 / 고유 질의: 500 / 5,000 / 2,000
- 질의 반복: 각 5회, 분석 대상 채팅 결과 10,000건
- 요청 모델 / 반환 모델: `gpt-4o-mini` / `gpt-4o-mini-2024-07-18`
- temperature / max output tokens: `0.0` / `100`
- 실제 애플리케이션 기준: 병합된 abstention 구조화 응답과 bidirectional menu-name predicate
- 비교 기준: Issue #589 병합 전 legacy one-way predicate를 같은 LLM 응답에 적용한 counterfactual

정답표는 평가 대상 LLM으로 만들지 않았다. 고정 메뉴·별칭 사전, 결정적 템플릿과 합성 gold manifest를 사용했다. 데이터 검증 결과 고아 정답, 중복 ID, 모순 gold, target leakage, 정답 매장·메뉴 누락은 모두 0건이다.

## v2.1 정답 정의·후보 수 재평가

기존 10,000회 응답을 새 API 호출 없이 전부 재사용했다. strict gold는 과거 비교를 위해 그대로 두고, sensory 질의에만 고정 사전의 `재료 + 맛 + 조리법 + 국물` 네 속성이 모두 같은 메뉴군을 acceptable gold로 추가했다. 평가 대상 LLM은 이 정답 확장에 사용하지 않았다. schema는 `miriyum-search-eval-v2.1`, dataset SHA-256은 `f501ea166b469c9f15717113425f44673e0337e0a31e2e837263b0c6264a53b7`다. 체크포인트 10,000건이 모두 이관됐고 변경·누락, 신규 provider 호출, 추가 비용은 모두 0이다.

기존 `semanticHit`는 메뉴명뿐 아니라 재료·맛 같은 속성 하나만 겹쳐도 성공으로 보던 loose 지표다. 따라서 `76.90%`를 메뉴군 이해율로 해석하면 안 된다. v2.1에서 메뉴군 이름 자체를 맞춘 비율은 strict `1,117/2,000 = 55.85%`, acceptable `1,143/2,000 = 57.15%`였다.

| 최종 병합 후보 범위 | strict 고유 질의 성공 | acceptable 고유 질의 성공 |
|---|---:|---:|
| @8 | 1,213/2,000 = 60.65% | 1,262/2,000 = 63.10% |
| @20 | 1,312/2,000 = 65.60% | 1,364/2,000 = 68.20% |
| @50 | 1,388/2,000 = 69.40% | 1,433/2,000 = 71.65% |
| 전체 후보 | 1,429/2,000 = 71.45% | 1,460/2,000 = 73.00% |

sensory 800개만 보면 strict는 `@8 165/800 = 20.63%`, `@20 243/800 = 30.38%`, `@50 313/800 = 39.13%`, 전체 후보 `352/800 = 44.00%`다. acceptable은 각각 `214/800 = 26.75%`, `295/800 = 36.88%`, `358/800 = 44.75%`, `383/800 = 47.88%`다. 즉 @8에서 전체 후보까지 넓히면 순위 손실 일부는 회복되지만, acceptable 기준으로도 전체 후보에 정답이 없는 질의가 `417/800 = 52.13%`라 후보 생성 문제가 더 크게 남는다.

sensory 질의에서 LLM이 속성을 과반 반복으로 보존한 고유 질의 비율은 재료 `467/800 = 58.38%`, 조리법 `230/800 = 28.75%`, 식감 `93/800 = 11.63%`, 국물 `85/800 = 10.63%`, 향 `71/800 = 8.88%`, 맛 `66/800 = 8.25%`였다. 현재 낮은 최종 정답률의 주원인은 @8 크기만이 아니라, 맛·국물·조리법을 함께 보존하지 못하고 재료 중심의 다른 메뉴군을 내는 자연어 해석 단계다.

v2.1 전체 결과는 현재 worktree의 ignored 로컬 경로 `performance/search-eval-v2/artifacts/eval-20260824-gold-v2-1-cutoffs/`에 생성했으며, `report.md`, `aggregate.json`, 10,000건 `results.jsonl`, checkpoint migration 원장과 SHA-256 manifest를 포함한다. 이 대용량 유료 응답 산출물은 Git diff나 깨끗한 checkout에 포함되지 않는다. 동일한 무과금 재현에는 ignored 로컬 원본 `eval-20260824-deterministic-menu-full/`이 필요하며, 영구 공유가 필요하면 별도 승인된 artifact 저장소에 원본과 v2.1 bundle을 게시해야 한다.

## 속성 근거 후보 검색 반사실 평가와 프롬프트 파일럿

기존 10,000회 체크포인트를 추가 호출 없이 재사용해, 메뉴명이 없는 질의에서는 질의 원문의 `재료·맛·조리법·국물` 중 둘 이상이 일치하는 메뉴를 후보로 보강하고 명시적 메뉴명이 있는 질의는 기존 양방향 결과를 그대로 유지하는 `simulated-query-attribute-evidence-v1`을 평가했다. 이는 아직 production predicate가 아닌 오프라인 simulated 결과다.

| 고유 질의 지표 | actual application | simulated evidence | 변화 |
|---|---:|---:|---:|
| 전체 strict @8 | 1,213/2,000 = 60.65% | 1,509/2,000 = 75.45% | +296, +14.80%p |
| 전체 acceptable @20 | 1,364/2,000 = 68.20% | 1,739/2,000 = 86.95% | +375, +18.75%p |
| sensory strict @8 | 165/800 = 20.63% | 461/800 = 57.63% | +296, +37.00%p |
| sensory acceptable @20 | 295/800 = 36.88% | 670/800 = 83.75% | +375, +46.88%p |

명시적 메뉴 질의를 기존 결과로 고정한 안전 가드 적용 후 filter-defense @8은 actual/simulated 모두 `86.67%`, true-no-answer 오탐은 모두 0건, 전체 gold-negative 오탐은 모두 `12/915`로 유지됐다. 따라서 속성 근거 검색은 구현 후보로서 유망하지만, 실제 SQL·애플리케이션 성능이나 운영 수치로 단정하지 않는다.

속성 보존 프롬프트의 실제 유료 층화 파일럿은 100개를 한 번씩 짝비교했다. sensory acceptable @20이 `35/40 → 38/40`으로 3건 증가해 사전 게이트의 최소 개선 폭을 정확히 충족했다. filter-defense는 `3/7 → 3/7`, gold-negative 오탐은 `0/7 → 0/7`, 폐점·비공개·과거·필터 누출과 provider/format 실패는 모두 0이었다. 파일럿 비용은 `$0.00836715`, 10,000회 예상 비용은 `$0.836715`였다.

게이트 통과 후 본 실행을 재개했으나 `2,447/10,000` 성공 시점에 지속적인 HTTP 429가 발생해 중단했다. 현재 체크포인트 비용은 `$0.21622275`, 감사 원장을 포함한 HTTP 시도는 2,558회, 재시도는 106회다. 비용 0인 최종 429 네 건은 감사 원장으로 격리했고, 마지막 한 건은 다음 `prompt-run` 시작 시 자동 격리·재시도된다. 이 불완전 표본으로 2,000개 전체 성공률을 계산하지 않으며 프롬프트 본 평가 완료를 주장하지 않는다. 로컬 산출물은 `performance/search-eval-v2/artifacts/eval-20260824-attribute-prompt-pilot/`에 있다.

이 중단을 계기로 하네스에는 최대 재시도를 소진한 429의 자동 circuit breaker를 추가했다. 중단 시 이미 실행 중인 future는 끝까지 회수해 체크포인트에 기록하고 새 요청만 막으므로, 비용 상한·429 중단 뒤에도 유료 응답을 잃어 재호출하지 않는다. 재개 격리는 `providerHttpStatus=429`, `failureKind=http_retryable`, 명시적 숫자형 `costUsd=0`을 모두 만족하는 기록에만 허용한다.

## 구조화 음식 근거 A/B/C 재분석

PR #635가 `dev`에 병합된 뒤의 actual application 결과 A를 고정 기준선으로 사용하고, 같은 10,000회 체크포인트에 신규 API 호출 없이 두 반사실 조건을 비교했다. B는 `rawFoodSpans`, 메뉴 계열, 재료, 맛, 국물, 조리법, 형태를 분리한 구조화 후보를 A 뒤에 보충하되 현재 정렬을 유지한다. C는 B와 같은 후보를 사용하고 메뉴명이 없는 질의에만 구조화 음식 근거 우선 정렬을 적용한다. 명시적 메뉴·별칭 질의는 현재 후보와 순서를 보존한다. B와 C는 production predicate가 아닌 simulated evidence다.

| 고유 질의 지표 | A: merged actual | B: structured candidate | C: structured + ranking |
|---|---:|---:|---:|
| strict @1 | 1,044/2,000 = 52.20% | 1,085/2,000 = 54.25% | 1,116/2,000 = 55.80% |
| strict @8 | 1,213/2,000 = 60.65% | 1,330/2,000 = 66.50% | 1,354/2,000 = 67.70% |
| acceptable @8 | 1,262/2,000 = 63.10% | 1,420/2,000 = 71.00% | 1,435/2,000 = 71.75% |
| strict @20 | 1,312/2,000 = 65.60% | 1,494/2,000 = 74.70% | 1,503/2,000 = 75.15% |
| strict @50 | 1,388/2,000 = 69.40% | 1,613/2,000 = 80.65% | 1,614/2,000 = 80.70% |
| strict 전체 후보 | 1,429/2,000 = 71.45% | 1,679/2,000 = 83.95% | 1,679/2,000 = 83.95% |
| strict Recall@8 | 37.91% | 42.52% | 43.74% |
| MRR / nDCG@8 | 0.4587 / 0.4501 | 0.4891 / 0.5001 | 0.5058 / 0.5173 |

A→B의 strict @8 순증은 117개, `+5.85%p`로 후보 보강 효과다. B→C 순증은 24개, `+1.20%p`로 정렬 효과다. 최종 C의 A 대비 순증은 141개, `+7.05%p`이며 Wilson 95% CI는 A `58.49–62.77%`, C `65.62–69.71%`다. sensory strict @8은 `165/800 = 20.63% → 248/800 = 31.00% → 272/800 = 34.00%`, sensory acceptable @8은 `214/800 = 26.75% → 338/800 = 42.25% → 353/800 = 44.13%`였다.

안전 게이트는 통과했다. true-no-answer 오탐은 A/B/C 모두 0건, 전체 gold-negative 오탐은 모두 3건이며 폐점·미승인 매장, 비공개·과거 메뉴, 지역·가격·분위기·category 위반은 모두 0건이다. alias/bidirectional strict @8은 `244/300 → 250/300 → 250/300`, filter-defense는 `129/150 → 147/150 → 147/150`으로 기준선을 잃지 않았다. top-1 안정률은 `95.25% → 95.22% → 95.24%`, pairwise Jaccard는 `0.8946 → 0.8937 → 0.8924`로 정답률 개선과 함께 작은 안정성 감소가 관측됐다.

초기 구조화안은 구체적인 family ID를 `면/탕/국/밥` 같은 공통 이름으로 다시 펼치고 기존 양방향 후보를 대체해 filter-defense 오탐과 별칭 손실을 만들었다. 최종안은 구체 family ID 우선, 일반 토큰 단독 후보 금지, 메뉴명 없는 질의의 서로 다른 핵심 속성 2개 이상, 기존 후보 보존 후 구조화 후보 보충이라는 네 가드를 적용했다. 안전 게이트 통과는 오프라인 구현 후보라는 뜻이며 production 활성화를 승인하지 않는다.

최종 분석 커밋은 `2456e9d40aa8ab95a8687ef57f2ef3097815abe9`, 작업 트리는 clean, 신규 provider 호출과 추가 비용은 각각 0회와 `$0`이다. ignored 산출물 `structured-reanalysis/results.jsonl`과 `aggregate.json`의 SHA-256은 각각 `9df2560af0cd46ab012c69f298a776126db0bbbbfcb1ab052ecb820249b57ba0`, `06a72a6e85cc8677f1894a3d8f393d2bf9c60921b7efcd796d3dd49359ee46bb`다.

## 단일 하이브리드 D/E/F/G 재분석

Issue #625의 평가 전용 확장으로 구조화 C를 D 기준선으로 고정하고, 같은 10,000회 결과에 새 provider 호출 없이 세 조건을 더 비교했다. E는 메뉴 설명·태그와 고정 메뉴 사전의 재료·맛·국물·조리법·향·식감 중 서로 다른 두 근거 이상이 있는 후보를 D 뒤에 보충한다. F는 E에 기존 `text-embedding-3-large`의 메뉴별 상위 200개 후보를 합친다. G는 F의 후보 집합을 바꾸지 않고 구조화 속성, 어휘 근거, embedding similarity와 기존 순위 보존 보너스를 하나의 고정 점수로 합쳐 `RECOMMENDED` 결과만 다시 정렬한다. 사용자 원문에서 결정적으로 확인한 메뉴명·별칭과 명시 필터 정렬은 D를 그대로 보존하며, LLM이 추론한 메뉴 계열은 사용자 명시 메뉴로 오인하지 않는다.

| 고유 질의 지표 | D: structured C | E: 사전·설명 후보 | F: E + large embedding | G: 단일 hybrid ranking |
|---|---:|---:|---:|---:|
| strict @1 | 55.80% | 55.80% | 55.85% | 78.60% |
| strict @8 | 67.70% | 67.70% | 67.75% | 84.10% |
| strict @20 | 75.15% | 75.15% | 75.20% | 89.25% |
| strict 전체 후보 | 83.95% | 94.20% | 94.25% | 94.25% |
| acceptable @8 | 71.75% | 71.75% | 71.80% | 85.75% |
| acceptable @20 | 1,596/2,000 = 79.80% | 79.80% | 1,597/2,000 = 79.85% | 1,824/2,000 = 91.20% |
| acceptable 전체 후보 | 87.35% | 95.45% | 95.50% | 95.50% |

주 목표 acceptable @20 `90%`와 후보 상한 목표 acceptable 전체 `95%`를 각각 `91.20%`, `95.50%`로 충족했다. G acceptable @20의 Wilson 95% CI는 `89.88–92.36%`다. strict @20은 `89.25%`이므로 90% 달성 주장은 acceptable 기준에만 적용한다. sensory 800개 acceptable @20은 `61.25% → 89.63%`, 전체 후보는 `78.88% → 99.13%`였다. E가 전체 후보를 크게 늘렸지만 기존 결과 뒤에 붙이기만 해 @20은 변하지 않았고, F의 embedding 단독 순증은 고유 질의 1개였다. 최종 개선의 핵심은 embedding 모델 교체가 아니라 고정 사전 속성 후보를 상위 20개로 올린 G 정렬이었다.

안전 게이트는 통과했다. D/E/F/G의 true-no-answer 오탐은 모두 0건, 전체 gold-negative 오탐은 모두 3건이며 폐점·미승인 매장, 비공개·과거 메뉴, 지역·가격·분위기·category 위반은 모두 0건이다. 명시 메뉴·별칭 유형 acceptable @20은 네 조건 모두 `91.33%`로 유지됐다. @20 top-1 안정률은 `95.24% → 96.05%`, 평균 pairwise Jaccard는 `0.8902 → 0.9299`로 개선됐다.

이는 production backend나 actual application predicate를 변경한 결과가 아니라 합성 corpus의 `simulated-evidence-not-actual-application` 반사실 평가다. 운영 활성화는 승인하지 않는다. 최종 분석 커밋은 `50bb146aa3f469061e3737fc95ec7e8b488673a5`, 실행 시 tracked worktree는 clean, 신규 provider 호출과 추가 비용은 0회와 `$0`이다. frozen structured 결과 SHA-256은 `9df2560af0cd46ab012c69f298a776126db0bbbbfcb1ab052ecb820249b57ba0`, embedding checkpoint SHA-256은 `f9cabb92c0613e84730bd3aec343f41be1b2444e21c2ac541a0f17aa0db59d58`다. 최종 `hybrid-reanalysis/results.jsonl`과 `aggregate.json` SHA-256은 각각 `c36877da4333902b869c8051f8ed958d7f36581a6a3b9e22c9192cee8dd805c8`, `7ac93b2c67a9dfc3acab4ab182bf5c0421640eb6582ea7dc8768dc346c786190`이다.

### H: 수정된 운영 food-evidence predicate의 구형 체크포인트 재생

H는 G의 `91.20%` simulated 구조를 운영 수치로 오인하지 않기 위해 추가했다. 초기 H는 명시 메뉴 질의에도 속성 후보를 덧붙이고 `menuRank*10+dimensionCount`로 LLM 메뉴 계열 하나를 여러 감각 차원보다 앞세워 acceptable @20 `74.60%`, 전체 음성 오탐 68건, filter-defense `54.67%`로 회귀했다. 수정 H는 명시 메뉴가 결정적으로 해소되면 D 후보·순서를 그대로 유지하고, 메뉴가 없는 질의에서만 현재 공개 메뉴의 이름·설명·카테고리·태그에 일치한 정보성 원문 토큰을 보충한다. 같은 메뉴에서 서로 다른 원문 토큰 2개 이상 또는 구조화 차원 2개 이상이 맞아야 하며, 점수는 원문 토큰·구조화 차원·메뉴 계열의 고정 가중 순서로 계산한다. 후보 판정에는 합성 `familyId`, 합성 family attributes, gold label을 사용하지 않았다.

단, 기존 10,000회 체크포인트는 현재 provider schema에 새로 추가된 `aromas`, `textures`를 포함하지 않는다. 따라서 H는 `actualApplication=false`, `actualApplicationPredicate=true`, `queryEvidenceProvenance=legacy-structured-checkpoint-replay`, `queryEvidenceSchemaComplete=false`인 무과금 predicate 재생이다. 현재 production 앱의 새 schema를 end-to-end로 실행한 actual application 결과가 아니며, D/E/F/G도 계속 simulated다.

| 고유 질의 지표 | D 기준선 | G simulated | H actual predicate replay |
|---|---:|---:|---:|
| strict @8 | 67.70% | 84.10% | 1,465/2,000 = 73.25% |
| strict @20 | 75.15% | 89.25% | 1,631/2,000 = 81.55% |
| acceptable @20 | 79.80% | 91.20% | 1,712/2,000 = 85.60% |
| acceptable 전체 후보 | 87.35% | 95.50% | 1,889/2,000 = 94.45% |
| sensory acceptable @20 | 61.25% | 89.63% | 603/800 = 75.38% |
| alias acceptable @20 | 91.33% | 91.33% | 277/300 = 92.33% |
| filter-defense acceptable @20 | 147/150 = 98.00% | 147/150 = 98.00% | 147/150 = 98.00% |

수정 H acceptable @20의 Wilson 95% CI는 `83.99–87.07%`로 목표 90%에는 아직 미달한다. top-1 안정률은 `95.56%`, 평균 pairwise Jaccard는 `0.9088`이다. true-no-answer 오탐은 0건, 전체 gold-negative 오탐은 D/G와 같은 3건이며 폐점·미승인 매장, 비공개·과거 메뉴, 필터 위반은 모두 0이다. filter-defense도 D/G와 같은 `147/150`으로 복구되어 안전 게이트는 통과했다. 초기 H와 비교하면 acceptable @20은 `74.60% → 85.60%`, 전체 음성 오탐은 `68 → 3`, filter-defense는 `54.67% → 98.00%`다.

G의 남은 우위는 합성 family에만 있는 향·식감·국물 속성을 정렬에 직접 사용한 효과를 포함한다. 현재 운영 메뉴의 이름·설명·카테고리·태그에는 이 속성이 항상 존재하지 않으므로 G `91.20%`를 그대로 actual 수치로 재현할 수 없다. 별도 메뉴 속성 저장·색인 계약 없이 이 정보를 production 코드에 복사하면 gold leakage가 된다. 따라서 수정 H는 안전 게이트 통과를 확인했지만 `actualApplication=false`, `productionActivationApproved=false`를 유지한다.

이 실행은 기존 10,000개 LLM 체크포인트와 frozen embedding만 재사용했으며 신규 provider/embedding 호출과 비용은 모두 0이다. 결과 파일은 ignored 로컬 artifact이고, `hybrid-reanalysis/results.jsonl`과 `aggregate.json` SHA-256은 각각 `c68cb6bfddfbc3a8562546f18d22ec875228e28e40c9a259626ed136501ac669`, `057f77f6ee7e2e04e2e27096d723a9cf288ec6d71d4f1f6ede0c3c6596738447`다.

## GPT-4o mini와 GPT-5.4 mini 100질의 짝비교

속성 보존 프롬프트, JSON schema, 동일 층화 질의 100개, `reasoning_effort=none`을 고정하고 모델만 `gpt-4o-mini`에서 `gpt-5.4-mini-2026-03-17`로 바꿨다. 기존 100건을 기준선으로 재사용하고 GPT-5.4 mini 100건만 새로 호출했다. 이는 모델 선택 파일럿이며 production 모델 교체 결과가 아니다.

| 지표 | GPT-4o mini | GPT-5.4 mini | 변화 |
|---|---:|---:|---:|
| loose semantic hit | 76/100 | 76/100 | 0 |
| strict family semantic | 50/100 | 54/100 | +4 |
| acceptable family semantic | 52/100 | 55/100 | +3 |
| actual application strict @8 | 55/100 | 56/100 | +1 |
| simulated evidence strict @8 | 82/100 | 78/100 | -4 |
| simulated evidence acceptable @8 | 84/100 | 80/100 | -4 |
| sensory acceptable @20 | 38/40 | 37/40 | -1 |
| gold-negative 오탐 @8 | 0/7 | 0/7 | 유지 |
| filter-defense acceptable @8 | 3/7 | 6/7 | +3 |

GPT-5.4 mini는 메뉴군 이름 이해는 3~4건 늘었지만, simulated strict/acceptable @8의 짝별 변화는 7건 손실·3건 회수로 순감 4건이었다. 손실은 composite filter 4건, sensory 2건, 모호한 음성 질의 1건이고, 회수는 filter-defense 3건이다. 손실 시 해석 상태도 `AMBIGUOUS` 2건, `NO_FOOD_SIGNAL` 3건, `MATCHABLE` 2건으로 단일 원인에 국한되지 않았다. 따라서 이 100개 표본만으로 상위 모델이 전체 검색 정답률을 개선한다고 볼 근거는 없으며, 현재 prompt 그대로의 전면 교체는 채택하지 않는다.

비용은 `$0.00836715 → $0.04819275`로 약 5.76배였고, 평균 지연시간은 `1321.3ms → 1076.4ms`, p95는 `2047.9ms → 1389.8ms`였다. GPT-5.4 mini 호출 100건의 provider/format 실패, 429, 중복 호출은 모두 0이다. 산출물은 ignored 로컬 경로 `performance/search-eval-v2/artifacts/eval-20260824-model-gpt54mini-paired100-v2/`에 있으며 요청별 checkpoint, 양쪽 aggregate, 짝비교 summary와 SHA-256을 포함한다.

## 발표·Notion용 핵심 숫자

1. provider/format 성공: 고유 질의 `2,000/2,000 = 100.00%` (Wilson 95% CI `99.81–100.00%`)
2. LLM semantic 성공: `1,538/2,000 = 76.90%` (Wilson 95% CI `75.00–78.69%`)
3. legacy one-way 성공: `1,256/2,000 = 62.80%` (Wilson 95% CI `60.66–64.89%`)
4. LLM 보충 predicate의 후보 풀 기준 actual bidirectional 성공: `1,280/2,000 = 64.00%` (Wilson 95% CI `61.87–66.07%`)
5. 양방향은 legacy 대비 고유 질의 `24건`, `+1.20%p` 순증가했고 순감소는 0건이었다.
6. 실제 무정답 음성 질의 오탐은 `0/375 = 0.00%`였다. 답이 있는 call의 abstention은 `1,496/9,085 = 16.47%`였고, Issue #616 전 기본 검색은 이를 구제하지 못했지만 보정 후 `772건`을 구제했다.
7. top-1 안정률은 `93.71%`, 5회 결과의 평균 pairwise Jaccard는 `0.8803`이었다.
8. 채팅 분석 비용은 `$0.779856`, 중복 방지 오류의 초과 12건까지 포함한 실제 채팅 비용은 `$0.780873`, embedding을 포함한 이번 실행 총비용은 `$0.831751`이었다.

## 통합 손실과 랭킹

- semantic에서 actual predicate까지의 고유 질의 통합 유실: `76.90% - 64.00% = 12.90%p`
- Issue #616 전 기본 검색 단독은 답이 있는 `1,817`개 고유 질의 중 `0건`을 맞혔다. 남은 문장 전체를 하나의 literal substring으로 찾던 predicate 특성상 자연어 요청 문구가 붙은 메뉴명을 회수하지 못했다.
- LLM 보충 후보 풀에서는 답이 있는 고유 질의 `1,100/1,817 = 60.54%`, 최종 사용자 노출 8개에서는 `787/1,817 = 43.31%`가 과반 반복 성공했다.
- 전체 고유 질의에서 기본 검색→LLM 보충→상위 8개 병합의 최종 성공은 `967/2,000 = 48.35%`였다. 후보 풀 성공 `64.00%`와 혼동하지 않는다.
- 최종 Recall@1 / @3 / @5 / @8: `25.31% / 30.06% / 31.32% / 32.46%`
- 최종 MRR / nDCG@8: `0.2986 / 0.3425`
- latency mean / p50 / p95 / p99 / max: `1038.7 / 1025.3 / 1433.0 / 1943.8 / 11372.6 ms`
- 2초 초과율: `0.89%`
- 폐점, 비공개·과거 버전 누출: 각각 `0.00%`
- 지역, 가격, 카테고리 필터 위반: 각각 `0.00%`

## Issue #616 deterministic 메뉴명 선회수 재분석

기존 10,000회 LLM 체크포인트를 그대로 사용하고, 최대 100자 `remainingKeyword`의 2자 이상 부분문자열을 indexed exact `IN`과 같은 MySQL `utf8mb4_0900_ai_ci` collation의 anti-join으로 조회해 non-retired current published 메뉴명을 요청당 한 번 찾는다. 전체 결과에서 겹치는 이름 중 가장 구체적인 이름을 먼저 남긴 뒤 최종 100개만 바인딩하고, 후보 조회에서는 visible current published 메뉴만 허용한다. 따라서 hidden current 긴 이름은 짧은 메뉴명으로의 잘못된 후퇴를 막을 수 있지만 결과로 노출되지는 않는다. 아래 차이는 새 LLM 출력이 아니라 검색 predicate와 최종 병합 변화만 반영하며 추가 API 비용은 0원이다.

최초 재분석 구현 기준은 당시 `origin/dev`의 `f188bd701c26ed8f73efd17a163f2f832b84b300`이었다. 보강된 재분석 게이트는 재생성 dataset SHA-256, 메타데이터·레코드 request fingerprint, 모든 deterministic request ID를 먼저 검증하고 baseline/#616을 같은 10,000건으로 동시에 계산한다. 두 결과는 `reanalysis/pre-issue-616/`과 `reanalysis/issue-616-most-specific/`에 분리하며 true-no-answer 오탐이 증가하면 결과 파일을 쓰기 전에 실패한다. canonical checkpoint SHA-256은 checkpoint 완성 시 `run-metadata.json`에 고정하고, 재분석은 현재 파일과 먼저 대조해 불일치하면 기존 결과를 덮어쓰기 전에 실패한다. `run-metadata.json`에는 `cli.py`를 포함한 분석 소스 SHA-256도 기록한다. 원래 10,000회 OpenAI 호출의 실행 커밋과 재분석 구현 커밋을 혼동하지 않는다.

| 지표 | Issue #616 전 | Issue #616 재분석 | 변화 |
|---|---:|---:|---:|
| 전체 고유 질의 최종 성공 | 967/2,000 = 48.35% | 1,213/2,000 = 60.65% | +246, +12.30%p |
| answerable 고유 질의 최종 성공 | 787/1,817 = 43.31% | 1,033/1,817 = 56.85% | +246, +13.54%p |
| answerable call 최종 성공 | 3,913/9,085 = 43.07% | 5,166/9,085 = 56.86% | +1,253, +13.79%p |
| 실제 사용자 결과까지 실패한 abstention | 1,496/9,085 = 16.47% | 724/9,085 = 7.97% | -772, -8.50%p |
| true-no-answer 오탐 | 0/375 = 0.00% | 0/375 = 0.00% | 유지 |
| 전체 gold-negative 오탐 | 12/915 = 1.31% | 12/915 = 1.31% | 유지 |

층화 100질의 paired 표본에서도 최종 상위 8개 정답은 `43/93 = 46.24%`에서 `56/93 = 60.22%`로 13건 증가했고, LLM abstention 중 6건을 deterministic 경로가 구제했으며 모든 gold-negative와 true-no-answer 오탐은 각각 `0/7`, `0/4`를 유지했다. 1차 구현에서 `칼칼한 마라탕`을 더 짧은 `마라탕`으로 후퇴시켜 늘었던 strict filter-defense 오탐 30 call은 가장 구체적인 긴 메뉴명 우선 규칙으로 제거했다.

## 과거 30×3 평가와 비교

| 지표 | 과거 30질의×3회 | v2 고유 질의 단위 |
|---|---:|---:|
| LLM 의미 성공 | 81/90 = 90.00% | 1,538/2,000 = 76.90% |
| legacy one-way DB 호환 | 64/90 = 71.11% | 1,256/2,000 = 62.80% |
| 의미→legacy one-way 유실 | 17/90 = 18.89%p | 14.10%p |
| 의미→actual bidirectional 유실 | 과거 actual 없음 | 12.90%p |

과거 평가는 서로 다른 매장 corpus가 없는 30개 합성 메뉴·30개 질의의 call 단위 수치다. v2는 매장 500개와 메뉴 5,000개를 포함하고 고유 질의 2,000개를 통계 단위로 사용하므로 단순 전후 성능 개선치로 해석하면 안 된다.

## Embedding 비교

| 모델 | Recall@1 | Recall@5 | Recall@8 | MRR | 배치 호출 | 비용 |
|---|---:|---:|---:|---:|---:|---:|
| `text-embedding-3-small` | 19.03% | 28.51% | 33.70% | 0.4528 | 14 | $0.006784 |
| `text-embedding-3-large` | 21.75% | 32.09% | 37.24% | 0.5034 | 14 | $0.044094 |

유사도 계산은 같은 2,000×5,000 corpus에서 NumPy `matmul + argpartition`으로 수행했다.

## 호출 원장과 재개 사고

- 분석 대상 채팅 결과: 10,000건
- 실제 성공 채팅 레코드: 10,012건
- HTTP 시도: 10,013회
- 재시도 / 최종 실패: 1 / 0
- embedding 배치 호출: 28회
- 초과 12건 원인: 파일럿 평균 토큰 추정치가 request fingerprint에 포함되어 최초 본 실행에서 동일 요청 ID가 달라짐
- 조치: 원본 체크포인트를 보존하고 실제 API request body가 동일한 기록만 canonical checkpoint로 이관했으며, `(queryId, repeatIndex)` 충돌에서는 파일럿 응답을 우선했다.
- canonicalization 결과: 원본 1,340건 → 유효 1,328건, 동등 중복 12건, 비동등 거부 0건

초과 12건은 10,000건 분석 표본에 포함하지 않았고 실제 비용 원장에만 포함했다.

## 재현 명령

`performance/search-eval-v2`에서 번들 Python 또는 NumPy가 설치된 Python을 사용한다.

```powershell
$env:PYTHONPATH='src'
python -m unittest discover -s tests -v
python -m miriyum_search_eval generate --artifact-dir artifacts/eval-20260824-post-merge
python -m miriyum_search_eval pilot --artifact-dir artifacts/eval-20260824-post-merge
python -m miriyum_search_eval run --artifact-dir artifacts/eval-20260824-post-merge
python -m miriyum_search_eval reanalyze --artifact-dir artifacts/eval-20260824-deterministic-menu-full --predicate-variant issue-616-most-specific
python -m miriyum_search_eval migrate-checkpoint --artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs --source-artifact-dir artifacts/eval-20260824-deterministic-menu-full
python -m miriyum_search_eval reanalyze --artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs --predicate-variant issue-616-most-specific
python -m miriyum_search_eval structured-reanalyze --artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs
python -m miriyum_search_eval hybrid-reanalyze --artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs --source-artifact-dir artifacts/eval-20260824-post-merge
python -m miriyum_search_eval report --artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs
python -m miriyum_search_eval prompt-pilot --artifact-dir artifacts/eval-20260824-attribute-prompt-pilot --source-artifact-dir artifacts/eval-20260824-gold-v2-1-cutoffs
python -m miriyum_search_eval prompt-run --artifact-dir artifacts/eval-20260824-attribute-prompt-pilot
python -m miriyum_search_eval embeddings --artifact-dir artifacts/eval-20260824-post-merge
python -m miriyum_search_eval report --artifact-dir artifacts/eval-20260824-post-merge
```

`OPENAI_API_KEY`는 환경변수에서만 읽는다. checkpoint·결과·로그에는 키와 원문 인증 헤더를 기록하지 않는다.

## 검증과 한계

- Python 하네스 테스트: H actual predicate replay 경계 테스트를 포함해 113개 전체 통과
- 영향받은 Java 단위 테스트: search interpreter·expansion·query·service·controller·OpenAPI 계약 범위 186개 통과
- `IntegratedStoreSearchRepositoryIT`: Docker/Testcontainers MySQL로 22개 통과. UCA expansion인 `ß ↔ ss`에서도 hidden current 긴 이름이 짧은 visible 이름으로 후퇴하지 않음을 포함한다.
- representative MySQL `EXPLAIN`/timing은 아직 실행하지 않아 CI 또는 별도 성능 검증 대기다. 후보 행마다 실행되던 correlated `NOT EXISTS`와 후보 조회의 non-sargable `LOCATE`는 제거했다. V71의 `name` 선두 복합 index에 최대 4,950개 exact 후보를 조회하고, 같은 collation anti-join으로 가장 구체적인 이름을 계산한 후 최종 100개만 검색 query에 바인딩한다.
- 전체 backend suite는 저장소 정책에 따라 로컬에서 실행하지 않았으며 `PENDING GitHub CI`다.
- H는 병합된 SQL predicate를 합성 corpus에 오프라인으로 결정적 모사하되 구형 structured checkpoint를 재생한 값이다. 현재 provider schema와 운영 DB를 end-to-end로 실행한 actual application 결과가 아니다.
- 기본 검색 proxy는 합성 manifest에 주소가 없어 매장명·region·current published 메뉴명만 평가했다. 구조화 region·가격·분위기·category span은 고정 합성 vocabulary와 템플릿으로 결정적으로 제거해 `remainingKeyword`를 만들며, 운영 vocabulary 전체를 실제 MySQL에서 실행한 수치는 아니다.
- 2026-08-24의 over-abstention 완화 prompt 수정안은 별도 층화 파일럿 100회에서 안전 게이트를 통과했지만 동일 질의의 answerable abstention이 `14/93 = 15.05%`에서 `33/93 = 35.48%`로 악화되고 semantic hit가 `80/100`에서 `66/100`으로 감소해 채택하지 않았다. true-no-answer 오탐은 두 조건 모두 `0/4`였다. 본 10,000회는 실행하지 않았고 production prompt와 정본 계약은 원복했다.
- 합성 언어와 합성 매장 분포이므로 실제 사용자·운영 데이터에 대한 외적 타당성은 제한된다.
- mode별 메뉴 오탐 건수는 gold family 밖 반환 메뉴의 누적 건수이며 무정답 query-level false-positive rate와 다른 지표다.
- 10,000개 반복 응답은 안정성 분석에 사용했고 Wilson 신뢰구간은 독립 표본처럼 부풀리지 않고 고유 질의 2,000개로 계산했다.

현재 worktree에서 기존 baseline 상세 결과는 ignored 로컬 경로 `performance/search-eval-v2/artifacts/eval-20260824-post-merge/`, 초기 Issue #616 재분석 결과는 `performance/search-eval-v2/artifacts/eval-20260824-deterministic-menu-full/`, v2.1 strict/acceptable 및 @8/@20/@50/전체 후보와 검증된 10,000건 paired reanalysis는 `performance/search-eval-v2/artifacts/eval-20260824-gold-v2-1-cutoffs/`에 있다. v2.1 paired summary는 true-no-answer 오탐 `0→0`, 전체 gold-negative 오탐 `12→12`, canonical checkpoint SHA-256 `2f5f0274f739db079526d7d6d53b0f3fe0df2187317862b6cbd308f011294834`를 기록한다. 이 경로들은 Git에 포함된 영구 배포 위치가 아니다.
