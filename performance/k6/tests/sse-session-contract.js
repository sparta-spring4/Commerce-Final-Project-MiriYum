import { check } from 'k6'

import {
  openChangedStream,
  prepareSseSession,
  selectUnexpected403ErrorCodeBucket,
} from '../sse/session.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function envelope(data) {
  return JSON.stringify({ code: 'SUCCESS', message: 'ok', data })
}

function recordingClient({ logoutStatus = 200 } = {}) {
  const calls = []
  return {
    calls,
    post(url, body, params) {
      calls.push({ method: 'POST', url, body, ...params })
      return {
        status: 200,
        body: envelope({ accessToken: 'access-token-memory-only', tokenType: 'Bearer', expiresIn: 900 }),
        headers: { 'Set-Cookie': 'refresh-cookie-memory-only' },
      }
    },
    get(url, params) {
      calls.push({ method: 'GET', url, body: null, ...params })
      return {
        status: 200,
        body: envelope({ token: 'csrf-memory-only', headerName: 'X-CSRF-TOKEN' }),
        headers: { 'Set-Cookie': 'csrf-cookie-memory-only' },
      }
    },
    del(url, body, params) {
      calls.push({ method: 'DELETE', url, body, ...params })
      return {
        status: logoutStatus,
        body: logoutStatus === 200
          ? envelope(null)
          : JSON.stringify({ code: 'COMMON_012', message: 'unavailable', data: null }),
        headers: {},
      }
    },
  }
}

function prepare(audience, client = recordingClient()) {
  const accessToken = prepareSseSession({
    client,
    baseUrl: 'https://loadtest-proxy:8443',
    target: { audience, kind: audience === 'consumer' ? 'notification-consumer' : 'waiting-store-operator' },
    credentials: { email: 'synthetic@example.test', password: 'synthetic-password' },
    tags: { profile: 'smoke', forbidden: 'must-not-propagate' },
  })
  return { accessToken, client }
}

function errorMessage(action) {
  try {
    action()
    return null
  } catch (error) {
    return error.message
  }
}

function unexpectedResponse(status, connectionStage, diagnosticBody = 'forbidden-body') {
  const results = []
  const diagnosticCalls = []
  const transport = {
    open(_url, _params, setup) {
      setup({ on() {}, close() {} })
      return { status, headers: { Authorization: 'forbidden-header' }, error: '' }
    },
  }
  openChangedStream({
    transport,
    diagnosticClient: {
      get(url, params) {
        diagnosticCalls.push({ url, params })
        return { status, body: diagnosticBody, headers: { Authorization: 'forbidden-header' } }
      },
    },
    url: 'https://loadtest-proxy:8443/api/v1/consumers/me/notification-events',
    accessToken: 'access-token-memory-only',
    endpointKind: 'notification-consumer',
    connectionStage,
    behavior: { mode: 'reconnect' },
    metrics: {
      connectionResult: (classification, tags, diagnostic) => {
        results.push({ classification, tags, diagnostic })
      },
    },
    tags: {
      phase: 'measured',
      profile: 'reconnect',
      audience: 'consumer',
      endpoint_kind: 'notification-consumer',
      traffic: 'sse-stream',
    },
  })
  return {
    ...results[0],
    diagnosticCallCount: diagnosticCalls.length,
    diagnosticRequestIsScoped: diagnosticCalls.length === 1
      && diagnosticCalls[0].url.endsWith('/api/v1/consumers/me/notification-events')
      && diagnosticCalls[0].params.headers.Authorization === 'Bearer access-token-memory-only'
      && diagnosticCalls[0].params.redirects === 0
      && diagnosticCalls[0].params.timeout === '2s'
      && diagnosticCalls[0].params.tags.traffic === 'sse-diagnostic',
  }
}

