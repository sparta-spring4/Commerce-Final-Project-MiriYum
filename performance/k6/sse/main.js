import { check, sleep } from 'k6'
import execution from 'k6/execution'
import http from 'k6/http'
import { Counter, Trend } from 'k6/metrics'
import sse from 'k6/x/sse'

import { buildSseTargets, endpointPath, validateSseFixture } from './contracts.js'
import { loadSseConfig } from './config.js'
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
    endpointKinds: config.endpointKinds,
  })

const availableTargets = buildSseTargets(fixture, config.connectionsPerAccount)
  .filter((target) => config.endpointKinds.includes(target.kind))
if (availableTargets.length < config.connections) {
  throw new Error('SSE fixture does not provide the requested connection capacity')
}
const selectedTargets = Object.freeze(availableTargets.slice(0, config.connections))

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

function thresholds() {
  const result = {
    dropped_iterations: ['count==0'],
    sse_connections_opened: [
      `count==${config.connections * (config.profile === 'reconnect' ? 2 : 1)}`,
    ],
    sse_connections_successful: [
      `count==${config.connections * (config.profile === 'reconnect' ? 2 : 1)}`,
    ],
    sse_connections_rejected: ['count==0'],
    sse_contract_errors: ['count==0'],
    sse_transport_errors: ['count==0'],
    sse_unexpected_4xx: ['count==0'],
    sse_server_5xx: ['count==0'],
    sse_unexpected_status: ['count==0'],
  }
  if (config.profile === 'reconnect') {
    result.sse_recovery_attempts = [`count==${config.connections}`]
    result.sse_recovery_successful = [`count==${config.connections}`]
  }
  for (const endpointKind of config.endpointKinds) {
    const tags = `phase:measured,profile:${config.profile},endpoint_kind:${endpointKind}`
    result[`checks{${tags}}`] = ['rate==1']
    result[`http_req_duration{${tags}}`] = ['max>=0']
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
  const execByProfile = {
    smoke: 'sseSmoke',
    reconnect: 'sseReconnect',
    steady: 'sseSteady',
    'slow-client': 'sseSlowClient',
  }
  return {
    sse: {
      executor: 'shared-iterations',
      exec: execByProfile[config.profile],
      vus: config.connections,
      iterations: config.connections,
      maxDuration: `${config.holdDurationSeconds + 90}s`,
      gracefulStop: '5s',
      tags: { phase: 'measured', profile: config.profile },
    },
  }
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
  return selectedTargets.map((target) => {
    const cacheKey = `${target.audience}:${target.accountAlias}`
    let accessToken = tokens.get(cacheKey)
    if (accessToken === undefined) {
      const reference = accountReference(target)
      accessToken = prepareSseSession({
        client: http,
        baseUrl: config.baseUrl,
        allowedOrigin: fixture.allowedOrigin,
        target,
        credentials: credentials(reference),
        tags: { profile: config.profile },
      })
      tokens.set(cacheKey, accessToken)
    }
    return { target, accessToken }
  })
}

function selectedSession(data) {
  if (!Array.isArray(data) || data.length !== selectedTargets.length) {
    throw new Error('prepared SSE sessions are missing')
  }
  return data[execution.scenario.iterationInTest % data.length]
}

function tagsFor(target) {
  return {
    phase: 'measured',
    profile: config.profile,
    audience: target.audience,
    endpoint_kind: target.kind,
  }
}

function metricAdapter() {
  return {
    opened: (value, tags) => openedConnections.add(value, tags),
    validEvent: (value, tags) => validEvents.add(value, tags),
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
    'SSE stream contract remains valid': (value) => value.completed,
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

export function sseSlowClient(data) {
  const session = selectedSession(data)
  safelyExecute(() => openSession(session, {
    mode: 'slow-client',
    delay: sleep,
    delaySeconds: config.slowClientDelaySeconds,
  }), session.target)
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
    },
  })
  return {
    stdout: rendered.stdout,
    [`/results/${config.runId}.json`]: rendered.json,
    [`/results/${config.runId}.md`]: rendered.markdown,
  }
}
