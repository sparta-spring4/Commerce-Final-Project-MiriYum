import { check } from 'k6'

import { classifyStatus, parseEnvelope } from '../lib/contracts.js'
import { bearerHeaders, deterministicUuid, jsonHeaders, originHeaders } from '../lib/session.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function throws(action) {
  try {
    action()
    return false
  } catch (_) {
    return true
  }
}

export default function () {
  check(null, {
    'success envelope is parsed from a complete response': () => {
      const envelope = parseEnvelope({
        status: 200,
        body: JSON.stringify({ code: 'SUCCESS', message: 'ok', data: { items: [] } }),
      })
      return envelope.code === 'SUCCESS' && Array.isArray(envelope.data.items)
    },
    'malformed JSON response is rejected': () =>
      throws(() => parseEnvelope({ status: 200, body: '{broken' })),
    'response missing an envelope field is rejected': () =>
      throws(() => parseEnvelope({
        status: 200,
        body: JSON.stringify({ code: 'SUCCESS', data: {} }),
      })),
    'expected 429 is separated from unexpected failures': () =>
      classifyStatus(429, [409, 429]) === 'expected_4xx',
    'unexpected 401 is classified separately': () =>
      classifyStatus(401, [409, 429]) === 'unexpected_4xx',
    '503 is classified as a server failure': () =>
      classifyStatus(503, [409, 429]) === 'server_5xx',
    'redirect is never treated as success': () =>
      classifyStatus(302, []) === 'unexpected_status',
    'idempotency key is a stable UUID v4': () => {
      const first = deterministicUuid('run-1:2:3')
      const second = deterministicUuid('run-1:2:3')
      return first === second
        && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(first)
    },
    'different idempotency seeds do not collide': () =>
      deterministicUuid('run-1:2:3') !== deterministicUuid('run-1:2:4'),
    'session headers preserve required security fields': () => {
      const json = jsonHeaders({ 'X-Test': 'one' })
      const bearer = bearerHeaders('access-token', { Authorization: 'Bearer attacker' })
      const origin = originHeaders('http://localhost:5173')
      return json['Content-Type'] === 'application/json'
        && json['X-Test'] === 'one'
        && bearer.Authorization === 'Bearer access-token'
        && origin.Origin === 'http://localhost:5173'
    },
  })
}
