const VALUE_NAMES = { 'p(50)': 'p50', 'p(95)': 'p95', 'p(99)': 'p99' }
const METRICS = { http_req_duration: ['httpRequestDuration', ['p(50)', 'p(95)', 'p(99)']], http_reqs: ['httpRequests', ['count', 'rate']], unexpected_4xx: ['unexpected4xx', ['count']], server_5xx: ['server5xx', ['count']], unexpected_status: ['unexpectedStatus', ['count']] }

function validText(name, value, pattern) { if (typeof value !== 'string' || !pattern.test(value)) throw new Error(`${name} is invalid`); return value }
function copy(values, keys) { const result = {}; for (const key of keys) if (typeof values?.[key] === 'number' && Number.isFinite(values[key])) result[VALUE_NAMES[key] || key] = values[key]; return result }
function parse(name) {
  const opening = name.indexOf('{')
  if (opening < 1 || !name.endsWith('}')) return null
  const tags = {}
  for (const part of name.slice(opening + 1, -1).split(',')) {
    const separator = part.indexOf(':')
    if (separator < 1) return null
    tags[part.slice(0, separator)] = part.slice(separator + 1)
  }
  return tags.phase === 'measured' && /^[a-z-]+$/.test(tags.scenario || '')
    ? { base: name.slice(0, opening), scenario: tags.scenario }
    : null
}
function markdown(summary) { return `# k6 LLM search safe summary\n\n- runId: ${summary.runId}\n- commitSha: ${summary.commitSha}\n- harnessCommitSha: ${summary.harnessCommitSha}\n- LLM metric evidence: ${summary.llmMetricEvidence}\n- calls delta: ${summary.llmDeltas?.calls ?? 'external evidence required'}\n- estimated cost delta USD: ${summary.llmDeltas?.estimatedCostUsd ?? 'external evidence required'}\n` }

export function renderSafeSearchLlmSummary(data, metadata) {
  const scenarios = Array.isArray(metadata.scenarios) ? [...metadata.scenarios] : []
  if (scenarios.length === 0 || scenarios.some((name) => !/^[a-z-]+$/.test(name))) throw new Error('scenarios are invalid')
  const summary = {
    schemaVersion: 'miriyum-k6-search-llm-summary-v1',
    targetEnv: validText('targetEnv', metadata.targetEnv, /^staging$/),
    runId: validText('runId', metadata.runId, /^[A-Za-z0-9._-]{1,100}$/),
    commitSha: validText('commitSha', metadata.commitSha, /^[0-9a-f]{40}$/),
    harnessCommitSha: validText('harnessCommitSha', metadata.harnessCommitSha, /^[0-9a-f]{40}$/),
    fixtureSha256: validText('fixtureSha256', metadata.fixtureSha256, /^[0-9a-f]{64}$/),
    scenarios, budget: { totalCalls: metadata.budget.totalCalls, totalCostUsd: metadata.budget.totalCostUsd },
    llmMetricEvidence: metadata.llmDeltas == null ? 'EXTERNAL_REQUIRED' : 'OBSERVED',
    llmDeltas: metadata.llmDeltas == null ? null : {
      calls: metadata.llmDeltas.calls,
      inputTokens: metadata.llmDeltas.inputTokens,
      outputTokens: metadata.llmDeltas.outputTokens,
      estimatedCostUsd: metadata.llmDeltas.estimatedCostUsd,
    },
    metrics: {},
  }
  for (const [name, metric] of Object.entries(data?.metrics || {})) {
    const parsed = parse(name); const contract = parsed && METRICS[parsed.base]
    if (!contract || !scenarios.includes(parsed.scenario)) continue
    summary.metrics[parsed.scenario] = summary.metrics[parsed.scenario] || {}
    summary.metrics[parsed.scenario][contract[0]] = copy(metric.values, contract[1])
  }
  const json = `${JSON.stringify(summary, null, 2)}\n`; const rendered = markdown(summary)
  return { stdout: rendered, json, markdown: rendered }
}
