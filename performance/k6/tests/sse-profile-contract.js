import { check, sleep } from 'k6'

import * as sessionContract from '../sse/session.js'

const { applySteadyMinimumLifetime, openChangedStream } = sessionContract

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function validEvent(name = 'notifications.changed', id = 'opaque_cursor-1') {
  return { name, data: '{}', id }
}

function heartbeat(comment = 'keepalive') {
  return { name: '', data: '', id: '', comment }
}

function fakeTransport({
  status = 200,
  events = [],
  fail = false,
  errorDelaySeconds = 0,
  open = true,
  returnDelaySeconds = 0,
} = {}) {
  const calls = []
  let closeCount = 0
  return {
    calls,
    get closeCount() {
      return closeCount
    },
    open(url, params, setup) {
      const handlers = {}
      let closed = false
      const client = {
        on(name, handler) {
          handlers[name] = handler
        },
        close() {
          closeCount += 1
          closed = true
        },
      }
      calls.push({ url, params })
      setup(client)
      if (open && handlers.open !== undefined) handlers.open()
      if (fail && errorDelaySeconds === 0 && handlers.error !== undefined) {
        handlers.error({ error: () => 'forbidden transport detail' })
      }
      for (const event of events) {
        if (closed) break
        if (handlers.event !== undefined) handlers.event(event)
      }
      if (fail && errorDelaySeconds > 0 && handlers.error !== undefined) {
        sleep(errorDelaySeconds)
        handlers.error({ error: () => 'forbidden transport detail' })
      }
      if (returnDelaySeconds > 0) sleep(returnDelaySeconds)
      return {
        status,
        headers: { Authorization: 'forbidden-response-header' },
        body: 'forbidden-response-body',
      }
    },
  }
}

function openWith({
  transport = fakeTransport({ events: [validEvent()] }),
  behavior = { mode: 'smoke' },
  lastEventId = null,
  endpointKind = 'notification-consumer',
  metrics = {},
} = {}) {
  const result = openChangedStream({
    transport,
    url: 'https://loadtest-proxy:8443/api/v1/consumers/me/notification-events',
    accessToken: 'access-token-memory-only',
    lastEventId,
    endpointKind,
    behavior,
    metrics,
    tags: {
      phase: 'measured',
      profile: behavior.mode,
      audience: 'consumer',
      endpoint_kind: endpointKind,
      traffic: 'sse-stream',
    },
  })
  return { result, transport }
}

