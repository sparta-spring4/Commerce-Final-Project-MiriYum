import { check, sleep } from 'k6'
import execution from 'k6/execution'
import http from 'k6/http'
import { Counter, Trend } from 'k6/metrics'
import sse from 'k6/x/sse'

import { buildSseTargets, endpointPath, validateSseFixture } from './contracts.js'
import { loadSseConfig } from './config.js'
import { openChangedStream, prepareSseSession } from './session.js'

const config = loadSseConfig(__ENV)
const fixture = JSON.parse(open(config.fixturePath))
validateSseFixture(fixture)

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
  thresholds: {
    checks: ['rate==1'],
    sse_contract_errors: ['count==0'],
    sse_transport_errors: ['count==0'],
    dropped_iterations: ['count==0'],
  },
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
      else if (classification === 'capacity_rejected') rejectedConnections.add(1, tags)
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
    openSession(session, { mode: 'reconnect' }, lastEventId)
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
