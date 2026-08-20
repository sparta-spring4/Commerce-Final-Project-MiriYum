import { parseEnvelope } from '../lib/contracts.js'
import { jsonHeaders } from '../lib/session.js'
import { validateChangedEvent } from './contracts.js'

const AUTH_PATHS = Object.freeze({
  consumer: '/api/v1/consumers/auth',
  'store-operator': '/api/v1/store-operators/auth',
})
const ENDPOINT_AUDIENCES = Object.freeze({
  'notification-consumer': 'consumer',
  'waiting-consumer': 'consumer',
  'waiting-store-operator': 'store-operator',
})
const TAG_FIELDS = Object.freeze(['profile', 'audience', 'endpoint_kind'])

function requireText(name, value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${name} is required`)
  }
  return value.trim()
}

function safeTags(target, tags, phase) {
  const result = {
    phase,
    profile: requireText('profile tag', tags.profile),
    audience: target.audience,
    endpoint_kind: target.kind,
  }
  for (const field of TAG_FIELDS) requireText(`${field} tag`, result[field])
  return result
}

function requireTarget(target) {
  if (target === null || typeof target !== 'object' || Array.isArray(target)) {
    throw new Error('SSE target is required')
  }
  const expectedAudience = ENDPOINT_AUDIENCES[target.kind]
  if (expectedAudience === undefined || target.audience !== expectedAudience) {
    throw new Error('SSE target audience does not match endpoint kind')
  }
  return target
}

function requireCredentials(credentials) {
  if (credentials === null || typeof credentials !== 'object' || Array.isArray(credentials)) {
    throw new Error('synthetic SSE credentials are required')
  }
  return {
    email: requireText('synthetic SSE email', credentials.email),
    password: requireText('synthetic SSE password', credentials.password),
  }
}

function parseToken(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('SSE login token contract is invalid')
  }
  const fields = Object.keys(data).sort()
  if (JSON.stringify(fields) !== JSON.stringify(['accessToken', 'expiresIn', 'tokenType'])
    || typeof data.accessToken !== 'string'
    || data.accessToken === ''
    || data.tokenType !== 'Bearer'
    || data.expiresIn !== 900) {
    throw new Error('SSE login token contract is invalid')
  }
  return data.accessToken
}

function parseCsrf(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('SSE cleanup CSRF contract is invalid')
  }
  const fields = Object.keys(data).sort()
  if (JSON.stringify(fields) !== JSON.stringify(['headerName', 'token'])
    || data.headerName !== 'X-CSRF-TOKEN'
    || typeof data.token !== 'string'
    || data.token === '') {
    throw new Error('SSE cleanup CSRF contract is invalid')
  }
  return data.token
}

export function prepareSseSession({
  client,
  baseUrl,
  target,
  credentials,
  tags = {},
}) {
  const normalizedTarget = requireTarget(target)
  const normalizedBaseUrl = requireText('baseUrl', baseUrl).replace(/\/+$/, '')
  const authPath = AUTH_PATHS[normalizedTarget.audience]
  const account = requireCredentials(credentials)

  const loginResponse = client.post(
    `${normalizedBaseUrl}${authPath}/sessions`,
    JSON.stringify({ email: account.email, password: account.password }),
    {
      headers: jsonHeaders(),
      tags: safeTags(normalizedTarget, tags, 'preparation'),
      redirects: 0,
    },
  )
  if (loginResponse.status !== 200) throw new Error('synthetic SSE login failed')
  const accessToken = parseToken(loginResponse)

  const csrfResponse = client.get(
    `${normalizedBaseUrl}${authPath}/csrf-tokens/current`,
    {
      headers: jsonHeaders(),
      tags: safeTags(normalizedTarget, tags, 'cleanup'),
      redirects: 0,
    },
  )
  if (csrfResponse.status !== 200) throw new Error('synthetic SSE session cleanup failed')
  const csrfToken = parseCsrf(csrfResponse)

  const logoutResponse = client.del(
    `${normalizedBaseUrl}${authPath}/sessions/current`,
    null,
    {
      headers: jsonHeaders({ 'X-CSRF-TOKEN': csrfToken }),
      tags: safeTags(normalizedTarget, tags, 'cleanup'),
      redirects: 0,
    },
  )
  if (logoutResponse.status !== 200 || parseEnvelope(logoutResponse).data !== null) {
    throw new Error('synthetic SSE session cleanup failed')
  }

  return accessToken
}

function emitMetric(metrics, name, value, tags) {
  const recorder = metrics[name]
  if (typeof recorder === 'function') recorder(value, tags)
}

function streamTags(tags) {
  const result = {}
  for (const field of ['phase', 'profile', 'audience', 'endpoint_kind', 'traffic']) {
    if (typeof tags[field] === 'string' && tags[field] !== '') result[field] = tags[field]
  }
  return result
}

function validateBehavior(behavior) {
  if (behavior === null || typeof behavior !== 'object' || Array.isArray(behavior)) {
    throw new Error('SSE stream behavior is required')
  }
  if (!['smoke', 'reconnect', 'steady', 'slow-client'].includes(behavior.mode)) {
    throw new Error('SSE stream behavior mode is invalid')
  }
  if (behavior.mode === 'slow-client'
    && (typeof behavior.delay !== 'function'
      || !Number.isInteger(behavior.delaySeconds)
      || behavior.delaySeconds <= 0
      || behavior.delaySeconds > 60)) {
    throw new Error('slow-client behavior requires a bounded delay')
  }
  if (behavior.onLastEventId !== undefined && typeof behavior.onLastEventId !== 'function') {
    throw new Error('onLastEventId must be a function')
  }
  if (behavior.onFirstValidEvent !== undefined
    && typeof behavior.onFirstValidEvent !== 'function') {
    throw new Error('onFirstValidEvent must be a function')
  }
  if (behavior.minimumValidEvents !== undefined
    && (!Number.isInteger(behavior.minimumValidEvents)
      || behavior.minimumValidEvents <= 0
      || behavior.minimumValidEvents > 10)) {
    throw new Error('minimumValidEvents must be bounded')
  }
  if (behavior.requireServerClose !== undefined
    && typeof behavior.requireServerClose !== 'boolean') {
    throw new Error('requireServerClose must be boolean')
  }
  if (behavior.timeoutSeconds !== undefined
    && (!Number.isInteger(behavior.timeoutSeconds)
      || behavior.timeoutSeconds <= 0
      || behavior.timeoutSeconds > 605)) {
    throw new Error('SSE stream timeout must be bounded')
  }
  return behavior
}

function isHeartbeatComment(event) {
  return event !== null
    && typeof event === 'object'
    && !Array.isArray(event)
    && typeof event.comment === 'string'
    && event.comment !== ''
    && (event.name === '' || event.name === undefined)
    && (event.data === '' || event.data === undefined)
    && (event.id === '' || event.id === undefined)
}

function statusClassification(status) {
  if (status === 401) return 'unauthorized'
  if (status === 429) return 'capacity_rejected'
  if (status === 503) return 'unavailable'
  if (status >= 500 && status <= 599) return 'server_error'
  if (status >= 400 && status <= 499) return 'unexpected_client_error'
  if (status !== 200) return 'unexpected_status'
  return null
}

export function openChangedStream({
  transport,
  url,
  accessToken,
  lastEventId = null,
  endpointKind,
  behavior,
  metrics = {},
  tags = {},
}) {
  if (transport === null || typeof transport?.open !== 'function') {
    throw new Error('SSE transport.open is required')
  }
  const streamUrl = requireText('SSE url', url)
  const token = requireText('SSE accessToken', accessToken)
  const selectedBehavior = validateBehavior(behavior)
  if (lastEventId !== null
    && (typeof lastEventId !== 'string'
      || !/^[A-Za-z0-9_-]{1,512}$/.test(lastEventId))) {
    throw new Error('Last-Event-ID must be a bounded base64url value')
  }

  const selectedTags = streamTags(tags)
  const headers = {
    Accept: 'text/event-stream',
    Authorization: `Bearer ${token}`,
  }
  if (lastEventId !== null) headers['Last-Event-ID'] = lastEventId
  const request = { method: 'GET', headers, tags: selectedTags }
  if (selectedBehavior.timeoutSeconds !== undefined) {
    request.timeout = `${selectedBehavior.timeoutSeconds}s`
  }

  let opened = false
  let validEvents = 0
  let heartbeatFrames = 0
  let contractError = false
  let transportError = false
  let clientClosedByHarness = false
  let receivePaused = false
  const startedAt = Date.now()
  let response

  try {
    response = transport.open(
      streamUrl,
      request,
      (client) => {
        const closeClient = () => {
          clientClosedByHarness = true
          client.close()
        }
        client.on('open', () => {
          opened = true
          emitMetric(metrics, 'opened', 1, selectedTags)
        })
        client.on('event', (event) => {
          if (isHeartbeatComment(event)) {
            heartbeatFrames += 1
            emitMetric(metrics, 'heartbeatFrame', 1, selectedTags)
            return
          }
          try {
            validateChangedEvent(event, endpointKind)
          } catch (_) {
            contractError = true
            emitMetric(metrics, 'contractError', 1, selectedTags)
            closeClient()
            return
          }

          validEvents += 1
          emitMetric(metrics, 'validEvent', 1, selectedTags)
          if (validEvents === 1) {
            emitMetric(metrics, 'firstEventMilliseconds', Date.now() - startedAt, selectedTags)
            if (typeof selectedBehavior.onLastEventId === 'function') {
              selectedBehavior.onLastEventId(event.id)
            }
            if (typeof selectedBehavior.onFirstValidEvent === 'function') {
              selectedBehavior.onFirstValidEvent()
            }
          }
          if (selectedBehavior.mode === 'slow-client' && !receivePaused) {
            receivePaused = true
            selectedBehavior.delay(selectedBehavior.delaySeconds)
          }

          if ((selectedBehavior.mode === 'smoke'
            || selectedBehavior.mode === 'reconnect') && validEvents === 1) {
            closeClient()
          }
        })
        client.on('error', () => {
          transportError = true
          emitMetric(metrics, 'transportError', 1, selectedTags)
          closeClient()
        })
      },
    )
  } catch (_) {
    transportError = true
  }

  const minimumValidEvents = selectedBehavior.minimumValidEvents ?? 1
  const serverClosed = !clientClosedByHarness && !transportError && !contractError
  let classification
  if (transportError) {
    classification = 'transport_error'
  } else if (contractError) {
    classification = 'contract_error'
  } else {
    classification = statusClassification(response?.status)
      || (validEvents >= minimumValidEvents
        && (!selectedBehavior.requireServerClose || serverClosed)
        ? 'success'
        : 'missing_event')
  }
  emitMetric(metrics, 'connectionResult', classification, selectedTags)

  return Object.freeze({
    classification,
    opened,
    validEvents,
    heartbeatFrames,
    receivePaused,
    serverClosed,
    completed: classification === 'success',
  })
}