export default function () {
  check(null, {
    'reconnect preserves close settle and cursor reconnect ordering': () => {
      if (typeof sessionContract.runReconnectCycle !== 'function') return false
      const steps = []
      const result = sessionContract.runReconnectCycle({
        openInitial: () => {
          steps.push('initial-closed')
          return { completed: true, lastEventId: 'opaque-cursor' }
        },
        delay: (seconds) => steps.push(`settle:${seconds}`),
        beforeReconnect: () => steps.push('before-reconnect'),
        openReconnect: (cursor) => {
          steps.push(`reconnect:${cursor}`)
          return { completed: true }
        },
        settleSeconds: 6,
      })
      return result.initialCompleted === true
        && result.recoveryCompleted === true
        && !JSON.stringify(result).includes('opaque-cursor')
        && JSON.stringify(steps) === JSON.stringify([
          'initial-closed',
          'settle:6',
          'before-reconnect',
          'reconnect:opaque-cursor',
        ])
    },
    'reconnect waits for the bounded registry settle window': () => {
      const delays = []
      return typeof sessionContract.waitForReconnectSettle === 'function'
        && sessionContract.waitForReconnectSettle((seconds) => delays.push(seconds), 6) === true
        && JSON.stringify(delays) === JSON.stringify([6])
    },
    'wrapper applies the hold invariant only to steady': () => {
      const steady = applySteadyMinimumLifetime({ mode: 'steady' }, 30, 'steady')
      const smoke = applySteadyMinimumLifetime({ mode: 'smoke' }, 30, 'smoke')
      const reconnect = applySteadyMinimumLifetime({ mode: 'reconnect' }, 30, 'reconnect')
      const recovery = applySteadyMinimumLifetime({ mode: 'recovery' }, 30, 'recovery')
      const slowClient = applySteadyMinimumLifetime({ mode: 'slow-client' }, 30, 'slow-client')
      const companion = applySteadyMinimumLifetime({ mode: 'steady' }, 30, 'slow-client')
      const capacity = applySteadyMinimumLifetime({ mode: 'steady' }, 30, 'capacity')
      return steady.minimumLifetimeSeconds === 30
        && smoke.minimumLifetimeSeconds === undefined
        && reconnect.minimumLifetimeSeconds === undefined
        && recovery.minimumLifetimeSeconds === undefined
        && slowClient.minimumLifetimeSeconds === undefined
        && companion.minimumLifetimeSeconds === undefined
        && capacity.minimumLifetimeSeconds === undefined
    },
    'smoke closes after exactly one validated frame': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent(), validEvent()] }),
      })
      return opened.result.classification === 'success'
        && opened.result.validEvents === 1
        && opened.transport.closeCount === 1
    },
    'reconnect forwards prior ID only through Last-Event-ID': () => {
      const opened = openWith({
        lastEventId: 'prior_cursor-1',
        behavior: { mode: 'reconnect' },
      })
      const request = opened.transport.calls[0]
      const serializedResult = JSON.stringify(opened.result)
      return request.params.headers['Last-Event-ID'] === 'prior_cursor-1'
        && !serializedResult.includes('prior_cursor-1')
        && !serializedResult.includes('opaque_cursor-1')
        && opened.transport.closeCount === 1
    },
    'steady leaves the client open until transport completion': () => {
      const opened = openWith({ behavior: { mode: 'steady' } })
      return opened.result.classification === 'success'
        && opened.transport.closeCount === 0
    },
    'recovery closes only after the second validated changed frame': () => {
      let firstCallbacks = 0
      let recoveredCallbacks = 0
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent(), validEvent('notifications.changed', 'opaque_cursor-2')] }),
        behavior: {
          mode: 'recovery',
          minimumValidEvents: 2,
          onFirstValidEvent: () => { firstCallbacks += 1 },
          onRecoveryValidEvent: () => { recoveredCallbacks += 1 },
        },
      })
      return opened.result.classification === 'success'
        && opened.result.validEvents === 2
        && opened.transport.closeCount === 1
        && firstCallbacks === 1
        && recoveredCallbacks === 1
    },
    'slow client accepts server cleanup after pausing on the initial changed frame': () => {
      const delays = []
      let firstValidCallbacks = 0
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent()] }),
        behavior: {
          mode: 'slow-client',
          delaySeconds: 2,
          delay: (seconds) => delays.push(seconds),
          minimumValidEvents: 1,
          requireServerClose: true,
          onFirstValidEvent: () => { firstValidCallbacks += 1 },
        },
      })
      return JSON.stringify(delays) === JSON.stringify([2])
        && firstValidCallbacks === 1
        && opened.transport.closeCount === 0
        && opened.result.validEvents === 1
        && opened.result.receivePaused === true
        && opened.result.serverClosed === true
        && opened.result.classification === 'success'
    },
    'slow client still requires an initial changed signal': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [heartbeat()] }),
        behavior: {
          mode: 'slow-client',
          delaySeconds: 2,
          delay: () => {},
          minimumValidEvents: 1,
          requireServerClose: true,
        },
      })
      return opened.result.classification === 'missing_event'
        && opened.result.validEvents === 0
        && opened.result.receivePaused === false
        && opened.transport.closeCount === 0
    },
    'validated cursor can be handed to an in-memory callback but not returned': () => {
      let captured = null
      const opened = openWith({
        behavior: { mode: 'smoke', onLastEventId: (value) => { captured = value } },
      })
      return captured === 'opaque_cursor-1'
        && !JSON.stringify(opened.result).includes(captured)
    },
    'incorrect event is classified without response or cursor values': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent('waiting.changed')] }),
      })
      const serialized = JSON.stringify(opened.result)
      return opened.result.classification === 'contract_error'
        && opened.transport.closeCount === 1
        && !serialized.includes('waiting.changed')
        && !serialized.includes('forbidden-response')
    },
    '401 429 and 503 have bounded status classifications': () => {
      const classifications = [401, 429, 503].map((status) => openWith({
        transport: fakeTransport({ status }),
        behavior: { mode: 'steady' },
      }).result.classification)
      return JSON.stringify(classifications)
        === JSON.stringify(['unauthorized', 'capacity_rejected', 'unavailable'])
    },
    'transport error is classified without error detail': () => {
      const opened = openWith({
        transport: fakeTransport({ status: 200, fail: true }),
        behavior: { mode: 'steady' },
      })
      const serialized = JSON.stringify(opened.result)
      return opened.result.classification === 'transport_error'
        && !serialized.includes('forbidden transport detail')
    },
    'steady treats termination after the approved hold as normal completion': () => {
      const opened = openWith({
        transport: fakeTransport({
          events: [validEvent()],
          fail: true,
          errorDelaySeconds: 1,
        }),
        behavior: { mode: 'steady', minimumLifetimeSeconds: 1 },
      })
      return opened.result.classification === 'success'
        && opened.result.completed === true
        && opened.result.validEvents === 1
    },
    'steady still rejects a transport error before the approved hold': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent()], fail: true }),
        behavior: { mode: 'steady', minimumLifetimeSeconds: 1 },
      })
      return opened.result.classification === 'transport_error'
        && opened.result.completed === false
    },
    'steady rejects a valid event without an open callback': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent()], open: false, returnDelaySeconds: 1 }),
        behavior: { mode: 'steady', minimumLifetimeSeconds: 1 },
      })
      return opened.result.classification === 'not_opened'
        && opened.result.completed === false
    },
    'steady rejects a normal return before the approved hold': () => {
      const opened = openWith({
        transport: fakeTransport({ events: [validEvent()] }),
        behavior: { mode: 'steady', minimumLifetimeSeconds: 1 },
      })
      return opened.result.classification === 'lifetime_too_short'
        && opened.result.completed === false
    },
    'request contains bearer but result and tags do not expose it': () => {
      const opened = openWith()
      const request = opened.transport.calls[0]
      const safeTagNames = Object.keys(request.params.tags).every((tag) =>
        ['phase', 'profile', 'audience', 'endpoint_kind', 'traffic'].includes(tag))
      return request.params.headers.Authorization === 'Bearer access-token-memory-only'
        && safeTagNames
        && !JSON.stringify(opened.result).includes('access-token-memory-only')
    },
  })
}
