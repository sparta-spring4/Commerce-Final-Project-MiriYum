import { check } from 'k6'

import {
  renderRecoverySummary,
  verifyRecoveryRateLimit,
} from '../lib/recovery-rate-limit.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function envelope(data) {
  return JSON.stringify({ code: 'SUCCESS', message: 'ok', data })
}

function throws(action) {
  try {
    action()
    return false
  } catch (_) {
    return true
  }
}

class RecoveryClient {
  constructor(finalLoginStatus = 429) {
    this.calls = []
    this.loginCount = 0
    this.finalLoginStatus = finalLoginStatus
  }

  post(url, body, params) {
    this.calls.push({ method: 'POST', url, body, ...params })
    if (url.endsWith('/api/v1/consumers/auth/sessions')) {
      this.loginCount += 1
      if (this.loginCount === 6 && this.finalLoginStatus === 429) {
        return {
          status: 429,
          body: JSON.stringify({ code: 'COMMON_010', message: 'rate limited', data: null }),
          headers: {},
        }
      }
      return {
        status: 200,
        body: envelope({ accessToken: 'secret-access-token', tokenType: 'Bearer', expiresIn: 900 }),
        headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=secret-refresh-cookie' },
      }
    }
    return {
      status: 200,
      body: envelope({ accessToken: 'secret-rotated-token', tokenType: 'Bearer', expiresIn: 900 }),
      headers: {},
    }
  }

  get(url, params) {
    this.calls.push({ method: 'GET', url, ...params })
    return {
      status: 200,
      body: envelope({ token: 'secret-csrf-token', headerName: 'X-CSRF-TOKEN' }),
      headers: {},
    }
  }

  del(url, body, params) {
    this.calls.push({ method: 'DELETE', url, body, ...params })
    return { status: 200, body: envelope(null), headers: {} }
  }
}

class CleanupFailureClient extends RecoveryClient {
  del(url, body, params) {
    this.calls.push({ method: 'DELETE', url, body, ...params })
    return { status: 500, body: JSON.stringify({ code: 'COMMON_001' }), headers: {} }
  }
}

const INPUT = {
  baseUrl: 'https://staging.example.test',
  allowedOrigin: 'https://staging.example.test',
  account: { email: 'synthetic@example.test', password: 'secret-password' },
}

export default function () {
  const client = new RecoveryClient()
  const result = verifyRecoveryRateLimit({ client, ...INPUT })
  const loginCalls = client.calls.filter((call) =>
    call.method === 'POST' && call.url.endsWith('/api/v1/consumers/auth/sessions'))
  const refreshCalls = client.calls.filter((call) =>
    call.method === 'POST' && call.url.endsWith('/api/v1/consumers/auth/token-refreshes'))
  const logoutCalls = client.calls.filter((call) => call.method === 'DELETE')
  const serialized = JSON.stringify(result)
  const summaryMetadata = {
    targetEnv: 'staging',
    runId: 'recovery-contract',
    commitSha: '0123456789abcdef0123456789abcdef01234567',
    harnessCommitSha: 'fedcba9876543210fedcba9876543210fedcba98',
    sourceIp: '203.0.113.10',
    forbiddenProbe: 'secret-password secret-access-token secret-refresh-cookie',
  }
  const passedSummary = JSON.parse(renderRecoverySummary({
    metrics: {
      checks: { thresholds: { 'rate==1': { ok: true } } },
    },
  }, summaryMetadata).json)
  const failedRendered = renderRecoverySummary({
    metrics: {
      checks: { thresholds: { 'rate==1': { ok: false } } },
    },
  }, summaryMetadata)
  const failedSummary = JSON.parse(failedRendered.json)

  check(null, {
    'recovery verifier sends exactly limit plus one sequential logins': () =>
      loginCalls.length === 6 && result.successfulLogins === 5,
    'every successful login session is cleaned before the final probe': () =>
      logoutCalls.length === 5 && refreshCalls.length === 0,
    'the final request must be exactly 429': () =>
      result.finalStatus === 429 && result.completed === true,
    'recovery result excludes credentials tokens cookies headers and response bodies': () =>
      !serialized.includes('synthetic@example.test')
      && !serialized.includes('secret-password')
      && !serialized.includes('secret-access-token')
      && !serialized.includes('secret-refresh-cookie')
      && !serialized.includes('secret-csrf-token')
      && !serialized.includes('Authorization')
      && !serialized.includes('COMMON_010'),
    'an early 429 does not satisfy the recovery contract': () => {
      const earlyClient = new RecoveryClient(429)
      earlyClient.loginCount = 1
      return throws(() => verifyRecoveryRateLimit({ client: earlyClient, ...INPUT }))
    },
    'a sixth successful login does not satisfy the recovery contract': () =>
      throws(() => verifyRecoveryRateLimit({ client: new RecoveryClient(200), ...INPUT })),
    'a successful login with failed session cleanup aborts recovery': () =>
      throws(() => verifyRecoveryRateLimit({ client: new CleanupFailureClient(), ...INPUT })),
    'successful recovery summary records only verified safe results': () =>
      passedSummary.thresholdsPassed === true
      && passedSummary.successfulLogins === 5
      && passedSummary.finalStatus === 429,
    'failed recovery summary never claims the expected result occurred': () =>
      failedSummary.thresholdsPassed === false
      && failedSummary.successfulLogins === null
      && failedSummary.finalStatus === null,
    'recovery summary excludes caller metadata and credential probes': () => {
      const combined = `${failedRendered.stdout}\n${failedRendered.json}\n${failedRendered.markdown}`
      return !combined.includes('secret-password')
        && !combined.includes('secret-access-token')
      && !combined.includes('secret-refresh-cookie')
        && !combined.includes('203.0.113.10')
        && !combined.includes('forbiddenProbe')
    },
  })
}
