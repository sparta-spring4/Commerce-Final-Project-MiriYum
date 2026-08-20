import { check } from 'k6'

import {
  assignSlowClientRoles,
  buildSseScenarioOptions,
  buildSseThresholds,
  captureOwnedHttpBaseline,
  ownedHttpPath,
  requireSlowCleanupResult,
  runOwnedHttpProbe,
  triggerWaitingChange,
} from '../sse/probe.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

const BASE_URL = 'https://loadtest-proxy:8443'
const TOKEN = 'access-token-memory-only'
const TAGS = {
  phase: 'measured',
  profile: 'steady',
  audience: 'consumer',
  endpoint_kind: 'notification-consumer',
  traffic: 'owned-http',
}

function envelope(data) {
  return JSON.stringify({ code: 'SUCCESS', message: 'ok', data })
}

function response(status, duration, data = {}) {
  return {
    status,
    body: envelope(data),
    timings: { duration },
    error: '',
  }
}

function recordingClient(responses) {
  const queue = [...responses]
  const calls = []
  return {
    calls,
    get(url, params) {
      calls.push({ method: 'GET', url, body: null, ...params })
      return queue.shift()
    },
    post(url, body, params) {
      calls.push({ method: 'POST', url, body, ...params })
      return queue.shift()
    },
  }
}

function recordingMetrics() {
  const entries = []
  return {
    entries,
    baseline: (value, tags) => entries.push({ name: 'baseline', value, tags }),
    duration: (value, tags) => entries.push({ name: 'duration', value, tags }),
    degradationRatio: (value, tags) => entries.push({ name: 'ratio', value, tags }),
    success: (value, tags) => entries.push({ name: 'success', value, tags }),
    error: (value, tags) => entries.push({ name: 'error', value, tags }),
    trigger: (value, tags) => entries.push({ name: 'trigger', value, tags }),
  }
}

