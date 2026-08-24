const SAFE_TAGS = Object.freeze([
  'phase',
  'profile',
  'audience',
  'endpoint_kind',
  'traffic',
])

function requirePositiveInteger(name, value, maximum) {
  if (!Number.isInteger(value) || value <= 0 || value > maximum) {
    throw new Error(`${name} is invalid`)
  }
  return value
}

function requireProfile(value) {
  if (!['smoke', 'reconnect', 'steady', 'slow-client', 'capacity', 'recovery'].includes(value)) {
    throw new Error('SSE profile is invalid')
  }
  return value
}

function requireSlowClientConnections(connections, value) {
  const slowConnections = requirePositiveInteger(
    'slow-client connections', value, connections - 1,
  )
  if (slowConnections !== connections - 1) {
    throw new Error('slow-client requires exactly one companion connection')
  }
  return slowConnections
}

function streamScenario(exec, vus, holdDurationSeconds) {
  return {
    executor: 'shared-iterations',
    exec,
    vus,
    iterations: vus,
    maxDuration: `${holdDurationSeconds + 90}s`,
    gracefulStop: '5s',
    tags: { phase: 'measured' },
  }
}

export function buildSseScenarioOptions(config) {
  const profile = requireProfile(config?.profile)
  const connections = requirePositiveInteger('SSE connections', config?.connections, 200)
  const holdDurationSeconds = requirePositiveInteger(
    'SSE hold duration', config?.holdDurationSeconds, 600,
  )
  if (profile === 'smoke') {
    return Object.freeze({ sse: streamScenario('sseSmoke', connections, holdDurationSeconds) })
  }
  if (profile === 'recovery') {
    if (connections !== 1) throw new Error('recovery requires one SSE connection')
    return Object.freeze({ sse: streamScenario('sseRecovery', 1, holdDurationSeconds) })
  }

  const probeRate = requirePositiveInteger('owned HTTP probe rate', config?.httpProbeRate, 100)
  const scenarios = {
    owned_http_probe: {
      executor: 'constant-arrival-rate',
      exec: 'ownedHttpProbe',
      rate: probeRate,
      timeUnit: '1s',
      duration: `${holdDurationSeconds}s`,
      preAllocatedVUs: Math.min(probeRate, 10),
      maxVUs: Math.min(probeRate * 2, 200),
      tags: { phase: 'measured' },
    },
  }
  if (profile !== 'slow-client') {
    scenarios.sse = streamScenario(
      profile === 'reconnect'
        ? 'sseReconnect'
        : profile === 'capacity' ? 'sseCapacity' : 'sseSteady',
      connections,
      holdDurationSeconds,
    )
    return Object.freeze(scenarios)
  }

  const slowConnections = requireSlowClientConnections(
    connections, config?.slowClientConnections,
  )
  scenarios.sse_slow = streamScenario(
    'sseSlowClient', slowConnections, holdDurationSeconds,
  )
  scenarios.sse_companion = streamScenario(
    'sseCompanion', connections - slowConnections, holdDurationSeconds,
  )
  return Object.freeze(scenarios)
}

export function buildSseThresholds(config) {
  const profile = requireProfile(config?.profile)
  if (profile === 'smoke') return Object.freeze({})
  if (profile === 'recovery') {
    const maxSeconds = requirePositiveInteger(
      'recovery maximum seconds', config?.recoveryMaxSeconds, 60,
    )
    return Object.freeze({
      'sse_recovery_duration{phase:measured,profile:recovery,endpoint_kind:waiting-store-operator}': [`max<=${maxSeconds * 1000}`],
      'sse_recovery_http_verified{phase:measured,profile:recovery,traffic:owned-http}': ['count==1'],
      'sse_recovery_cleanup_successful{phase:cleanup,profile:recovery,traffic:cleanup}': ['count==1'],
    })
  }
  const maxP95Ratio = requirePositiveInteger(
    'owned HTTP p95 ratio', config?.httpMaxP95Ratio, 10,
  )
  const endpointKinds = Array.isArray(config?.endpointKinds) ? config.endpointKinds : []
  if (endpointKinds.length === 0) throw new Error('owned HTTP endpoint kinds are required')
  const commonTags = `phase:measured,profile:${profile},traffic:owned-http`
  const result = {
    [`http_reqs{${commonTags}}`]: ['count>0'],
    [`http_req_failed{${commonTags}}`]: ['rate==0'],
    [`owned_http_success{${commonTags}}`]: ['count>0'],
    [`owned_http_errors{${commonTags}}`]: ['count==0'],
    'dropped_iterations{scenario:owned_http_probe}': ['count==0'],
  }
  for (const endpointKind of endpointKinds) {
    result[`owned_http_degradation_ratio{phase:measured,profile:${profile},endpoint_kind:${endpointKind},traffic:owned-http}`]
      = [`p(95)<=${maxP95Ratio}`]
  }
  if (profile === 'slow-client') {
    const endpointKind = endpointKinds[0]
    const maxCleanupSeconds = requirePositiveInteger(
      'slow-client max cleanup', config?.slowClientMaxCleanupSeconds, 600,
    )
    const companionMinSeconds = requirePositiveInteger(
      'companion minimum lifetime', config?.companionMinLifetimeSeconds, 600,
    )
    result['slow_client_triggers{phase:measured,profile:slow-client,traffic:trigger}'] = ['count==1']
    result[`sse_slow_cleanup_duration{phase:measured,profile:slow-client,endpoint_kind:${endpointKind}}`]
      = [`max<=${maxCleanupSeconds * 1000}`]
    result[`sse_companion_lifetime{phase:measured,profile:slow-client,endpoint_kind:${endpointKind}}`]
      = [`min>=${companionMinSeconds * 1000}`]
  }
  return Object.freeze(result)
}

