import { check, sleep } from 'k6'
import execution from 'k6/execution'
import http from 'k6/http'
import { Counter, Trend } from 'k6/metrics'
import sse from 'k6/x/sse'

import {
  buildSseTargets,
  endpointPath,
  selectCapacityTargets,
  selectSseTargets,
  validateSseFixture,
} from './contracts.js'
import { loadSseConfig, requiredSmokeEndpointKinds } from './config.js'
import {
  assignSlowClientRoles,
  buildSseScenarioOptions,
  buildSseThresholds,
  cleanupWaitingChange,
  captureOwnedHttpBaseline,
  requireSlowCleanupResult,
  runOwnedHttpProbe,
  triggerWaitingChange,
  verifyWaitingChange,
} from './probe.js'
import { runSseRecovery } from './recovery.js'
import { openChangedStream, prepareSseSession } from './session.js'
import {
  createFixtureFingerprint,
  createTargetFingerprint,
} from '../lib/smoke-proof.js'
import { renderSafeSseSummary, validateSseSmokeProof } from './summary.js'

const config = loadSseConfig(__ENV)
const fixtureText = open(config.fixturePath)
const fixture = JSON.parse(fixtureText)
validateSseFixture(fixture)
const targetFingerprint = createTargetFingerprint(config.targetEnv, config.baseUrl)
const fixtureSha256 = createFixtureFingerprint(fixtureText)
const prerequisiteSmokeProof = config.profile === 'smoke'
  ? null
  : validateSseSmokeProof(JSON.parse(open(config.smokeProofPath)), {
    targetEnv: config.targetEnv,
    commitSha: config.commitSha,
    harnessCommitSha: config.harnessCommitSha,
    targetFingerprint,
    fixtureSha256,
    endpointKinds: requiredSmokeEndpointKinds(config.profile, config.endpointKinds),
  })

const availableTargets = buildSseTargets(
  fixture,
  config.connectionsPerAccount,
  config.profile === 'capacity' ? 7 : 6,
)
  .filter((target) => config.endpointKinds.includes(target.kind))
if (availableTargets.length < config.connections) {
  throw new Error('SSE fixture does not provide the requested connection capacity')
}
const selectedTargets = config.profile === 'capacity'
  ? selectCapacityTargets(availableTargets, config.endpointKinds[0], config.connections)
  : selectSseTargets(availableTargets, config.endpointKinds, config.connections)

const openedConnections = new Counter('sse_connections_opened')
const successfulConnections = new Counter('sse_connections_successful')
const rejectedConnections = new Counter('sse_connections_rejected')
const contractErrors = new Counter('sse_contract_errors')
const transportErrors = new Counter('sse_transport_errors')
const validEvents = new Counter('sse_valid_events')
const firstEventMilliseconds = new Trend('sse_first_event', true)
const connectionMilliseconds = new Trend('sse_connection_duration', true)
const recoveryAttempts = new Counter('sse_recovery_attempts')
const recoverySuccessful = new Counter('sse_recovery_successful')
const expected4xx = new Counter('sse_expected_4xx')
const unexpected4xx = new Counter('sse_unexpected_4xx')
const server5xx = new Counter('sse_server_5xx')
const unexpectedStatus = new Counter('sse_unexpected_status')
const heartbeatFrames = new Counter('sse_heartbeat_frames')
const ownedHttpBaseline = new Trend('owned_http_baseline', true)
const ownedHttpDuration = new Trend('owned_http_duration', true)
const ownedHttpDegradationRatio = new Trend('owned_http_degradation_ratio')
const ownedHttpSuccess = new Counter('owned_http_success')
const ownedHttpErrors = new Counter('owned_http_errors')
const slowClientTriggers = new Counter('slow_client_triggers')
const slowClientCleanupMilliseconds = new Trend('sse_slow_cleanup_duration', true)
const companionLifetimeMilliseconds = new Trend('sse_companion_lifetime', true)
const recoveryMilliseconds = new Trend('sse_recovery_duration', true)
const recoveryHttpVerified = new Counter('sse_recovery_http_verified')
const recoveryCleanupSuccessful = new Counter('sse_recovery_cleanup_successful')

