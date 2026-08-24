from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path
from typing import Any, Iterable


def write_json(path: Path, value: Any) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return path


def write_jsonl(path: Path, values: Iterable[dict[str, Any]]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for value in values:
            handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    return path


def write_dataset_artifacts(dataset: dict[str, Any], root: Path) -> list[Path]:
    records = [{"recordType": "metadata", **dataset["metadata"]}]
    for key, singular in (("families", "family"), ("stores", "store"), ("menus", "menu"), ("queries", "query")):
        records.extend({"recordType": singular, **value} for value in dataset[key])
    manifest = write_jsonl(root / "manifest.jsonl", records)
    metadata = write_json(root / "metadata.json", dataset["metadata"])
    queries = write_jsonl(root / "queries.jsonl", dataset["queries"])
    return [manifest, metadata, queries]


def write_sha256_manifest(files: Iterable[Path], output_path: Path) -> dict[str, str]:
    root = output_path.parent
    hashes = {
        path.relative_to(root).as_posix(): sha256(path.read_bytes()).hexdigest()
        for path in sorted(files)
    }
    write_json(output_path, hashes)
    return hashes