export function requireSlowCleanupResult(result) {
  if (result === null
    || typeof result !== 'object'
    || result.completed !== true
    || result.classification !== 'success'
    || result.receivePaused !== true
    || result.serverClosed !== true
    || !Number.isInteger(result.validEvents)
    || result.validEvents < 1) {
    throw new Error('slow-client cleanup evidence is invalid')
  }
  return result
}

export function assignSlowClientRoles(sessions, slowConnections) {
  if (!Array.isArray(sessions) || sessions.length < 2) {
    throw new Error('slow-client sessions are missing')
  }
  requireSlowClientConnections(sessions.length, slowConnections)
  return Object.freeze(sessions.map((session, index) => Object.freeze({
    ...session,
    role: index < slowConnections ? 'slow' : 'companion',
  })))
}

function requireText(name, value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} is required`)
  }
  return value.trim()
}

function requireSession(session) {
  if (session === null || typeof session !== 'object' || Array.isArray(session)
    || session.target === null || typeof session.target !== 'object') {
    throw new Error('owned HTTP session is required')
  }
  requireText('owned HTTP access token', session.accessToken)
  return session
}

function safeTags(tags) {
  const selected = {}
  for (const field of SAFE_TAGS) selected[field] = requireText(`${field} tag`, tags?.[field])
  return selected
}

function headers(accessToken, json = false) {
  return {
    Accept: 'application/json',
    Authorization: `Bearer ${accessToken}`,
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  }
}

function diagnosticError(errorMessage, response, diagnosticTags) {
  if (diagnosticTags === null) return new Error(errorMessage)
  const endpointKind = requireText('endpoint_kind tag', diagnosticTags.endpoint_kind)
  const status = Number.isInteger(response?.status) ? response.status : 'transport'
  return new Error(`${errorMessage} (endpoint_kind=${endpointKind} status=${status})`)
}

function parseSuccess(response, errorMessage, diagnosticTags = null) {
  if (response?.status !== 200) throw diagnosticError(errorMessage, response, diagnosticTags)
  let parsed
  try {
    parsed = JSON.parse(response.body)
  } catch (_) {
    throw diagnosticError(errorMessage, response, diagnosticTags)
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)
    || parsed.code !== 'SUCCESS'
    || typeof parsed.message !== 'string'
    || parsed.data === undefined) {
    throw diagnosticError(errorMessage, response, diagnosticTags)
  }
  return parsed.data
}

function requireRecoveryMutation(mutation) {
  if (mutation === null || typeof mutation !== 'object'
    || typeof mutation.waitingTeamId !== 'string'
    || !/^[1-9][0-9]*$/.test(mutation.waitingTeamId)
    || !Number.isInteger(mutation.version)
    || mutation.version < 1) {
    throw new Error('recovery mutation evidence is invalid')
  }
  return mutation
}

function durationMilliseconds(response, errorMessage, diagnosticTags = null) {
  const duration = response?.timings?.duration
  if (typeof duration !== 'number' || !Number.isFinite(duration) || duration < 0) {
    throw diagnosticError(errorMessage, response, diagnosticTags)
  }
  return duration
}

function emit(metrics, name, value, tags) {
  const recorder = metrics?.[name]
  if (typeof recorder === 'function') recorder(value, tags)
}

function get(client, url, accessToken, tags) {
  if (client === null || typeof client?.get !== 'function') {
    throw new Error('owned HTTP client is required')
  }
  return client.get(url, {
    headers: headers(accessToken),
    tags,
    redirects: 0,
  })
}

function waitingTriggerFixture({ client, baseUrl, session, metrics, tags }) {
  const selected = requireSession(session)
  if (selected.target.kind !== 'waiting-store-operator'
    || selected.target.audience !== 'store-operator') {
    throw new Error('slow-client trigger target is invalid')
  }
  if (client === null || typeof client?.get !== 'function') {
    throw new Error('slow-client trigger client is required')
  }
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  const selectedTags = safeTags(tags)
  const listUrl = `${normalizedBaseUrl}/api/v1/store-operators/stores/${selected.target.storeId}/waiting-teams?status=WAITING&size=1`
  let list
  try {
    list = parseSuccess(
      get(client, listUrl, selected.accessToken, selectedTags),
      'recovery trigger list request failed',
    )
  } catch (_) {
    emit(metrics, 'listFailure', 1, selectedTags)
    throw new Error(selectedTags.profile === 'recovery'
      ? 'recovery trigger list request failed'
      : 'slow-client trigger fixture is unavailable')
  }
  const item = Array.isArray(list?.items) ? list.items[0] : null
  if (item === null || item === undefined
    || typeof item.waitingTeamId !== 'string'
    || !/^[1-9][0-9]*$/.test(item.waitingTeamId)
    || item.status !== 'WAITING'
    || !Number.isInteger(item.version)
    || item.version < 0) {
    emit(metrics, 'fixtureFailure', 1, selectedTags)
    throw new Error(selectedTags.profile === 'recovery'
      ? 'recovery trigger fixture is unavailable'
      : 'slow-client trigger fixture is unavailable')
  }
  return Object.freeze({ item, selected, normalizedBaseUrl, selectedTags })
}

export function verifyWaitingTriggerFixture({
  client,
  baseUrl,
  session,
  metrics = {},
  tags,
}) {
  waitingTriggerFixture({ client, baseUrl, session, metrics, tags })
  return true
}

export function ownedHttpPath(target) {
  if (target?.kind === 'notification-consumer' && target.audience === 'consumer') {
    return '/api/v1/consumers/me/notifications?size=20'
  }
  if (target?.kind === 'waiting-consumer' && target.audience === 'consumer') {
    return '/api/v1/consumers/me/waiting-teams/current'
  }
  if (target?.kind === 'waiting-store-operator'
    && target.audience === 'store-operator'
    && typeof target.storeId === 'string'
    && /^[1-9][0-9]*$/.test(target.storeId)) {
    return `/api/v1/store-operators/stores/${target.storeId}/waiting-teams?size=20`
  }
  throw new Error('owned HTTP target is invalid')
}

export function captureOwnedHttpBaseline({
  client,
  baseUrl,
  session,
  samples,
  metrics = {},
  tags,
}) {
  const selected = requireSession(session)
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  if (samples !== 3) throw new Error('owned HTTP baseline requires exactly three samples')
  const selectedTags = safeTags(tags)
  const url = `${normalizedBaseUrl}${ownedHttpPath(selected.target)}`
  const durations = []
  for (let index = 0; index < samples; index += 1) {
    const response = get(client, url, selected.accessToken, selectedTags)
    parseSuccess(response, 'owned HTTP baseline request failed', selectedTags)
    const duration = durationMilliseconds(response, 'owned HTTP baseline request failed', selectedTags)
    durations.push(duration)
    emit(metrics, 'baseline', duration, selectedTags)
  }
  durations.sort((left, right) => left - right)
  return Math.max(1, durations[durations.length - 1])
}

export function runOwnedHttpProbe({
  client,
  baseUrl,
  session,
  maxP95Ratio,
  metrics = {},
  tags,
  onThresholdExceeded,
}) {
  const selected = requireSession(session)
  if (typeof selected.baselineMilliseconds !== 'number'
    || !Number.isFinite(selected.baselineMilliseconds)
    || selected.baselineMilliseconds <= 0) {
    throw new Error('owned HTTP baseline is missing')
  }
  if (typeof maxP95Ratio !== 'number' || !Number.isFinite(maxP95Ratio)
    || maxP95Ratio < 1 || maxP95Ratio > 10) {
    throw new Error('owned HTTP p95 ratio is invalid')
  }
  if (onThresholdExceeded !== undefined && typeof onThresholdExceeded !== 'function') {
    throw new Error('owned HTTP threshold diagnostic callback is invalid')
  }
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  const selectedTags = safeTags(tags)
  const response = get(
    client,
    `${normalizedBaseUrl}${ownedHttpPath(selected.target)}`,
    selected.accessToken,
    selectedTags,
  )
  try {
    parseSuccess(response, 'owned HTTP measured request failed')
    const duration = durationMilliseconds(response, 'owned HTTP measured request failed')
    const degradationRatio = duration / selected.baselineMilliseconds
    emit(metrics, 'duration', duration, selectedTags)
    emit(metrics, 'degradationRatio', degradationRatio, selectedTags)
    const success = degradationRatio <= maxP95Ratio
    emit(metrics, success ? 'success' : 'error', 1, selectedTags)
    if (!success && onThresholdExceeded !== undefined) {
      onThresholdExceeded(Object.freeze({
        endpointKind: selectedTags.endpoint_kind,
        baselineMilliseconds: selected.baselineMilliseconds,
        measuredMilliseconds: duration,
        degradationRatio,
        occurredAt: new Date().toISOString(),
      }))
    }
    return Object.freeze({
      success,
      degradationRatio,
      baselineMilliseconds: selected.baselineMilliseconds,
      measuredMilliseconds: duration,
    })
  } catch (_) {
    emit(metrics, 'error', 1, selectedTags)
    throw new Error('owned HTTP measured request failed')
  }
}

export function triggerWaitingChange({
  client,
  baseUrl,
  session,
  idempotencyKey,
  metrics = {},
  tags,
  onMutation,
}) {
  const key = requireText('slow-client idempotency key', idempotencyKey)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(key)) {
    throw new Error('slow-client idempotency key is invalid')
  }
  if (client === null || typeof client?.get !== 'function' || typeof client?.post !== 'function') {
    throw new Error('slow-client trigger client is required')
  }
  const { item, selected, normalizedBaseUrl, selectedTags } = waitingTriggerFixture({
    client, baseUrl, session, metrics, tags,
  })
  const response = client.post(
    `${normalizedBaseUrl}/api/v1/store-operators/stores/${selected.target.storeId}/waiting-teams/${item.waitingTeamId}/calls`,
    JSON.stringify({ expectedVersion: item.version }),
    {
      headers: { ...headers(selected.accessToken, true), 'Idempotency-Key': key },
      tags: selectedTags,
      redirects: 0,
    },
  )
  let changed
  try {
    changed = parseSuccess(response, 'recovery trigger call request failed')
  } catch (_) {
    emit(metrics, 'callFailure', 1, selectedTags)
    throw new Error(selectedTags.profile === 'recovery'
      ? 'recovery trigger call request failed'
      : 'slow-client trigger request failed')
  }
  if (changed?.waitingTeamId !== item.waitingTeamId
    || changed?.status !== 'CALLED'
    || !Number.isInteger(changed?.version)
    || changed.version <= item.version) {
    emit(metrics, 'responseFailure', 1, selectedTags)
    throw new Error(selectedTags.profile === 'recovery'
      ? 'recovery trigger call response is invalid'
      : 'slow-client trigger request failed')
  }
  if (onMutation !== undefined) {
    if (typeof onMutation !== 'function') throw new Error('recovery mutation callback is invalid')
    onMutation(Object.freeze({ waitingTeamId: item.waitingTeamId, version: changed.version }))
  }
  emit(metrics, 'trigger', 1, selectedTags)
  return true
}

export function verifyWaitingChange({ client, baseUrl, session, mutation, tags }) {
  const selected = requireSession(session)
  const evidence = requireRecoveryMutation(mutation)
  const selectedTags = safeTags(tags)
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  const detail = parseSuccess(get(
    client,
    `${normalizedBaseUrl}/api/v1/store-operators/stores/${selected.target.storeId}/waiting-teams/${evidence.waitingTeamId}`,
    selected.accessToken,
    selectedTags,
  ), 'recovery HTTP verification failed')
  if (detail?.waitingTeamId !== evidence.waitingTeamId
    || detail?.status !== 'CALLED'
    || detail?.version !== evidence.version) {
    throw new Error('recovery HTTP verification failed')
  }
  return true
}

export function cleanupWaitingChange({
  client, baseUrl, session, mutation, idempotencyKey, tags,
}) {
  const selected = requireSession(session)
  const evidence = requireRecoveryMutation(mutation)
  const key = requireText('recovery cleanup idempotency key', idempotencyKey)
  const selectedTags = safeTags(tags)
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  const response = client.post(
    `${normalizedBaseUrl}/api/v1/store-operators/stores/${selected.target.storeId}/waiting-teams/${evidence.waitingTeamId}/cancellations`,
    JSON.stringify({ expectedVersion: evidence.version }),
    {
      headers: { ...headers(selected.accessToken, true), 'Idempotency-Key': key },
      tags: selectedTags,
      redirects: 0,
    },
  )
  const cleaned = parseSuccess(response, 'recovery cleanup failed')
  if (cleaned?.waitingTeamId !== evidence.waitingTeamId
    || cleaned?.status !== 'CANCELLED'
    || !Number.isInteger(cleaned?.version)
    || cleaned.version <= evidence.version) {
    throw new Error('recovery cleanup failed')
  }
  return true
}
