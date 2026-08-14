const ALLOWED_SCENARIOS = new Set([
  'authRefresh',
  'storeSearch',
  'reservationCreate',
  'notificationHistory',
])

const ALLOWED_METRICS = Object.freeze({
  http_req_duration: ['httpReqDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  http_reqs: ['httpRequests', ['count', 'rate']],
  checks: ['checks', ['rate', 'passes', 'fails']],
  expected_4xx: ['expected4xx', ['count', 'rate']],
  unexpected_4xx: ['unexpected4xx', ['count', 'rate']],
  server_5xx: ['server5xx', ['count', 'rate']],
  unexpected_status: ['unexpectedStatus', ['count', 'rate']],
  dropped_iterations: ['droppedIterations', ['count', 'rate']],
})

const VALUE_NAMES = Object.freeze({
  'p(50)': 'p50',
  'p(95)': 'p95',
  'p(99)': 'p99',
})

function parseMetricName(metricName) {
  const opening = metricName.indexOf('{')
  if (opening < 0 || !metricName.endsWith('}')) return null
  const baseName = metricName.slice(0, opening)
  const tags = {}
  for (const part of metricName.slice(opening + 1, -1).split(',')) {
    const separator = part.indexOf(':')
    if (separator < 1) return null
    tags[part.slice(0, separator)] = part.slice(separator + 1)
  }
  return { baseName, tags }
}

function copyMetricValues(values, allowedValueNames) {
  const copied = {}
  for (const sourceName of allowedValueNames) {
    const value = values[sourceName]
    if (typeof value === 'number' && Number.isFinite(value)) {
      copied[VALUE_NAMES[sourceName] || sourceName] = value
    }
  }
  return copied
}

function safeMetadata(metadata) {
  const limits = metadata.limits || {}
  return {
    targetEnv: metadata.targetEnv,
    profile: metadata.profile,
    runId: metadata.runId,
    prerequisiteSmokeRunId: metadata.prerequisiteSmokeRunId || null,
    commitSha: metadata.commitSha,
    limits: {
      maxVus: limits.maxVus,
      durationSeconds: limits.durationSeconds,
      arrivalRate: limits.arrivalRate,
    },
  }
}

function collectMetrics(data) {
  const collected = {}
  const metrics = data && data.metrics ? data.metrics : {}
  for (const [metricName, metric] of Object.entries(metrics)) {
    const parsed = parseMetricName(metricName)
    if (parsed === null || parsed.tags.phase !== 'measured') continue
    const scenario = parsed.tags.scenario
    if (!ALLOWED_SCENARIOS.has(scenario)) continue
    const metricContract = ALLOWED_METRICS[parsed.baseName]
    if (metricContract === undefined || metric === null || typeof metric !== 'object') continue

    const [outputName, allowedValueNames] = metricContract
    const values = metric.values && typeof metric.values === 'object' ? metric.values : {}
    collected[scenario] = collected[scenario] || {}
    collected[scenario][outputName] = copyMetricValues(values, allowedValueNames)
  }
  return collected
}

function renderMarkdown(summary) {
  const lines = [
    '# k6 safe summary',
    '',
    `- targetEnv: ${summary.targetEnv}`,
    `- profile: ${summary.profile}`,
    `- runId: ${summary.runId}`,
    `- commitSha: ${summary.commitSha}`,
    '',
    '| scenario | p50 ms | p95 ms | p99 ms | requests | dropped iterations | expected 4xx | unexpected 4xx | 5xx |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|',
  ]
  for (const scenario of ALLOWED_SCENARIOS) {
    const metrics = summary.metrics[scenario]
    if (metrics === undefined) continue
    const duration = metrics.httpReqDuration || {}
    lines.push(`| ${scenario} | ${duration.p50 ?? '-'} | ${duration.p95 ?? '-'} | ${duration.p99 ?? '-'} | ${metrics.httpRequests?.count ?? '-'} | ${metrics.droppedIterations?.count ?? 0} | ${metrics.expected4xx?.count ?? 0} | ${metrics.unexpected4xx?.count ?? 0} | ${metrics.server5xx?.count ?? 0} |`)
  }
  return `${lines.join('\n')}\n`
}

export function renderSafeSummary(data, metadata) {
  const summary = {
    ...safeMetadata(metadata),
    metrics: collectMetrics(data),
  }
  const json = `${JSON.stringify(summary, null, 2)}\n`
  const markdown = renderMarkdown(summary)
  return {
    stdout: markdown,
    json,
    markdown,
  }
}