function thresholds() {
  const expectedCapacityRejections = config.profile === 'capacity'
    ? config.connectionsPerAccount - 6
    : 0
  const result = {
    dropped_iterations: ['count==0'],
    sse_connections_opened: [
      `count==${config.connections * (config.profile === 'reconnect' ? 2 : 1)}`,
    ],
    sse_connections_successful: [
      `count==${config.connections * (config.profile === 'reconnect' ? 2 : 1)
        - expectedCapacityRejections}`,
    ],
    sse_connections_rejected: [
      `count==${expectedCapacityRejections}`,
    ],
    sse_expected_4xx: [
      `count==${expectedCapacityRejections}`,
    ],
    sse_contract_errors: ['count==0'],
    sse_transport_errors: ['count==0'],
    sse_unexpected_4xx: ['count==0'],
    sse_server_5xx: ['count==0'],
    sse_unexpected_status: ['count==0'],
  }
  Object.assign(result, buildSseThresholds(config))
  if (config.profile === 'reconnect') {
    result.sse_recovery_attempts = [`count==${config.connections}`]
    result.sse_recovery_successful = [`count==${config.connections}`]
  }
  for (const endpointKind of config.endpointKinds) {
    const tags = `phase:measured,profile:${config.profile},endpoint_kind:${endpointKind}`
    result[`checks{${tags}}`] = ['rate==1']
    result[`sse_first_event{${tags}}`] = ['max>=0']
    result[`sse_connection_duration{${tags}}`] = ['max>=0']
    result[`sse_connections_opened{${tags}}`] = ['count>=0']
    result[`sse_connections_successful{${tags}}`] = ['count>=0']
    result[`sse_connections_rejected{${tags}}`] = ['count>=0']
    result[`sse_contract_errors{${tags}}`] = ['count==0']
    result[`sse_transport_errors{${tags}}`] = ['count==0']
    result[`sse_valid_events{${tags}}`] = ['count>=0']
    result[`sse_recovery_attempts{${tags}}`] = ['count>=0']
    result[`sse_recovery_successful{${tags}}`] = ['count>=0']
    result[`sse_expected_4xx{${tags}}`] = ['count>=0']
    result[`sse_unexpected_4xx{${tags}}`] = ['count==0']
    result[`sse_server_5xx{${tags}}`] = ['count==0']
    result[`sse_unexpected_status{${tags}}`] = ['count==0']
  }
  result[`dropped_iterations{phase:measured,profile:${config.profile}}`] = ['count==0']
  return result
}

function scenarioOptions() {
  return buildSseScenarioOptions(config)
}

export const options = {
  scenarios: scenarioOptions(),
  thresholds: thresholds(),
  insecureSkipTLSVerify: config.targetEnv === 'local',
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  summaryTimeUnit: 'ms',
  systemTags: ['status', 'method', 'scenario'],
  userAgent: `miriyum-k6-sse/${config.harnessCommitSha.slice(0, 12)}`,
}

function accountReference(target) {
  const accounts = target.audience === 'consumer'
    ? fixture.consumerAccounts
    : fixture.storeOperatorAccounts
  const account = accounts.find((candidate) => candidate.alias === target.accountAlias)
  if (account === undefined) throw new Error('SSE target account reference is missing')
  return account
}

function credentials(reference) {
  const email = __ENV[reference.emailEnv]
  const password = __ENV[reference.passwordEnv]
  if (typeof email !== 'string' || email.trim() === ''
    || typeof password !== 'string' || password === '') {
    throw new Error('synthetic SSE credential environment value is missing')
  }
  return { email, password }
}

export function setup() {
  const tokens = new Map()
  const baselines = new Map()
  let sessions = selectedTargets.map((target) => {
    const cacheKey = `${target.audience}:${target.accountAlias}`
    let accessToken = tokens.get(cacheKey)
    if (accessToken === undefined) {
      const reference = accountReference(target)
      accessToken = prepareSseSession({
        client: http,
        baseUrl: config.baseUrl,
        target,
        credentials: credentials(reference),
        tags: { profile: config.profile },
      })
      tokens.set(cacheKey, accessToken)
    }
    const baselineKey = `${target.audience}:${target.kind}:${target.storeId || ''}:${target.accountAlias}`
    let baselineMilliseconds = baselines.get(baselineKey)
    if (config.profile !== 'smoke' && baselineMilliseconds === undefined) {
      baselineMilliseconds = captureOwnedHttpBaseline({
        client: http,
        baseUrl: config.baseUrl,
        session: { target, accessToken },
        samples: 3,
        metrics: {
          baseline: (value, tags) => ownedHttpBaseline.add(value, tags),
        },
        tags: tagsFor(target, 'owned-http', 'baseline'),
      })
      baselines.set(baselineKey, baselineMilliseconds)
    }
    return { target, accessToken, baselineMilliseconds: baselineMilliseconds ?? null }
  })
  if (config.profile === 'slow-client') {
    sessions = assignSlowClientRoles(sessions, config.slowClientConnections)
  }
  return sessions
}

function isExpectedCapacityRejection() {
  return config.profile === 'capacity'
}

