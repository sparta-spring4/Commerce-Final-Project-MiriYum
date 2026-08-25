from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _pct(value: float) -> str:
    return f"{value * 100:.2f}%"


def _cutoff_success_key(gold: str, label: str) -> str:
    suffix = "All" if label == "all" else label
    return f"{gold}Final{suffix}"


def render_report(
    *, aggregate: dict[str, Any], pilot_gate: dict[str, Any],
    metadata: dict[str, Any], embedding_results: dict[str, Any] | None = None,
    diagnostics: dict[str, Any] | None = None,
) -> str:
    unique = aggregate["uniqueQuerySuccess"]
    usage = aggregate["usage"]
    latency = aggregate["latencyMs"]
    embedding_results = embedding_results or {}
    diagnostics = diagnostics or {}
    paid = metadata.get("paidExecution", {})
    reuse = metadata.get("checkpointReuse", {})
    embedding_cost = sum(
        float(result.get("usage", {}).get("costUsd", 0.0))
        for result in embedding_results.values()
    )
    lines = [
        "# MiriYum 검색 통합 평가 v2",
        "",
        f"- 실행 시각(UTC): {metadata['executedAtUtc']}",
        f"- 평가 커밋: `{metadata['commitSha']}`",
        *([f"- 재분석 base 커밋: `{metadata['analysisCommitSha']}`; dirty `{metadata.get('analysisWorkingTreeDirty', False)}`; predicate `{metadata.get('analysisPredicate', '')}`"] if metadata.get("analysisCommitSha") else []),
        f"- 데이터: seed `{metadata['seed']}`, schema `{metadata['schemaVersion']}`",
        f"- 요청/반환 모델: `{metadata['requestedModel']}` / `{metadata.get('returnedModels', [])}`",
        f"- 호출: {aggregate['statisticalUnit']['calls']:,}회, 고유 질의 {aggregate['statisticalUnit']['uniqueQueries']:,}개",
        f"- paid provider calls: {paid.get('paidProviderCalls', aggregate['statisticalUnit']['calls']):,}; "
        f"duplicate overhead: {paid.get('duplicateOverheadCalls', 0):,}; "
        f"actual paid chat cost: ${paid.get('actualPaidCostUsd', usage['costUsd']):.6f}",
        *([f"- checkpoint reuse: {reuse.get('migrated', 0):,} calls; new provider calls: "
           f"{reuse.get('newProviderCalls', 0):,}; incremental cost: ${reuse.get('incrementalCostUsd', 0.0):.6f}"] if reuse else []),
        *([f"- source paid ledger: {reuse.get('sourcePaidExecution', {}).get('paidProviderCalls', 0):,} calls; "
           f"${reuse.get('sourcePaidExecution', {}).get('actualPaidCostUsd', 0.0):.6f}"] if reuse else []),
        "",
        "## 핵심 숫자",
        "",
        f"1. provider/format 고유 질의 성공률: {_pct(unique['providerFormat']['rate'])} (Wilson 95% CI {_pct(unique['providerFormat']['wilson95']['low'])}–{_pct(unique['providerFormat']['wilson95']['high'])})",
        f"2. legacy loose semantic 고유 질의 성공률: {_pct(unique['semantic']['rate'])}",
        f"- 엄격 메뉴군 의미 성공: {_pct(unique.get('strictFamilySemantic', unique['semantic'])['rate'])}",
        f"- 허용 메뉴군 의미 성공: {_pct(unique.get('acceptableFamilySemantic', unique['semantic'])['rate'])}",
        f"3. legacy one-way counterfactual DB 호환 성공률: {_pct(unique['oneWay']['rate'])}",
        f"4. actual current bidirectional 성공률: {_pct(unique['bidirectional']['rate'])}",
        f"5. 최종 사용자 검색 성공률(기본 검색→LLM 보충): {_pct(unique.get('finalApplication', unique['bidirectional'])['rate'])}",
        f"6. Recall@5 / MRR / nDCG@8: {_pct(aggregate['ranking']['recall@5'])} / {aggregate['ranking']['mrr']:.4f} / {aggregate['ranking']['ndcg@8']:.4f}",
        f"7. top-1 안정률 / pairwise Jaccard: {_pct(aggregate['stability']['meanTop1Stability'])} / {aggregate['stability']['meanPairwiseJaccard']:.4f}",
        f"8. latency p50/p95/p99/max: {latency['p50']:.1f}/{latency['p95']:.1f}/{latency['p99']:.1f}/{latency['max']:.1f} ms; 2초 초과 {_pct(latency['over2SecondsRate'])}",
        f"- answerable-query abstention: {_pct(diagnostics.get('answerableAbstentionRate', 0.0))} "
        f"({diagnostics.get('answerableAbstentions', 0):,}/{diagnostics.get('answerableCalls', 0):,} calls)",
        f"- 실제 사용자 결과까지 실패한 answerable abstention: {_pct(diagnostics.get('userVisibleAnswerableAbstentionRate', 0.0))} "
        f"({diagnostics.get('userVisibleAnswerableAbstentions', 0):,}/{diagnostics.get('answerableCalls', 0):,} calls)",
        "",
        "## 고유 질의 성공률과 Wilson 95% 신뢰구간",
        "",
        "| 지표 | 성공/고유 질의 | 성공률 | Wilson 95% CI |",
        "|---|---:|---:|---:|",
        *[
            f"| {label} | {unique[key]['successes']:,}/{unique[key]['total']:,} | {_pct(unique[key]['rate'])} | "
            f"{_pct(unique[key]['wilson95']['low'])}–{_pct(unique[key]['wilson95']['high'])} |"
            for key, label in (
                ("providerFormat", "provider/format"),
                ("semantic", "LLM semantic"),
                ("oneWay", "legacy one-way"),
                ("bidirectional", "actual bidirectional"),
                ("original", "기본 검색 단독"),
                ("finalApplication", "최종 사용자 검색"),
            )
            if key in unique
        ],
        "",
        "## 과거 30×3과 직접 비교",
        "",
        "| 지표 | 과거 30질의×3회(제공 실측) | v2 고유 질의 단위 |",
        "|---|---:|---:|",
        f"| LLM 의미 성공 | 81/90 = 90.00% | {_pct(unique['semantic']['rate'])} |",
        f"| one-way DB 호환 | 64/90 = 71.11% | {_pct(unique['oneWay']['rate'])} |",
        f"| 의미→DB 통합 유실 | 17/90 = 18.89%p | {_pct(max(0.0, unique['semantic']['rate'] - unique['oneWay']['rate']))}p |",
        "",
        "과거 원자료는 저장소/GitHub에서 확인되지 않아 제공 실측값만 비교했으며, 과거 strict one-way와 v2 semantic/bidirectional 정의를 합치지 않았다.",
        "",
        "## 정답 정의와 후보 수별 재평가",
        "",
        "기존 strict gold는 과거 비교를 위해 그대로 유지했다. sensory 질의의 acceptable gold는 "
        "고정 사전에서 재료·맛·조리법·국물 속성이 모두 같은 메뉴군만 허용하며 LLM으로 생성하지 않았다.",
        "",
        "| 후보 범위 | strict 고유 성공 | acceptable 고유 성공 | strict call Recall | acceptable call Recall |",
        "|---|---:|---:|---:|---:|",
        *[
            f"| {'전체 후보' if label == 'all' else label} | "
            f"{_pct(unique.get(_cutoff_success_key('strict', label), {'rate': 0.0})['rate'])} | "
            f"{_pct(unique.get(_cutoff_success_key('acceptable', label), {'rate': 0.0})['rate'])} | "
            f"{_pct(aggregate.get('candidateCutoffs', {}).get(label, {}).get('strictRecall', 0.0))} | "
            f"{_pct(aggregate.get('candidateCutoffs', {}).get(label, {}).get('acceptableRecall', 0.0))} |"
            for label in ("@8", "@20", "@50", "all")
        ],
        "",
        *(
            ["sensory 800개만 보면:", "", "| 후보 범위 | strict 고유 성공 | acceptable 고유 성공 |", "|---|---:|---:|", *[
                f"| {'전체 후보' if label == 'all' else label} | "
                f"{_pct(aggregate['queryTypeUniqueSuccess']['sensory_without_menu'][_cutoff_success_key('strict', label)]['rate'])} | "
                f"{_pct(aggregate['queryTypeUniqueSuccess']['sensory_without_menu'][_cutoff_success_key('acceptable', label)]['rate'])} |"
                for label in ("@8", "@20", "@50", "all")
            ], ""]
            if aggregate.get("queryTypeUniqueSuccess", {}).get("sensory_without_menu") else []
        ),
        "## actual과 counterfactual 구분",
        "",
        "| 구분 | 구현 상태 | 고유 질의 성공률 |",
        "|---|---|---:|",
        f"| actual current bidirectional | 병합된 application predicate @ `{metadata['commitSha'][:12]}` | {_pct(unique['bidirectional']['rate'])} |",
        f"| legacy one-way counterfactual | Issue #589 병합 전 규칙을 같은 응답에 적용 | {_pct(unique['oneWay']['rate'])} |",
        f"| original search proxy | 남은 문장 전체 literal predicate를 합성 corpus에 적용 | {_pct(unique.get('original', {'rate': 0.0})['rate'])} |",
        f"| final application proxy | 기본 검색 결과 뒤에 LLM 보충 결과 병합 | {_pct(unique.get('finalApplication', unique['bidirectional'])['rate'])} |",
        "",
        f"고유 질의 기준 양방향 순증가 {diagnostics.get('uniqueBidirectionalGains', 0):,}건, "
        f"순감소 {diagnostics.get('uniqueBidirectionalRegressions', 0):,}건이다.",
        f"현재 semantic→actual predicate 통합 유실은 {_pct(max(0.0, unique['semantic']['rate'] - unique['bidirectional']['rate']))}p다.",
        "",
        "## 안전·필터",
        "",
        f"- 실제 무정답 음성 질의 false-positive rate: {_pct(aggregate['negativeFalsePositiveRate'])} "
        f"({diagnostics.get('trueNoAnswerFalsePositives', 0):,}/{diagnostics.get('trueNoAnswerCalls', aggregate.get('negativeFalsePositiveDenominator', 0)):,})",
        f"- abstention rate: {_pct(aggregate.get('abstentionRate', 0.0))}",
        f"- 폐점 누출 / 비공개·과거 누출: {_pct(aggregate['leakageAndFilters']['closedLeakRate'])} / {_pct(aggregate['leakageAndFilters']['privateOrHistoricalLeakRate'])}",
        f"- 지역 / 가격 / 카테고리 필터 위반: {_pct(aggregate['leakageAndFilters'].get('regionFilterViolationRate', 0.0))} / {_pct(aggregate['leakageAndFilters'].get('priceFilterViolationRate', 0.0))} / {_pct(aggregate['leakageAndFilters'].get('categoryFilterViolationRate', 0.0))}",
        f"- 파일럿 gate: `{pilot_gate['passed']}`; 재개 중복 호출 {pilot_gate['resumeDuplicateCalls']}회; 예상 completion 비용 ${pilot_gate['projectedCompletionCostUsd']:.6f}",
        "",
        "### 매칭 mode별 회수/오탐 메뉴 건수 (10,000 calls 합계)",
        "",
        "| mode | 회수 | gold family 밖 오탐 |",
        "|---|---:|---:|",
        *[
            f"| {mode} | {aggregate['matchCounts'][mode]:,} | {aggregate['falsePositiveCounts'][mode]:,} |"
            for mode in ("exact", "forward", "reverse", "alias")
        ],
        "",
        "mode별 오탐은 반환된 메뉴 중 gold family 밖 메뉴의 누적 건수이며, 무정답 질의의 query-level false-positive rate와 다른 지표다.",
        "",
        "## 질의 유형별 breakdown (call 기준)",
        "",
        "| 유형 | calls | semantic | legacy one-way | LLM bidirectional | 기본 검색 | 최종 검색 | off-target result |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
        *[
            f"| {query_type} | {row['calls']:,} | {_pct(row['semanticRate'])} | {_pct(row['oneWayRate'])} | "
            f"{_pct(row['bidirectionalRate'])} | {_pct(row.get('originalRate', 0.0))} | "
            f"{_pct(row.get('finalApplicationRate', row['bidirectionalRate']))} | {_pct(row['falsePositiveRate'])} |"
            for query_type, row in aggregate["queryTypeBreakdown"].items()
        ],
        "",
        "## Embedding 비교",
        "",
    ]
    if embedding_results:
        lines.extend(["| 모델 | Recall@1 | Recall@5 | MRR | 입력 토큰 | 배치 호출 | 비용 |", "|---|---:|---:|---:|---:|---:|---:|"])
        for model, result in embedding_results.items():
            lines.append(
                f"| {model} | {_pct(result['ranking']['recall@1'])} | {_pct(result['ranking']['recall@5'])} | "
                f"{result['ranking']['mrr']:.4f} | {result['usage']['inputTokens']:,} | {result.get('providerBatchCalls', 0):,} | ${result['usage']['costUsd']:.6f} |"
            )
        lines.append("")
        lines.append(
            f"채팅 실제 결제 비용과 embedding 비용을 합친 이번 실행 비용은 "
            f"${float(paid.get('actualPaidCostUsd', usage['costUsd'])) + embedding_cost:.6f}이다."
        )
    else:
        lines.append("- NOT RUN")
    lines.extend([
        "",
        "## 한계",
        "",
        "- 전부 결정적으로 생성한 합성 데이터이므로 실제 사용자 언어와 매장 분포의 외적 타당성은 제한된다.",
        "- legacy loose semantic hit는 고정 메뉴·별칭뿐 아니라 단일 속성 겹침도 허용한 과거 지표라 메뉴군 이해를 과대평가할 수 있다.",
        "- strict/acceptable family semantic hit와 속성별 이해도는 평가 LLM이 아닌 고정 사전에 대한 결정적 판정이다.",
        "- 10,000회 반복은 출력 안정성 분석에만 사용했고 Wilson 신뢰구간의 표본 수는 고유 질의 수다.",
        "- actual predicate는 병합된 SQL predicate를 합성 corpus에 결정적으로 모사한 값이며, 실제 운영 DB에 질의를 실행한 수치는 아니다.",
        "- 기본 검색 proxy는 합성 manifest에 주소가 없어 store name·region·현재 공개 메뉴명만 평가했으며, RuleInterpreter의 구조화 토큰 제거 전 원문을 remaining keyword로 사용한 보수적 근사다.",
        "- 질의 유형별 off-target result는 답변 후보가 존재하지만 반환 매장에 gold 교집합이 없는 call의 비율이며, 개별 메뉴 precision과 동일하지 않다.",
        "- USD 비용은 응답 usage와 실행 config의 공식 단가 산식이며 KRW 환산은 환율 변동 때문에 원장으로 사용하지 않는다.",
        "",
    ])
    return "\n".join(lines)
