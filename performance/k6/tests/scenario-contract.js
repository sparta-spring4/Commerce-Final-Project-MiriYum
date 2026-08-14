import { check } from 'k6'

import { runAuthRefresh } from '../scenarios/auth-refresh.js'
import { runStoreSearch } from '../scenarios/store-search.js'

export const options = {
  thresholds: {
    checks: ['rate==1'],
  },
}

function envelope(data) {
  return JSON.stringify({ code: 'SUCCESS', message: 'ok', data })
}

class RecordingClient {
  constructor() {
    this.calls = []
  }

  post(url, body, params) {
    this.calls.push({ method: 'POST', url, body, ...params })
    if (url.endsWith('/api/v1/consumers/auth/sessions')) {
      return {
        status: 200,
        body: envelope({ accessToken: 'access-token-value', tokenType: 'Bearer', expiresIn: 900 }),
        headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=refresh-cookie-value' },
      }
    }
    return {
      status: 200,
      body: envelope({ accessToken: 'rotated-access-token', tokenType: 'Bearer', expiresIn: 900 }),
      headers: { 'Set-Cookie': 'MIRIYUM_CONSUMER_REFRESH=rotated-refresh-cookie' },
    }
  }

  get(url, params) {
    this.calls.push({ method: 'GET', url, ...params })
    return {
      status: 200,
      body: envelope({
        items: [],
        normalizedCondition: {},
        warnings: [],
        ruleVersion: 'rules-v1',
        vocabularyVersion: 'vocabulary-v1',
        rankingRuleVersion: null,
        nextCursor: null,
      }),
      headers: {},
    }
  }
}

export default function () {
  const client = new RecordingClient()
  const authResult = runAuthRefresh({
    client,
    baseUrl: 'http://backend:8080',
    allowedOrigin: 'http://localhost:5173',
    account: { email: 'consumer@example.test', password: 'synthetic-password' },
    tags: { phase: 'measured' },
  })
  const searchResult = runStoreSearch({
    client,
    baseUrl: 'http://backend:8080',
    search: { input: '서울 한식' },
    tags: { phase: 'measured' },
  })

  const [loginCall, refreshCall, searchCall] = client.calls
  const combinedResult = JSON.stringify({ authResult, searchResult })

  check(null, {
    'login uses the consumer session resource': () =>
      loginCall.method === 'POST'
      && loginCall.url === 'http://backend:8080/api/v1/consumers/auth/sessions',
    'login sends only the approved credential fields': () =>
      loginCall.body === '{"email":"consumer@example.test","password":"synthetic-password"}',
    'refresh sends empty JSON with the same approved Origin': () =>
      refreshCall.method === 'POST'
      && refreshCall.url === 'http://backend:8080/api/v1/consumers/auth/token-refreshes'
      && refreshCall.body === '{}'
      && refreshCall.headers.Origin === 'http://localhost:5173',
    'search uses an encoded public searchInput': () =>
      searchCall.method === 'GET'
      && searchCall.url === 'http://backend:8080/api/v1/stores?searchInput=%EC%84%9C%EC%9A%B8%20%ED%95%9C%EC%8B%9D',
    'all measured requests carry phase tags': () =>
      client.calls.every((call) => call.tags.phase === 'measured'),
    'scenario results expose statuses but not tokens or cookies': () =>
      authResult.loginStatus === 200
      && authResult.refreshStatus === 200
      && searchResult.status === 200
      && !combinedResult.includes('access-token-value')
      && !combinedResult.includes('refresh-cookie-value'),
  })
}
