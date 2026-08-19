import { check } from 'k6'

import { prepareSseSession } from '../sse/session.js'

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
    allowedOrigin: 'https://loadtest-proxy:8443',
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

export default function () {
  const consumer = prepare('consumer')
  const operator = prepare('store-operator')
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
  })
}