function selectedSession(data, role = null) {
  if (!Array.isArray(data) || data.length !== selectedTargets.length) {
    throw new Error('prepared SSE sessions are missing')
  }
  const candidates = role === null ? data : data.filter((session) => session.role === role)
  if (candidates.length === 0) throw new Error('prepared SSE session role is missing')
  return candidates[execution.scenario.iterationInTest % candidates.length]
}

function tagsFor(target, traffic = 'sse-stream', phase = 'measured') {
  return {
    phase,
    profile: config.profile,
    audience: target.audience,
    endpoint_kind: target.kind,
    traffic,
  }
}

function metricAdapter() {
  return {
    opened: (value, tags) => openedConnections.add(value, tags),
    validEvent: (value, tags) => validEvents.add(value, tags),
    heartbeatFrame: (value, tags) => heartbeatFrames.add(value, tags),
    contractError: (value, tags) => contractErrors.add(value, tags),
    transportError: (value, tags) => transportErrors.add(value, tags),
    firstEventMilliseconds: (value, tags) => firstEventMilliseconds.add(value, tags),
    connectionResult: (classification, tags) => {
      if (classification === 'success') successfulConnections.add(1, tags)
      else if (classification === 'capacity_rejected') {
        rejectedConnections.add(1, tags)
        expected4xx.add(1, tags)
      } else if (classification === 'unauthorized'
        || classification === 'unexpected_client_error') {
        unexpected4xx.add(1, tags)
      } else if (classification === 'unavailable' || classification === 'server_error') {
        server5xx.add(1, tags)
      } else if (classification === 'unexpected_status') {
        unexpectedStatus.add(1, tags)
      }
    },
  }
}

function openSession(session, behavior, lastEventId = null) {
  const target = session.target
  const startedAt = Date.now()
  const result = openChangedStream({
    transport: sse,
    url: `${config.baseUrl}${endpointPath(target)}`,
    accessToken: session.accessToken,
    lastEventId,
    endpointKind: target.kind,
    behavior: {
      timeoutSeconds: config.holdDurationSeconds + 5,
      ...behavior,
    },
    metrics: metricAdapter(),
    tags: tagsFor(target),
  })
  connectionMilliseconds.add(Date.now() - startedAt, tagsFor(target))
  check(result, {
    'SSE stream contract remains valid': (value) => value.completed
      || (isExpectedCapacityRejection() && value.classification === 'capacity_rejected'),
  }, tagsFor(target))
  return result
}

function safelyExecute(action, target) {
  try {
    action()
  } catch (_) {
    contractErrors.add(1, tagsFor(target))
    check(null, { 'SSE stream contract remains valid': () => false }, tagsFor(target))
  }
}

export function sseSmoke(data) {
  const session = selectedSession(data)
  safelyExecute(() => openSession(session, { mode: 'smoke' }), session.target)
}

export function sseReconnect(data) {
  const session = selectedSession(data)
  safelyExecute(() => {
    let lastEventId = null
    const first = openSession(session, {
      mode: 'reconnect',
      onLastEventId: (value) => { lastEventId = value },
    })
    if (!first.completed || lastEventId === null) {
      throw new Error('SSE reconnect cursor was not captured')
    }
    recoveryAttempts.add(1, tagsFor(session.target))
    const recovered = openSession(session, { mode: 'reconnect' }, lastEventId)
    if (!recovered.completed) throw new Error('SSE reconnect did not recover')
    recoverySuccessful.add(1, tagsFor(session.target))
    lastEventId = null
  }, session.target)
}

export function sseSteady(data) {
  const session = selectedSession(data)
  safelyExecute(() => openSession(session, { mode: 'steady' }), session.target)
}

export function sseRecovery(data) {
  const session = selectedSession(data)
  safelyExecute(() => {
    recoveryAttempts.add(1, tagsFor(session.target))
    runSseRecovery({
      armDelaySeconds: config.recoveryArmDelaySeconds,
      maxRecoverySeconds: config.recoveryMaxSeconds,
      delay: sleep,
      ready: () => console.log(
        `SSE_RECOVERY_READY stop Valkey within ${config.recoveryArmDelaySeconds}s`,
      ),
      openStream: (behavior) => openSession(session, behavior),
      trigger: () => {
        let mutation = null
        triggerWaitingChange({
          client: http,
          baseUrl: config.baseUrl,
          session,
          idempotencyKey: config.recoveryTriggerIdempotencyKey,
          tags: tagsFor(session.target, 'trigger'),
          onMutation: (value) => { mutation = value },
        })
        if (mutation === null) throw new Error('recovery mutation evidence is missing')
        return mutation
      },
      verify: (mutation) => verifyWaitingChange({
        client: http,
        baseUrl: config.baseUrl,
        session,
        mutation,
        tags: tagsFor(session.target, 'owned-http'),
      }),
      cleanup: (mutation) => cleanupWaitingChange({
        client: http,
        baseUrl: config.baseUrl,
        session,
        mutation,
        idempotencyKey: config.recoveryCleanupIdempotencyKey,
        tags: tagsFor(session.target, 'cleanup', 'cleanup'),
      }),
      metrics: {
        duration: (value) => recoveryMilliseconds.add(value, tagsFor(session.target)),
        httpVerified: (value) => recoveryHttpVerified.add(
          value, tagsFor(session.target, 'owned-http'),
        ),
        cleanupSuccessful: (value) => recoveryCleanupSuccessful.add(
          value, tagsFor(session.target, 'cleanup', 'cleanup'),
        ),
      },
    })
    recoverySuccessful.add(1, tagsFor(session.target))
  }, session.target)
}

