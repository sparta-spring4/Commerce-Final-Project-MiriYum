const ENDPOINT_KINDS = new Set([
  'notification-consumer',
  'waiting-consumer',
  'waiting-store-operator',
])

const ALLOWED_METRICS = Object.freeze({
  sse_first_event: ['firstEvent', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  sse_connection_duration: ['connectionDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  sse_slow_cleanup_duration: ['slowCleanupDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  sse_companion_lifetime: ['companionLifetime', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  sse_recovery_duration: ['recoveryDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  sse_heartbeat_frames: ['heartbeatFrames', ['count', 'rate']],
  http_req_duration: ['httpRequestDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  http_reqs: ['httpRequests', ['count', 'rate']],
  http_req_failed: ['httpRequestFailures', ['count', 'rate']],
  owned_http_baseline: ['ownedHttpBaseline', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  owned_http_duration: ['ownedHttpDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  owned_http_degradation_ratio: ['ownedHttpDegradationRatio', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  owned_http_success: ['ownedHttpSuccess', ['count', 'rate']],
  owned_http_errors: ['ownedHttpErrors', ['count', 'rate']],
  slow_client_triggers: ['slowClientTriggers', ['count', 'rate']],
  sse_connections_opened: ['openedConnections', ['count', 'rate']],
  sse_connections_successful: ['successfulConnections', ['count', 'rate']],
  sse_connections_rejected: ['rejectedConnections', ['count', 'rate']],
  sse_contract_errors: ['contractErrors', ['count', 'rate']],
  sse_transport_errors: ['transportErrors', ['count', 'rate']],
  sse_valid_events: ['validEvents', ['count', 'rate']],
  sse_recovery_attempts: ['recoveryAttempts', ['count', 'rate']],
  sse_recovery_successful: ['recoverySuccessful', ['count', 'rate']],
  sse_expected_4xx: ['expected4xx', ['count', 'rate']],
  sse_unexpected_4xx: ['unexpected4xx', ['count', 'rate']],
  sse_server_5xx: ['server5xx', ['count', 'rate']],
  sse_unexpected_status: ['unexpectedStatus', ['count', 'rate']],
  checks: ['checks', ['rate', 'passes', 'fails']],
})

const RUN_METRICS = Object.freeze({
  dropped_iterations: ['droppedIterations', ['count', 'rate']],
  sse_recovery_http_verified: ['recoveryHttpVerified', ['count', 'rate']],
  sse_recovery_cleanup_successful: ['recoveryCleanupSuccessful', ['count', 'rate']],
  sse_recovery_trigger_list_failures: ['recoveryTriggerListFailures', ['count', 'rate']],
  sse_recovery_trigger_fixture_failures: ['recoveryTriggerFixtureFailures', ['count', 'rate']],
  sse_recovery_trigger_call_failures: ['recoveryTriggerCallFailures', ['count', 'rate']],
  sse_recovery_trigger_response_failures: ['recoveryTriggerResponseFailures', ['count', 'rate']],
})

const RUN_AGGREGATE_METRICS = Object.freeze({
  owned_http_baseline: ['ownedHttpBaseline', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
  owned_http_duration: ['ownedHttpDuration', ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)']],
})

const VALUE_NAMES = Object.freeze({
  'p(50)': 'p50',
  'p(95)': 'p95',
  'p(99)': 'p99',
})

function requireText(name, value, pattern) {
  if (typeof value !== 'string' || !pattern.test(value)) throw new Error(`${name} is invalid`)
  return value
}

function requirePositiveInt(name, value, maximum) {
  if (!Number.isInteger(value) || value <= 0 || value > maximum) {
    throw new Error(`${name} is invalid`)
  }
  return value
}

function parseMetricName(metricName) {
  const opening = metricName.indexOf('{')
  if (opening < 1 || !metricName.endsWith('}')) return null
  const tags = {}
  for (const part of metricName.slice(opening + 1, -1).split(',')) {
    const separator = part.indexOf(':')
    if (separator < 1) return null
    tags[part.slice(0, separator)] = part.slice(separator + 1)
  }
  return { baseName: metricName.slice(0, opening), tags }
}

function copyMetricValues(values, allowedNames) {
  const result = {}
  const source = values !== null && typeof values === 'object' ? values : {}
  for (const name of allowedNames) {
    const value = source[name]
    if (typeof value === 'number' && Number.isFinite(value)) {
      result[VALUE_NAMES[name] || name] = value
    }
  }
  return result
}

function safeMetadata(metadata) {
  const targetEnv = requireText('targetEnv', metadata.targetEnv, /^(local|staging)$/)
  const profile = requireText('profile', metadata.profile, /^(smoke|reconnect|steady|slow-client|capacity|recovery)$/)
  const runId = requireText('runId', metadata.runId, /^[A-Za-z0-9._-]{1,100}$/)
  const shaPattern = /^[0-9a-f]{40}$/
  const digestPattern = /^[0-9a-f]{64}$/
  const endpointKinds = Array.isArray(metadata.endpointKinds) ? [...metadata.endpointKinds] : []
  if (endpointKinds.length === 0
    || endpointKinds.some((kind) => !ENDPOINT_KINDS.has(kind))
    || new Set(endpointKinds).size !== endpointKinds.length) {
    throw new Error('endpointKinds is invalid')
  }
  const limits = metadata.limits || {}
  const holdDurationSeconds = requirePositiveInt(
    'holdDurationSeconds', limits.holdDurationSeconds, 600,
  )
  const slowClientDelaySeconds = requirePositiveInt(
    'slowClientDelaySeconds', limits.slowClientDelaySeconds, 60,
  )
  let slowClientMaxCleanupSeconds = null
  let companionMinLifetimeSeconds = null
  let reconnectSettleSeconds = null
  let recoveryArmDelaySeconds = null
  let recoveryMaxSeconds = null
  if (profile === 'reconnect') {
    reconnectSettleSeconds = requirePositiveInt(
      'reconnectSettleSeconds', limits.reconnectSettleSeconds, 60,
    )
  }
  if (profile === 'slow-client') {
    slowClientMaxCleanupSeconds = requirePositiveInt(
      'slowClientMaxCleanupSeconds', limits.slowClientMaxCleanupSeconds, 600,
    )
    companionMinLifetimeSeconds = requirePositiveInt(
      'companionMinLifetimeSeconds', limits.companionMinLifetimeSeconds, 600,
    )
    if (slowClientDelaySeconds <= 30
      || slowClientDelaySeconds >= slowClientMaxCleanupSeconds
      || slowClientMaxCleanupSeconds >= companionMinLifetimeSeconds
      || companionMinLifetimeSeconds >= holdDurationSeconds) {
      throw new Error('slow-client timing windows are invalid')
    }
  }
  if (profile === 'recovery') {
    if (targetEnv === 'staging') {
      if (limits.recoveryArmDelaySeconds !== null) {
        throw new Error('recoveryArmDelaySeconds is invalid')
      }
    } else {
      recoveryArmDelaySeconds = requirePositiveInt(
        'recoveryArmDelaySeconds', limits.recoveryArmDelaySeconds, 60,
      )
    }
    recoveryMaxSeconds = requirePositiveInt(
      'recoveryMaxSeconds', limits.recoveryMaxSeconds, 60,
    )
  }
  return {
    schemaVersion: 'miriyum-k6-sse-summary-v1',
    targetEnv,
    profile,
    runId,
    prerequisiteSmokeRunId: metadata.prerequisiteSmokeRunId === null
      || metadata.prerequisiteSmokeRunId === undefined
      ? null
      : requireText(
        'prerequisiteSmokeRunId',
        metadata.prerequisiteSmokeRunId,
        /^[A-Za-z0-9._-]{1,100}$/,
      ),
    commitSha: requireText('commitSha', metadata.commitSha, shaPattern),
    harnessCommitSha: requireText('harnessCommitSha', metadata.harnessCommitSha, shaPattern),
    targetFingerprint: requireText(
      'targetFingerprint', metadata.targetFingerprint, digestPattern,
    ),
    fixtureSha256: requireText('fixtureSha256', metadata.fixtureSha256, digestPattern),
    endpointKinds,
    limits: {
      connections: requirePositiveInt('connections', limits.connections, 200),
      connectionsPerAccount: requirePositiveInt(
        'connectionsPerAccount', limits.connectionsPerAccount, 7,
      ),
      holdDurationSeconds,
      reconnectSettleSeconds,
      slowClientDelaySeconds,
      slowClientMaxCleanupSeconds,
      companionMinLifetimeSeconds,
      recoveryArmDelaySeconds,
      recoveryMaxSeconds,
    },
  }
}

function allThresholdsPassed(data) {
  const results = []
  const metrics = data && data.metrics ? data.metrics : {}
  for (const metric of Object.values(metrics)) {
    if (metric === null || typeof metric !== 'object') continue
    const thresholds = metric.thresholds
    if (thresholds === null || typeof thresholds !== 'object') continue
    for (const result of Object.values(thresholds)) results.push(result)
  }
  return results.length > 0 && results.every((result) => result && result.ok === true)
}

function collectMetrics(data, metadata) {
  const endpointMetrics = {}
  const runMetrics = {}
  const metrics = data && data.metrics ? data.metrics : {}
  for (const [name, metric] of Object.entries(metrics)) {
    const runAggregateContract = RUN_AGGREGATE_METRICS[name]
    if (runAggregateContract !== undefined && metric !== null && typeof metric === 'object') {
      runMetrics[runAggregateContract[0]] = copyMetricValues(
        metric.values,
        runAggregateContract[1],
      )
      continue
    }
    const parsed = parseMetricName(name)
    if (parsed === null
      || !['measured', 'cleanup'].includes(parsed.tags.phase)
      || parsed.tags.profile !== metadata.profile
      || metric === null
      || typeof metric !== 'object') continue

    const runContract = RUN_METRICS[parsed.baseName]
    if (runContract !== undefined && parsed.tags.endpoint_kind === undefined) {
      runMetrics[runContract[0]] = copyMetricValues(metric.values, runContract[1])
      continue
    }

    const endpointKind = parsed.tags.endpoint_kind
    const metricContract = ALLOWED_METRICS[parsed.baseName]
    if (metricContract === undefined
      || !metadata.endpointKinds.includes(endpointKind)) continue
    endpointMetrics[endpointKind] = endpointMetrics[endpointKind] || {}
    endpointMetrics[endpointKind][metricContract[0]] = copyMetricValues(
      metric.values,
      metricContract[1],
    )
  }
  return { endpointMetrics, runMetrics }
}

function renderMarkdown(summary) {
  const lines = [
    '# k6 SSE safe summary',
    '',
    `- targetEnv: ${summary.targetEnv}`,
    `- profile: ${summary.profile}`,
    `- runId: ${summary.runId}`,
    `- commitSha: ${summary.commitSha}`,
    `- harnessCommitSha: ${summary.harnessCommitSha}`,
    `- thresholdsPassed: ${summary.thresholdsPassed}`,
    '',
    '| endpoint kind | first event p95 ms | connection p95 ms | slow cleanup max ms | companion min ms | successful | rejected | recovery successful | unexpected 4xx | 5xx |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|',
  ]
  for (const endpointKind of summary.endpointKinds) {
    const metrics = summary.metrics[endpointKind] || {}
    lines.push(`| ${endpointKind} | ${metrics.firstEvent?.p95 ?? '-'} | ${metrics.connectionDuration?.p95 ?? '-'} | ${metrics.slowCleanupDuration?.max ?? '-'} | ${metrics.companionLifetime?.min ?? '-'} | ${metrics.successfulConnections?.count ?? 0} | ${metrics.rejectedConnections?.count ?? 0} | ${metrics.recoverySuccessful?.count ?? 0} | ${metrics.unexpected4xx?.count ?? 0} | ${metrics.server5xx?.count ?? 0} |`)
  }
  const baseline = summary.runMetrics.ownedHttpBaseline
  const measured = summary.runMetrics.ownedHttpDuration
  if (baseline !== undefined || measured !== undefined) {
    lines.push(
      '',
      `- owned HTTP baseline p95/max ms: ${baseline?.p95 ?? '-'} / ${baseline?.max ?? '-'}`,
      `- owned HTTP measured p95/max ms: ${measured?.p95 ?? '-'} / ${measured?.max ?? '-'}`,
    )
  }
  if (summary.profile === 'recovery') {
    const recovery = summary.metrics['waiting-store-operator']?.recoveryDuration
    lines.push(
      '',
      `- recovery max ms: ${recovery?.max ?? '-'}`,
      `- HTTP verified: ${summary.runMetrics.recoveryHttpVerified?.count ?? 0}`,
      `- cleanup successful: ${summary.runMetrics.recoveryCleanupSuccessful?.count ?? 0}`,
      `- trigger list failures: ${summary.runMetrics.recoveryTriggerListFailures?.count ?? 0}`,
      `- trigger fixture failures: ${summary.runMetrics.recoveryTriggerFixtureFailures?.count ?? 0}`,
      `- trigger call failures: ${summary.runMetrics.recoveryTriggerCallFailures?.count ?? 0}`,
      `- trigger response failures: ${summary.runMetrics.recoveryTriggerResponseFailures?.count ?? 0}`,
    )
  }
  return `${lines.join('\n')}\n`
}

export function renderSafeSseSummary(data, metadata) {
  const safe = safeMetadata(metadata)
  const collected = collectMetrics(data, safe)
  const summary = {
    ...safe,
    thresholdsPassed: allThresholdsPassed(data),
    metrics: collected.endpointMetrics,
    runMetrics: collected.runMetrics,
  }
  const json = `${JSON.stringify(summary, null, 2)}\n`
  const markdown = renderMarkdown(summary)
  return { stdout: markdown, json, markdown }
}

export function validateSseSmokeProof(proof, expected) {
  if (proof === null || typeof proof !== 'object' || Array.isArray(proof)
    || proof.schemaVersion !== 'miriyum-k6-sse-summary-v1'
    || proof.profile !== 'smoke') {
    throw new Error('SSE smoke proof contract is invalid')
  }
  if (proof.thresholdsPassed !== true) throw new Error('SSE smoke proof thresholds did not pass')
  if (proof.targetEnv !== expected.targetEnv
    || proof.targetFingerprint !== expected.targetFingerprint) {
    throw new Error('SSE smoke proof target does not match')
  }
  if (proof.commitSha !== expected.commitSha
    || proof.harnessCommitSha !== expected.harnessCommitSha) {
    throw new Error('SSE smoke proof SHA does not match')
  }
  if (proof.fixtureSha256 !== expected.fixtureSha256) {
    throw new Error('SSE smoke proof fixture does not match')
  }
  const covered = Array.isArray(proof.endpointKinds) ? new Set(proof.endpointKinds) : new Set()
  if (!expected.endpointKinds.every((kind) => covered.has(kind))) {
    throw new Error('SSE smoke proof endpoint coverage does not match')
  }
  const runId = requireText('SSE smoke proof runId', proof.runId, /^[A-Za-z0-9._-]{1,100}$/)
  return Object.freeze({ runId })
}
