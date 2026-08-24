from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import json
import os
from pathlib import Path
import time
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import numpy as np


EMBEDDING_PRICES = {"text-embedding-3-small": 0.02, "text-embedding-3-large": 0.13}


class RetryableEmbeddingError(RuntimeError):
    pass


@dataclass(frozen=True)
class EmbeddingResult:
    ids: tuple[str, ...]
    vectors: np.ndarray
    input_tokens: int
    requested_model: str
    returned_models: tuple[str, ...]
    cost_usd: float


def topk_cosine(query_vectors: np.ndarray, corpus_vectors: np.ndarray, *, k: int, batch_size: int = 256) -> tuple[np.ndarray, np.ndarray]:
    queries = np.asarray(query_vectors, dtype=np.float32)
    corpus = np.asarray(corpus_vectors, dtype=np.float32)
    if queries.ndim != 2 or corpus.ndim != 2 or queries.shape[1] != corpus.shape[1]:
        raise ValueError("query and corpus matrices must be 2D with equal dimensions")
    if not len(corpus) or k < 1:
        raise ValueError("non-empty corpus and positive k are required")
    actual_k = min(k, len(corpus))
    queries = queries / np.maximum(np.linalg.norm(queries, axis=1, keepdims=True), 1e-12)
    corpus = corpus / np.maximum(np.linalg.norm(corpus, axis=1, keepdims=True), 1e-12)
    index_batches, score_batches = [], []
    for start in range(0, len(queries), batch_size):
        similarities = queries[start:start + batch_size] @ corpus.T
        partition = np.argpartition(similarities, -actual_k, axis=1)[:, -actual_k:]
        partition_scores = np.take_along_axis(similarities, partition, axis=1)
        order = np.argsort(-partition_scores, axis=1, kind="stable")
        index_batches.append(np.take_along_axis(partition, order, axis=1))
        score_batches.append(np.take_along_axis(partition_scores, order, axis=1))
    return np.vstack(index_batches), np.vstack(score_batches)


class OpenAIEmbeddingTransport:
    def __init__(self, api_key: str, base_url: str = "https://api.openai.com"):
        if not api_key:
            raise ValueError("OPENAI_API_KEY is required")
        self._api_key = api_key
        self._url = base_url.rstrip("/") + "/v1/embeddings"

    def __call__(self, model: str, texts: list[str], timeout_seconds: float) -> tuple[list[list[float]], int, str]:
        body = json.dumps({"model": model, "input": texts, "encoding_format": "float"}, ensure_ascii=False).encode("utf-8")
        request = Request(
            self._url, data=body,
            headers={"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            if error.code == 429 or 500 <= error.code < 600:
                raise RetryableEmbeddingError(f"embedding provider retryable HTTP {error.code}") from error
            raise RuntimeError(f"embedding provider HTTP {error.code}") from error
        except (URLError, TimeoutError) as error:
            raise TimeoutError("embedding request timed out or was unreachable") from error
        data = sorted(payload["data"], key=lambda value: value["index"])
        return [value["embedding"] for value in data], int(payload["usage"]["prompt_tokens"]), str(payload.get("model", model))


def _load_checkpoint(path: Path) -> dict[str, dict[str, Any]]:
    records = {}
    if not path.exists():
        return records
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line:
            continue
        record = json.loads(line)
        batch_id = record.get("batchId")
        if not batch_id or batch_id in records:
            raise ValueError(f"invalid embedding checkpoint at line {line_number}")
        records[batch_id] = record
    return records


def _batch_id(model: str, ids: list[str], texts: list[str]) -> str:
    material = json.dumps({"model": model, "ids": ids, "texts": texts}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "emb-" + sha256(material.encode("utf-8")).hexdigest()[:32]


def embed_texts(
    *, ids: list[str], texts: list[str], model: str, artifact_dir: Path, batch_size: int = 512,
    timeout_seconds: float = 30.0,
    transport: Callable[[str, list[str], float], tuple[list[list[float]], int, str]],
    max_retries: int = 4, backoff_base_seconds: float = 0.5,
    sleep: Callable[[float], None] = time.sleep,
) -> EmbeddingResult:
    if len(ids) != len(texts) or len(set(ids)) != len(ids):
        raise ValueError("embedding ids and texts must be equal-length with unique ids")
    if model not in EMBEDDING_PRICES:
        raise ValueError(f"unsupported embedding model: {model}")
    model_dir = artifact_dir / model
    model_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = model_dir / "embedding-checkpoint.jsonl"
    records = _load_checkpoint(checkpoint_path)
    vector_batches, returned_models = [], []
    total_tokens = 0
    for start in range(0, len(texts), batch_size):
        batch_ids = ids[start:start + batch_size]
        batch_texts = texts[start:start + batch_size]
        batch_id = _batch_id(model, batch_ids, batch_texts)
        batch_file = model_dir / f"{batch_id}.npz"
        record = records.get(batch_id)
        if record is None:
            attempts = 0
            while True:
                try:
                    vectors, input_tokens, returned_model = transport(model, batch_texts, timeout_seconds)
                    break
                except (TimeoutError, RetryableEmbeddingError):
                    if attempts >= max_retries:
                        raise
                    sleep(backoff_base_seconds * (2 ** attempts))
                    attempts += 1
            matrix = np.asarray(vectors, dtype=np.float32)
            if matrix.ndim != 2 or len(matrix) != len(batch_texts):
                raise ValueError("embedding response shape mismatch")
            temp_file = model_dir / f"{batch_id}.tmp.npz"
            np.savez_compressed(temp_file, ids=np.asarray(batch_ids), vectors=matrix)
            os.replace(temp_file, batch_file)
            record = {
                "schemaVersion": "miriyum-embedding-batch-v2",
                "batchId": batch_id,
                "requestedModel": model,
                "returnedModel": returned_model,
                "count": len(batch_ids),
                "inputTokens": input_tokens,
                "file": batch_file.name,
            }
            with checkpoint_path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
                handle.flush()
                os.fsync(handle.fileno())
            records[batch_id] = record
        if not batch_file.exists():
            raise ValueError(f"embedding batch file missing for completed batch {batch_id}")
        with np.load(batch_file, allow_pickle=False) as saved:
            saved_ids = tuple(str(value) for value in saved["ids"])
            if saved_ids != tuple(batch_ids):
                raise ValueError(f"embedding batch id order mismatch for {batch_id}")
            vector_batches.append(saved["vectors"].astype(np.float32, copy=True))
        total_tokens += int(record["inputTokens"])
        returned_models.append(str(record["returnedModel"]))
    matrix = np.vstack(vector_batches) if vector_batches else np.empty((0, 0), dtype=np.float32)
    return EmbeddingResult(
        ids=tuple(ids), vectors=matrix, input_tokens=total_tokens,
        requested_model=model, returned_models=tuple(sorted(set(returned_models))),
        cost_usd=total_tokens * EMBEDDING_PRICES[model] / 1_000_000,
    )