export function sseSlowClient(data) {
  const session = selectedSession(data, 'slow')
  safelyExecute(() => {
    const startedAt = Date.now()
    const result = openSession(session, {
      mode: 'slow-client',
      delay: sleep,
      delaySeconds: config.slowClientDelaySeconds,
      minimumValidEvents: 1,
      requireServerClose: true,
    })
    requireSlowCleanupResult(result)
    slowClientCleanupMilliseconds.add(Date.now() - startedAt, tagsFor(session.target))
  }, session.target)
}

export function sseCapacity(data) {
  const session = selectedSession(data)
  safelyExecute(() => openSession(session, { mode: 'steady' }), session.target)
}

export function sseCompanion(data) {
  const session = selectedSession(data, 'companion')
  safelyExecute(() => {
    const startedAt = Date.now()
    const triggerTags = tagsFor(session.target, 'trigger')
    openSession(session, {
      mode: 'steady',
      minimumValidEvents: 2,
      requireServerClose: true,
      onFirstValidEvent: () => {
        const triggered = triggerWaitingChange({
          client: http,
          baseUrl: config.baseUrl,
          session,
          idempotencyKey: config.slowClientIdempotencyKey,
          metrics: {
            trigger: (value, metricTags) => slowClientTriggers.add(value, metricTags),
          },
          tags: triggerTags,
        })
        check(triggered, { 'slow-client follow-up change is triggered': Boolean }, triggerTags)
      },
    })
    companionLifetimeMilliseconds.add(Date.now() - startedAt, tagsFor(session.target))
  }, session.target)
}

export function ownedHttpProbe(data) {
  const session = selectedSession(data)
  const tags = tagsFor(session.target, 'owned-http')
  try {
    const result = runOwnedHttpProbe({
      client: http,
      baseUrl: config.baseUrl,
      session,
      maxP95Ratio: config.httpMaxP95Ratio,
      metrics: {
        duration: (value, metricTags) => ownedHttpDuration.add(value, metricTags),
        degradationRatio: (value, metricTags) => ownedHttpDegradationRatio.add(value, metricTags),
        success: (value, metricTags) => ownedHttpSuccess.add(value, metricTags),
        error: (value, metricTags) => ownedHttpErrors.add(value, metricTags),
      },
      tags,
    })
    check(result, { 'owned HTTP probe remains healthy': (value) => value.success }, tags)
  } catch (_) {
    check(null, { 'owned HTTP probe remains healthy': () => false }, tags)
  }
}

export function handleSummary(data) {
  const rendered = renderSafeSseSummary(data, {
    targetEnv: config.targetEnv,
    profile: config.profile,
    runId: config.runId,
    prerequisiteSmokeRunId: prerequisiteSmokeProof === null
      ? null
      : prerequisiteSmokeProof.runId,
    commitSha: config.commitSha,
    harnessCommitSha: config.harnessCommitSha,
    targetFingerprint,
    fixtureSha256,
    endpointKinds: config.endpointKinds,
    limits: {
      connections: config.connections,
      connectionsPerAccount: config.connectionsPerAccount,
      holdDurationSeconds: config.holdDurationSeconds,
      slowClientDelaySeconds: config.slowClientDelaySeconds,
      slowClientMaxCleanupSeconds: config.slowClientMaxCleanupSeconds,
      companionMinLifetimeSeconds: config.companionMinLifetimeSeconds,
      httpProbeRate: config.httpProbeRate,
      httpMaxP95Ratio: config.httpMaxP95Ratio,
      slowClientConnections: config.slowClientConnections,
      recoveryArmDelaySeconds: config.recoveryArmDelaySeconds,
      recoveryMaxSeconds: config.recoveryMaxSeconds,
    },
  })
  return {
    stdout: rendered.stdout,
    [`/results/${config.runId}.json`]: rendered.json,
    [`/results/${config.runId}.md`]: rendered.markdown,
  }
}
