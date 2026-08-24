from __future__ import annotations

from itertools import combinations
import math
from typing import Iterable


def ranking_metrics(predicted: list[str], relevant: list[str], ks: Iterable[int] = (1, 3, 5, 8)) -> dict[str, float]:
    relevant_set = set(relevant)
    denominator = len(relevant_set)
    result: dict[str, float] = {}
    for k in ks:
        result[f"recall@{k}"] = (len(set(predicted[:k]) & relevant_set) / denominator) if denominator else float(not predicted)
        ideal = sum(1.0 / math.log2(index + 2) for index in range(min(k, denominator)))
        dcg = sum((1.0 / math.log2(index + 2)) for index, value in enumerate(predicted[:k]) if value in relevant_set)
        result[f"ndcg@{k}"] = dcg / ideal if ideal else float(not predicted)
    first_relevant = next((index for index, value in enumerate(predicted) if value in relevant_set), None)
    result["mrr"] = 0.0 if first_relevant is None else 1.0 / (first_relevant + 1)
    return result


def stability_metrics(rankings: list[list[str]]) -> dict[str, float]:
    if not rankings:
        return {"top1Stability": 0.0, "pairwiseJaccard": 0.0}
    top_values = [ranking[0] if ranking else None for ranking in rankings]
    counts = {value: top_values.count(value) for value in set(top_values)}
    top1 = max(counts.values()) / len(top_values)
    scores = []
    for left, right in combinations(rankings, 2):
        left_set, right_set = set(left), set(right)
        union = left_set | right_set
        scores.append(len(left_set & right_set) / len(union) if union else 1.0)
    return {"top1Stability": top1, "pairwiseJaccard": sum(scores) / len(scores) if scores else 1.0}


def wilson_interval(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if total <= 0 or successes < 0 or successes > total:
        raise ValueError("Wilson interval requires 0 <= successes <= total")
    proportion = successes / total
    denominator = 1 + z * z / total
    center = (proportion + z * z / (2 * total)) / denominator
    margin = z * math.sqrt((proportion * (1 - proportion) + z * z / (4 * total)) / total) / denominator
    return center - margin, center + margin