export default function () {
  const consumer = prepare('consumer')
  const operator = prepare('store-operator')
  const forbidden = unexpectedResponse(403, 'reconnect', JSON.stringify({
    code: 'AUTH_006',
    message: 'forbidden-message',
  }))
  const remappedCapacity = unexpectedResponse(403, 'initial', JSON.stringify({
    code: 'COMMON_010',
    message: 'rate-limit-message',
  }))
  const unknownForbidden = unexpectedResponse(403, 'reconnect', JSON.stringify({
    code: 'AUTH_999',
    message: 'unknown-message',
  }))
  const malformedForbidden = unexpectedResponse(403, 'reconnect', '{broken-json')
  const unauthorized = unexpectedResponse(401, 'reconnect', JSON.stringify({
    code: 'AUTH_006',
    message: 'must-not-be-requested',
  }))
  const rateLimited = unexpectedResponse(429, 'initial')
  const consumerBody = JSON.parse(consumer.client.calls[0].body)
  const operatorBody = JSON.parse(operator.client.calls[0].body)

  check(null, {
    'consumer session uses only the consumer auth namespace': () =>
      consumer.client.calls.map((call) => call.url).every((url) =>
        url.startsWith('https://loadtest-proxy:8443/api/v1/consumers/auth/')),
    'store operator session uses only the store-operator auth namespace': () =>
      operator.client.calls.map((call) => call.url).every((url) =>
        url.startsWith('https://loadtest-proxy:8443/api/v1/store-operators/auth/')),
    'login body contains only email and password': () =>
      JSON.stringify(Object.keys(consumerBody).sort()) === JSON.stringify(['email', 'password'])
      && JSON.stringify(Object.keys(operatorBody).sort()) === JSON.stringify(['email', 'password']),
    'both namespaces complete login then CSRF then logout in order': () =>
      [consumer.client, operator.client].every((client) =>
        client.calls.map((call) => call.method).join(',') === 'POST,GET,DELETE'),
    'logout carries only the namespace CSRF token as sensitive header': () =>
      [consumer.client, operator.client].every((client) => {
        const logout = client.calls[2]
        return logout.body === null
          && logout.headers['X-CSRF-TOKEN'] === 'csrf-memory-only'
          && logout.headers.Authorization === undefined
      }),
    'request tags contain only approved dimensions': () =>
      [consumer.client, operator.client].every((client) =>
        client.calls.every((call) => Object.keys(call.tags).every((tag) =>
          ['phase', 'profile', 'audience', 'endpoint_kind'].includes(tag)))),
    'Access Token is returned only after cleanup as a string': () =>
      consumer.accessToken === 'access-token-memory-only'
      && operator.accessToken === 'access-token-memory-only'
      && typeof consumer.accessToken === 'string'
      && consumer.client.calls.length === 3
      && operator.client.calls.length === 3,
    'cleanup failure rejects the prepared session without exposing values': () => {
      const message = errorMessage(() => prepare('consumer', recordingClient({ logoutStatus: 503 })))
      return message === 'synthetic SSE session cleanup failed'
        && !message.includes('access-token-memory-only')
        && !message.includes('csrf-memory-only')
    },
    'unexpected 4xx exposes only an allowlisted error code bucket status stage endpoint and UTC time': () =>
      forbidden.classification === 'unexpected_client_error'
      && forbidden.diagnostic.statusBucket === '403'
      && forbidden.diagnostic.errorCodeBucket === 'AUTH_006'
      && forbidden.diagnostic.connectionStage === 'reconnect'
      && forbidden.diagnostic.endpointKind === 'notification-consumer'
      && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(
        forbidden.diagnostic.observedAtUtc,
      )
      && JSON.stringify(Object.keys(forbidden.diagnostic).sort()) === JSON.stringify([
        'classification', 'connectionStage', 'endpointKind', 'errorCodeBucket',
        'observedAtUtc', 'statusBucket',
      ])
      && !JSON.stringify(forbidden).includes('forbidden-header')
      && !JSON.stringify(forbidden).includes('forbidden-message')
      && !JSON.stringify(forbidden).includes('access-token-memory-only'),
    '403 diagnostics distinguish a remapped capacity code without retaining its message': () =>
      remappedCapacity.diagnostic.errorCodeBucket === 'COMMON_010'
      && remappedCapacity.diagnosticCallCount === 1
      && remappedCapacity.diagnosticRequestIsScoped
      && !JSON.stringify(remappedCapacity).includes('rate-limit-message'),
    'unknown and malformed 403 bodies collapse to one fixed safe bucket': () =>
      unknownForbidden.diagnostic.errorCodeBucket === 'other-or-missing'
      && malformedForbidden.diagnostic.errorCodeBucket === 'other-or-missing'
      && !JSON.stringify(unknownForbidden).includes('AUTH_999')
      && !JSON.stringify(unknownForbidden).includes('unknown-message')
      && !JSON.stringify(malformedForbidden).includes('broken-json'),
    '403 code selection preserves the status-code association under mixed 401 and 403 failures': () =>
      unauthorized.diagnosticCallCount === 0
      && selectUnexpected403ErrorCodeBucket(unauthorized.diagnostic) === null
      && selectUnexpected403ErrorCodeBucket(forbidden.diagnostic) === 'AUTH_006',
    'capacity 429 remains separate and does not emit unexpected 4xx diagnostics': () =>
      rateLimited.classification === 'capacity_rejected'
      && rateLimited.diagnostic === null
      && rateLimited.diagnosticCallCount === 0,
  })
}
