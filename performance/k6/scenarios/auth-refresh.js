import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'
import { jsonHeaders, originHeaders } from '../lib/session.js'

function requestTags(tags, request) {
  return { phase: 'measured', ...tags, request }
}

function requireCredentials(account) {
  if (account === null || typeof account !== 'object') {
    throw new Error('synthetic consumer account is required')
  }
  if (typeof account.email !== 'string' || account.email.trim() === '') {
    throw new Error('synthetic consumer email is required')
  }
  if (typeof account.password !== 'string' || account.password === '') {
    throw new Error('synthetic consumer password is required')
  }
}

function parseTokenData(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('token response data must be an object')
  }
  if (typeof data.accessToken !== 'string' || data.accessToken === '') {
    throw new Error('token response must contain an access token')
  }
  if (data.tokenType !== 'Bearer' || data.expiresIn !== 900) {
    throw new Error('token response contract is invalid')
  }
  return data.accessToken
}

function requestConsumerSession({ client, baseUrl, account, tags }) {
  requireCredentials(account)
  const requestTagSet = requestTags(tags, 'consumerLogin')
  const response = client.post(
    `${baseUrl}/api/v1/consumers/auth/sessions`,
    JSON.stringify({ email: account.email, password: account.password }),
    { headers: jsonHeaders(), tags: requestTagSet, redirects: 0 },
  )
  const classification = recordClassification(
    classifyStatus(response.status, [429]),
    requestTagSet,
  )
  return {
    status: response.status,
    classification,
    accessToken: classification === 'success' ? parseTokenData(response) : null,
  }
}

export function loginConsumer({ client, baseUrl, account, tags = {} }) {
  const result = requestConsumerSession({ client, baseUrl, account, tags })
  if (result.classification !== 'success') {
    throw new Error(`synthetic consumer login failed with HTTP ${result.status}`)
  }
  return result.accessToken
}

export function runAuthRefresh({ client, baseUrl, allowedOrigin, account, tags = {} }) {
  const login = requestConsumerSession({ client, baseUrl, account, tags })
  if (login.classification !== 'success') {
    return {
      loginStatus: login.status,
      refreshStatus: null,
      classification: login.classification,
      completed: false,
    }
  }

  const requestTagSet = requestTags(tags, 'consumerTokenRefresh')
  const response = client.post(
    `${baseUrl}/api/v1/consumers/auth/token-refreshes`,
    '{}',
    { headers: originHeaders(allowedOrigin), tags: requestTagSet, redirects: 0 },
  )
  const classification = recordClassification(
    classifyStatus(response.status, [429]),
    requestTagSet,
  )
  if (classification === 'success') parseTokenData(response)

  return {
    loginStatus: login.status,
    refreshStatus: response.status,
    classification,
    completed: classification === 'success',
  }
}