function errorMessage(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

export default function () {
  const targets = {
    notification: { kind: 'notification-consumer', audience: 'consumer' },
    waitingConsumer: { kind: 'waiting-consumer', audience: 'consumer' },
    waitingOperator: {
      kind: 'waiting-store-operator',
      audience: 'store-operator',
      storeId: '301',
    },
  }

  const baselineClient = recordingClient([
    response(200, 10),
    response(200, 30),
    response(200, 20),
  ])
  const baselineMetrics = recordingMetrics()
  const baseline = captureOwnedHttpBaseline({
    client: baselineClient,
    baseUrl: BASE_URL,
    session: { target: targets.notification, accessToken: TOKEN },
    samples: 3,
    metrics: baselineMetrics,
    tags: { ...TAGS, phase: 'baseline' },
  })

  const probeClient = recordingClient([response(200, 45)])
  const probeMetrics = recordingMetrics()
  const probe = runOwnedHttpProbe({
    client: probeClient,
    baseUrl: BASE_URL,
    session: { target: targets.notification, accessToken: TOKEN, baselineMilliseconds: baseline },
    maxP95Ratio: 2,
    metrics: probeMetrics,
    tags: TAGS,
  })

  const triggerClient = recordingClient([
    response(200, 12, {
      items: [{
        waitingTeamId: '901',
        status: 'WAITING',
        queueSequence: 1,
        partySize: 2,
        createdAt: '2026-08-20T00:00:00Z',
        version: 4,
      }],
      nextCursor: null,
    }),
    response(200, 18, {
      waitingTeamId: '901',
      storeId: '301',
      status: 'CALLED',
      queueSequence: 1,
      partySize: 2,
      createdAt: '2026-08-20T00:00:00Z',
      calledAt: '2026-08-20T00:00:01Z',
      arrivedAt: null,
      checkedInAt: null,
      cancelledAt: null,
      version: 5,
    }),
  ])
  const triggerMetrics = recordingMetrics()
  const triggered = triggerWaitingChange({
    client: triggerClient,
    baseUrl: BASE_URL,
    session: { target: targets.waitingOperator, accessToken: TOKEN },
    idempotencyKey: '123e4567-e89b-12d3-a456-426614174000',
    metrics: triggerMetrics,
    tags: {
      phase: 'measured',
      profile: 'slow-client',
      audience: 'store-operator',
      endpoint_kind: 'waiting-store-operator',
      traffic: 'trigger',
    },
  })

  const badStatus = errorMessage(() => captureOwnedHttpBaseline({
    client: recordingClient([response(503, 1), response(200, 1), response(200, 1)]),
    baseUrl: BASE_URL,
    session: { target: targets.notification, accessToken: TOKEN },
    samples: 3,
    metrics: recordingMetrics(),
    tags: { ...TAGS, phase: 'baseline' },
  }))
  const missingTeam = errorMessage(() => triggerWaitingChange({
    client: recordingClient([response(200, 1, { items: [], nextCursor: null })]),
    baseUrl: BASE_URL,
    session: { target: targets.waitingOperator, accessToken: TOKEN },
    idempotencyKey: '123e4567-e89b-12d3-a456-426614174000',
    metrics: recordingMetrics(),
    tags: { ...TAGS, profile: 'slow-client', audience: 'store-operator', endpoint_kind: 'waiting-store-operator', traffic: 'trigger' },
  }))

  const steadyConfig = {
    profile: 'steady',
    connections: 3,
    holdDurationSeconds: 30,
    httpProbeRate: 3,
    httpMaxP95Ratio: 2,
    endpointKinds: [
      'notification-consumer',
      'waiting-consumer',
      'waiting-store-operator',
    ],
  }
  const steadyScenarios = buildSseScenarioOptions(steadyConfig)
  const steadyThresholds = buildSseThresholds(steadyConfig)
  const slowConfig = {
    ...steadyConfig,
    profile: 'slow-client',
    connections: 2,
    holdDurationSeconds: 100,
    slowClientConnections: 1,
    slowClientMaxCleanupSeconds: 60,
    companionMinLifetimeSeconds: 85,
    endpointKinds: ['waiting-store-operator'],
  }
  const slowScenarios = buildSseScenarioOptions(slowConfig)
  const slowThresholds = buildSseThresholds(slowConfig)
  const multipleCompanionScenarios = errorMessage(() => buildSseScenarioOptions({
    ...slowConfig,
    connections: 3,
    slowClientConnections: 1,
  }))
  const capacityConfig = {
    profile: 'capacity',
    connections: 7,
    connectionsPerAccount: 7,
    holdDurationSeconds: 30,
    httpProbeRate: 3,
    httpMaxP95Ratio: 2,
    endpointKinds: ['notification-consumer'],
  }
  const capacityScenarios = buildSseScenarioOptions(capacityConfig)
  const capacityThresholds = buildSseThresholds(capacityConfig)
  const roleSessions = assignSlowClientRoles([
    { target: targets.waitingOperator, accessToken: TOKEN, baselineMilliseconds: 10 },
    { target: targets.waitingOperator, accessToken: TOKEN, baselineMilliseconds: 10 },
  ], 1)
  const validSlowCleanup = {
    completed: true,
    classification: 'success',
    receivePaused: true,
    serverClosed: true,
    validEvents: 1,
  }
  const invalidSlowCleanup = errorMessage(() => requireSlowCleanupResult({
    ...validSlowCleanup,
    receivePaused: false,
  }))

  check(null, {
    'owned HTTP paths stay inside their audience contract': () =>
      ownedHttpPath(targets.notification) === '/api/v1/consumers/me/notifications?size=20'
      && ownedHttpPath(targets.waitingConsumer) === '/api/v1/consumers/me/waiting-teams/current'
      && ownedHttpPath(targets.waitingOperator) === '/api/v1/store-operators/stores/301/waiting-teams?size=20',
    'baseline uses three successful samples and keeps the conservative p95': () =>
      baseline === 30
      && baselineClient.calls.length === 3
      && baselineMetrics.entries.filter((entry) => entry.name === 'baseline').length === 3,
    'measured probe compares duration to its target baseline': () =>
      probe.success === true
      && probe.degradationRatio === 1.5
      && probeMetrics.entries.some((entry) => entry.name === 'ratio' && entry.value === 1.5)
      && probeMetrics.entries.some((entry) => entry.name === 'success' && entry.value === 1),
    'probe requests expose only safe metric dimensions': () =>
      probeClient.calls.every((call) =>
        Object.keys(call.tags).sort().join(',')
          === 'audience,endpoint_kind,phase,profile,traffic')
      && probeClient.calls[0].headers.Authorization === `Bearer ${TOKEN}`,
    'slow-client trigger calls one public WAITING transition': () =>
      triggered === true
      && triggerClient.calls.map((call) => call.method).join(',') === 'GET,POST'
      && triggerClient.calls[0].url.endsWith('/waiting-teams?status=WAITING&size=1')
      && triggerClient.calls[1].url.endsWith('/waiting-teams/901/calls')
      && JSON.parse(triggerClient.calls[1].body).expectedVersion === 4
      && triggerMetrics.entries.some((entry) => entry.name === 'trigger' && entry.value === 1),
    'failures are fail-closed without identifier leakage': () =>
      badStatus === 'owned HTTP baseline request failed'
      && missingTeam === 'slow-client trigger fixture is unavailable'
      && !`${badStatus}${missingTeam}`.includes('901')
      && !`${badStatus}${missingTeam}`.includes(TOKEN),
    'steady and reconnect run owned HTTP probes concurrently': () =>
      steadyScenarios.sse.exec === 'sseSteady'
      && steadyScenarios.owned_http_probe.exec === 'ownedHttpProbe'
      && steadyScenarios.owned_http_probe.executor === 'constant-arrival-rate'
      && steadyScenarios.owned_http_probe.rate === 3
      && steadyScenarios.owned_http_probe.duration === '30s',
    'owned HTTP thresholds require traffic status latency and scheduler health': () =>
      steadyThresholds['http_reqs{phase:measured,profile:steady,traffic:owned-http}'][0] === 'count>0'
      && steadyThresholds['http_req_failed{phase:measured,profile:steady,traffic:owned-http}'][0] === 'rate==0'
      && steadyThresholds['dropped_iterations{scenario:owned_http_probe}'][0] === 'count==0'
      && steadyThresholds['owned_http_errors{phase:measured,profile:steady,traffic:owned-http}'][0] === 'count==0'
      && steadyConfig.endpointKinds.every((kind) =>
        steadyThresholds[`owned_http_degradation_ratio{phase:measured,profile:steady,endpoint_kind:${kind},traffic:owned-http}`][0] === 'p(95)<=2'),
    'slow profile separates delayed and readiness-triggering companion streams': () =>
      slowScenarios.sse_slow.exec === 'sseSlowClient'
      && slowScenarios.sse_slow.vus === 1
      && slowScenarios.sse_companion.exec === 'sseCompanion'
      && slowScenarios.sse_companion.vus === 1
      && slowScenarios.slow_client_trigger === undefined
      && multipleCompanionScenarios === 'slow-client requires exactly one companion connection'
      && slowScenarios.owned_http_probe.executor === 'constant-arrival-rate'
      && slowThresholds['slow_client_triggers{phase:measured,profile:slow-client,traffic:trigger}'][0] === 'count==1'
      && slowThresholds['sse_slow_cleanup_duration{phase:measured,profile:slow-client,endpoint_kind:waiting-store-operator}']?.[0] === 'max<=60000'
      && slowThresholds['sse_companion_lifetime{phase:measured,profile:slow-client,endpoint_kind:waiting-store-operator}']?.[0] === 'min>=85000',
    'slow-client role assignment preserves at least one normal companion': () =>
      roleSessions.filter((session) => session.role === 'slow').length === 1
      && roleSessions.filter((session) => session.role === 'companion').length === 1,
    'slow cleanup evidence requires the receive pause and server close': () =>
      requireSlowCleanupResult(validSlowCleanup) === validSlowCleanup
      && invalidSlowCleanup === 'slow-client cleanup evidence is invalid',
    'capacity profile deliberately runs one overflow connection': () =>
      capacityScenarios.sse.exec === 'sseCapacity'
      && capacityScenarios.sse.vus === 7
      && capacityThresholds['slow_client_triggers{phase:measured,profile:slow-client,traffic:trigger}'] === undefined,
  })
}
