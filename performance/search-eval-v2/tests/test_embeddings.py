from pathlib import Path
import tempfile
import unittest

import numpy as np

from miriyum_search_eval.embeddings import embed_texts, topk_cosine


class FakeEmbeddingTransport:
    def __init__(self):
        self.calls = 0

    def __call__(self, model, texts, timeout_seconds):
        self.calls += 1
        vectors = [[float(len(text)), float(index + 1)] for index, text in enumerate(texts)]
        return vectors, sum(len(text) for text in texts), model


class EmbeddingTest(unittest.TestCase):
    def test_vectorized_cosine_returns_top_k_indices(self):
        corpus = np.asarray([[1.0, 0.0], [0.0, 1.0], [0.8, 0.2]], dtype=np.float32)
        queries = np.asarray([[1.0, 0.0], [0.0, 1.0]], dtype=np.float32)

        indices, scores = topk_cosine(queries, corpus, k=2, batch_size=1)

        np.testing.assert_array_equal(indices, [[0, 2], [1, 2]])
        self.assertEqual(scores.shape, (2, 2))

    def test_embedding_batches_resume_without_duplicate_api_calls(self):
        transport = FakeEmbeddingTransport()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = embed_texts(
                ids=["a", "b", "c"], texts=["하나", "둘", "셋"], model="text-embedding-3-small",
                artifact_dir=root, batch_size=2, transport=transport,
            )
            first_call_count = transport.calls
            second_transport = FakeEmbeddingTransport()
            second = embed_texts(
                ids=["a", "b", "c"], texts=["하나", "둘", "셋"], model="text-embedding-3-small",
                artifact_dir=root, batch_size=2, transport=second_transport,
            )

        self.assertEqual(first_call_count, 2)
        self.assertEqual(second_transport.calls, 0)
        np.testing.assert_array_equal(first.vectors, second.vectors)
        self.assertEqual(first.input_tokens, second.input_tokens)

    def test_embedding_batch_retries_timeout_before_checkpointing_success(self):
        class TimeoutThenSuccess(FakeEmbeddingTransport):
            def __call__(self, model, texts, timeout_seconds):
                self.calls += 1
                if self.calls == 1:
                    raise TimeoutError("temporary")
                return [[1.0, 0.0] for _ in texts], 3, model

        transport = TimeoutThenSuccess()
        sleeps = []
        with tempfile.TemporaryDirectory() as directory:
            result = embed_texts(
                ids=["a"], texts=["하나"], model="text-embedding-3-small",
                artifact_dir=Path(directory), transport=transport,
                max_retries=2, backoff_base_seconds=0.01, sleep=sleeps.append,
            )

        self.assertEqual(transport.calls, 2)
        self.assertEqual(len(sleeps), 1)
        self.assertEqual(result.input_tokens, 3)


if __name__ == "__main__":
    unittest.main()
