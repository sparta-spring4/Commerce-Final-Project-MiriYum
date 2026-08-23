import { check } from 'k6'
import { renderSafeSearchLlmSummary } from '../search-llm/summary.js'

export const options = { thresholds: { checks: ['rate==1'] } }

export default function () {
  const rendered = renderSafeSearchLlmSummary({ metrics: {
    'http_req_duration{phase:measured,scenario:exact}': { values: { 'p(50)': 10, 'p(95)': 20, 'p(99)': 30 } },
    'http_reqs{phase:measured,scenario:exact}': { values: { count: 2, rate: 1 } },
    leaked_metric: { values: { count: 999 } },
  } }, {
    targetEnv: 'staging', runId: 'llm-01', commitSha: '0123456789abcdef0123456789abcdef01234567',
    harnessCommitSha: '0123456789abcdef0123456789abcdef01234567', fixtureSha256: 'a'.repeat(64),
    scenarios: ['exact'], budget: { totalCalls: 2, totalCostUsd: 0.01 },
    llmDeltas: { calls: 0, inputTokens: 0, outputTokens: 0, estimatedCostUsd: 0 },
  })
  check(null, {
    'summary keeps approved latency and RPS': () => JSON.parse(rendered.json).metrics.exact.httpRequestDuration.p95 === 20,
    'summary keeps aggregate LLM deltas': () => JSON.parse(rendered.json).llmDeltas.calls === 0,
    'summary omits unknown metrics and sensitive sentinels': () => !rendered.json.includes('leaked_metric') && !rendered.json.includes('searchInput') && !rendered.json.includes('storeId'),
    'summary accepts metric tags in any order': () => {
      const reversed = renderSafeSearchLlmSummary({ metrics: { 'http_req_duration{scenario:exact,phase:measured}': { values: { 'p(95)': 21 } } } }, {
        targetEnv: 'staging', runId: 'llm-02', commitSha: '0123456789abcdef0123456789abcdef01234567', harnessCommitSha: '0123456789abcdef0123456789abcdef01234567', fixtureSha256: 'b'.repeat(64), scenarios: ['exact'], budget: { totalCalls: 0, totalCostUsd: 0 }, llmDeltas: null,
      })
      return JSON.parse(reversed.json).metrics.exact.httpRequestDuration.p95 === 21
    },
  })
}
