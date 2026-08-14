import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'
import { bearerHeaders, deterministicUuid } from '../lib/session.js'

const EXPECTED_CONFLICT_CODES = new Set([
  'RESERVATION_003',
  'RESERVATION_004',
  'MENU_HOLD_001',
  'MENU_HOLD_002',
])

function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

function buildReservationBody(template) {
  requireObject(template, 'reservation template')
  requireObject(template.party, 'reservation party')
  const body = {
    storeId: template.storeId,
    serviceDate: template.serviceDate,
    startTime: template.startTime,
  }
  if (template.startOffset !== undefined && template.startOffset !== null) {
    body.startOffset = template.startOffset
  }
  body.party = {
    adultCount: template.party.adultCount,
    childCount: template.party.childCount,
    infantCount: template.party.infantCount,
  }
  const menuSelections = template.menuSelections === undefined ? [] : template.menuSelections
  if (!Array.isArray(menuSelections)) {
    throw new Error('reservation menuSelections must be an array')
  }
  body.menuSelections = menuSelections.map((selection) => ({
    menuId: selection.menuId,
    quantity: selection.quantity,
  }))
  return body
}

function errorCode(response) {
  try {
    const parsed = JSON.parse(response.body)
    return typeof parsed.code === 'string' ? parsed.code : null
  } catch (_) {
    return null
  }
}

function classifyReservationResponse(response) {
  if (response.status === 409 && EXPECTED_CONFLICT_CODES.has(errorCode(response))) {
    return 'expected_4xx'
  }
  return classifyStatus(response.status, [])
}

function validateReservationSuccess(response) {
  const data = requireObject(parseEnvelope(response).data, 'reservation response data')
  if (typeof data.reservationId !== 'string' || typeof data.storeId !== 'string') {
    throw new Error('reservation response must contain public identifiers')
  }
  if (typeof data.status !== 'string' || data.status === '') {
    throw new Error('reservation response must contain status')
  }
}

export function runReservationCreate({
  client,
  baseUrl,
  accessToken,
  template,
  runId,
  vu,
  iteration,
  tags = {},
}) {
  const requestTagSet = { phase: 'measured', ...tags, request: 'reservationCreate' }
  const idempotencyKey = deterministicUuid(`${runId}:${vu}:${iteration}:reservation`)
  const response = client.post(
    `${baseUrl}/api/v1/consumers/me/reservations`,
    JSON.stringify(buildReservationBody(template)),
    {
      headers: bearerHeaders(accessToken, { 'Idempotency-Key': idempotencyKey }),
      tags: requestTagSet,
      redirects: 0,
    },
  )
  const classification = recordClassification(
    classifyReservationResponse(response),
    requestTagSet,
  )
  if (response.status === 201) {
    validateReservationSuccess(response)
  } else if (classification === 'success') {
    throw new Error(`reservation creation returned unsupported success HTTP ${response.status}`)
  }

  return { status: response.status, classification }
}
