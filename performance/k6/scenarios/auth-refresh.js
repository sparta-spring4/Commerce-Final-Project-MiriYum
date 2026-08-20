import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'
import { jsonHeaders, originHeaders } from '../lib/session.js'

const CSRF_TOKENS_BY_CLIENT = new WeakMap()

function requestTags(tags, request) {
  return { phase: 'measured', ...tags, request }
}

function cleanupTags(tags, request) {
  return { ...tags, phase: 'cleanup', request }
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
  const fields = Object.keys(data)
  if (fields.length !== 3
      || fields.some((field) => !['accessToken', 'tokenType', 'expiresIn'].includes(field))) {
    throw new Error('token response data must match the OpenAPI fields')
  }
  if (typeof data.accessToken !== 'string' || data.accessToken === '') {
    throw new Error('token response must contain an access token')
  }
  if (data.tokenType !== 'Bearer' || data.expiresIn !== 900) {
    throw new Error('token response contract is invalid')
  }
  return data.accessToken
}

function parseCsrfTokenData(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('CSRF token response data must be an object')
  }
  const fields = Object.keys(data)
  if (fields.length !== 2 || fields.some((field) => !['token', 'headerName'].includes(field))) {
    throw new Error('CSRF token response data must match the OpenAPI fields')
  }
  if (typeof data.token !== 'string' || data.token === '' || data.headerName !== 'X-CSRF-TOKEN') {
    throw new Error('CSRF token response contract is invalid')
  }
  return data.token
}

function revokeConsumerSession({ client, baseUrl, tags }) {
  let csrfToken = CSRF_TOKENS_BY_CLIENT.get(client)
  let csrfStatus = null
  if (csrfToken === undefined) {
    const csrfTagSet = cleanupTags(tags, 'consumerCsrfToken')
    const csrfResponse = client.get(
      `${baseUrl}/api/v1/consumers/auth/csrf-tokens/current`,
      { headers: jsonHeaders(), tags: csrfTagSet, redirects: 0 },
    )
    csrfStatus = csrfResponse.status
    const csrfClassification = recordClassification(
      classifyStatus(csrfResponse.status, [429]),
      csrfTagSet,
    )
    if (csrfClassification !== 'success') {
      return {
        csrfStatus,
        logoutStatus: null,
        completed: false,
      }
    }
    csrfToken = parseCsrfTokenData(csrfResponse)
    CSRF_TOKENS_BY_CLIENT.set(client, csrfToken)
  }

  const logoutTagSet = cleanupTags(tags, 'consumerLogout')
  const logoutResponse = client.del(
    `${baseUrl}/api/v1/consumers/auth/sessions/current`,
    null,
    {
      headers: jsonHeaders({ 'X-CSRF-TOKEN': csrfToken }),
      tags: logoutTagSet,
      redirects: 0,
    },
  )
  const logoutClassification = recordClassification(
    classifyStatus(logoutResponse.status, []),
    logoutTagSet,
  )
  if (logoutClassification === 'success' && parseEnvelope(logoutResponse).data !== null) {
    throw new Error('logout response data must be null')
  }
  return {
    csrfStatus,
    logoutStatus: logoutResponse.status,
    completed: logoutClassification === 'success',
  }
}

function runWithConsumerSessionCleanup({ client, baseUrl, tags }, action) {
  let value
  let actionError
  try {
    value = action()
  } catch (error) {
    actionError = error
  }

  let cleanup
  let cleanupError
  try {
    cleanup = revokeConsumerSession({ client, baseUrl, tags })
  } catch (error) {
    cleanupError = error
  }

  if (actionError !== undefined) throw actionError
  if (cleanupError !== undefined) throw cleanupError
  return { value, cleanup }
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
    response,
  }
}

export function loginConsumer({ client, baseUrl, account, tags = {} }) {
  const result = requestConsumerSession({ client, baseUrl, account, tags })
  if (result.classification !== 'success') {
    throw new Error(`synthetic consumer login failed with HTTP ${result.status}`)
  }
  const { value: accessToken, cleanup } = runWithConsumerSessionCleanup(
    { client, baseUrl, tags },
    () => parseTokenData(result.response),
  )
  if (!cleanup.completed) {
    throw new Error(`synthetic consumer session cleanup failed with HTTP ${cleanup.logoutStatus}`)
  }
  return accessToken
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

  const { value: refresh, cleanup } = runWithConsumerSessionCleanup(
    { client, baseUrl, tags },
    () => {
      parseTokenData(login.response)
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
      return { status: response.status, classification }
    },
  )

  return {
    loginStatus: login.status,
    refreshStatus: refresh.status,
    csrfStatus: cleanup.csrfStatus,
    logoutStatus: cleanup.logoutStatus,
    classification: refresh.classification,
    completed: refresh.classification === 'success' && cleanup.completed,
  }
}
