import { check } from 'k6'

import {
  classifyStatus,
  parseEnvelope,
  validateAuthPoolCapacity,
  validateFixture,
} from '../lib/contracts.js'
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
  const validFixture = {
    allowedOrigin: 'http://localhost:5173',
    accounts: [
      { alias: 'consumer-01', emailEnv: 'K6_CONSUMER_01_EMAIL', passwordEnv: 'K6_CONSUMER_01_PASSWORD' },
      { alias: 'consumer-02', emailEnv: 'K6_CONSUMER_02_EMAIL', passwordEnv: 'K6_CONSUMER_02_PASSWORD' },
      { alias: 'consumer-03', emailEnv: 'K6_CONSUMER_03_EMAIL', passwordEnv: 'K6_CONSUMER_03_PASSWORD' },
      { alias: 'consumer-04', emailEnv: 'K6_CONSUMER_04_EMAIL', passwordEnv: 'K6_CONSUMER_04_PASSWORD' },
      { alias: 'consumer-05', emailEnv: 'K6_CONSUMER_05_EMAIL', passwordEnv: 'K6_CONSUMER_05_PASSWORD' },
    ],
    auth: { accountAliases: ['consumer-01', 'consumer-02'] },
    search: { input: '서울 한식' },
    reservationTemplates: [{
      accountAlias: 'consumer-03',
      storeId: '301',
      serviceDate: '2099-08-20',
      startTime: '18:00:00',
      startOffset: '+09:00',
      party: { adultCount: 2, childCount: 0, infantCount: 0 },
      menuSelections: [],
    }],
    notification: {
      accountAliases: ['consumer-04', 'consumer-05'],
      pageSize: 2,
      minimumDeliveredItemsPerAccount: 3,
    },
  }

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
    'success envelope rejects a non-success code': () =>
      throws(() => parseEnvelope({
        status: 200,
        body: JSON.stringify({ code: 'STORE_001', message: 'not found', data: null }),
      })),
    'success envelope rejects unsupported top-level fields': () =>
      throws(() => parseEnvelope({
        status: 200,
        body: JSON.stringify({ code: 'SUCCESS', message: 'ok', data: {}, debug: true }),
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
    'complete synthetic fixture contract is accepted': () =>
      validateFixture(validFixture).accounts.length === 5,
    'duplicate synthetic account alias is rejected': () =>
      throws(() => validateFixture({
        ...validFixture,
        accounts: [validFixture.accounts[0], validFixture.accounts[0]],
      })),
    'reservation template must reference a declared account': () =>
      throws(() => validateFixture({
        ...validFixture,
        reservationTemplates: [{
          ...validFixture.reservationTemplates[0],
          accountAlias: 'unknown-consumer',
        }],
      })),
    'auth fixture must reference distinct declared accounts': () =>
      throws(() => validateFixture({
        ...validFixture,
        auth: { accountAliases: ['consumer-01', 'unknown-consumer'] },
      })),
    'auth and prepared bearer account pools must not overlap': () =>
      throws(() => validateFixture({
        ...validFixture,
        auth: { accountAliases: ['consumer-03'] },
      })),
    'auth pool must cover every globally addressable baseline VU': () =>
      throws(() => validateAuthPoolCapacity(validFixture, 3)),
    'auth pool accepts a global VU ceiling it fully covers': () =>
      validateAuthPoolCapacity(validFixture, 2) === validFixture,
    'reservation and notification account pools must not overlap': () =>
      throws(() => validateFixture({
        ...validFixture,
        reservationTemplates: [{
          ...validFixture.reservationTemplates[0],
          accountAlias: 'consumer-04',
        }],
      })),
    'notification fixture must guarantee a second page': () =>
      throws(() => validateFixture({
        ...validFixture,
        notification: {
          ...validFixture.notification,
          minimumDeliveredItemsPerAccount: 2,
        },
      })),
    'notification fixture requires two distinct declared accounts': () =>
      throws(() => validateFixture({
        ...validFixture,
        notification: {
          ...validFixture.notification,
          accountAliases: ['consumer-01', 'unknown-consumer'],
        },
      })),
    'fixture rejects inline credentials': () =>
      throws(() => validateFixture({
        ...validFixture,
        accounts: [{ ...validFixture.accounts[0], password: 'inline-secret' }, validFixture.accounts[1]],
      })),
    'fixture rejects malformed secret environment references': () =>
      throws(() => validateFixture({
        ...validFixture,
        accounts: [{ ...validFixture.accounts[0], emailEnv: 'consumer.email' }, validFixture.accounts[1]],
      })),
    'fixture rejects non-public reservation identifiers': () =>
      throws(() => validateFixture({
        ...validFixture,
        reservationTemplates: [{ ...validFixture.reservationTemplates[0], storeId: '0' }],
      })),
    'fixture rejects an empty reservation party': () =>
      throws(() => validateFixture({
        ...validFixture,
        reservationTemplates: [{
          ...validFixture.reservationTemplates[0],
          party: { adultCount: 0, childCount: 0, infantCount: 0 },
        }],
      })),
    'fixture rejects blank public search input': () =>
      throws(() => validateFixture({ ...validFixture, search: { input: '   ' } })),
  })
}
