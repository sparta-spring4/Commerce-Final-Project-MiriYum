import {
  classifyStatus,
  isCalendarDate,
  parseEnvelope,
  recordClassification,
  requireOffsetDateTime,
} from '../lib/contracts.js'
import { bearerHeaders, deterministicUuid } from '../lib/session.js'

const EXPECTED_CONFLICT_CODES = new Set([
  'RESERVATION_003',
  'MENU_HOLD_001',
  'MENU_HOLD_002',
])

const RESERVATION_STATUSES = new Set(['CONFIRMED', 'CANCELLED', 'FULFILLED'])
const TIME_STATUSES = new Set(['RESOLVED', 'LEGACY_UNRESOLVED'])
const CANCELLED_BY = new Set(['CONSUMER', 'STORE_OPERATOR', null])

function requireObject(value, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  return value
}

function requireExactObject(value, keys, name) {
  const object = requireObject(value, name)
  const actual = Object.keys(object)
  if (actual.length !== keys.length || actual.some((key) => !keys.includes(key))) {
    throw new Error(`${name} must match the OpenAPI fields`)
  }
  return object
}

function requirePublicId(value, name) {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) {
    throw new Error(`${name} must be a public ID`)
  }
}

function requireBoundedString(value, name, minimum, maximum) {
  if (typeof value !== 'string' || value.length < minimum || value.length > maximum) {
    throw new Error(`${name} length is outside the OpenAPI bounds`)
  }
}

function validateParty(value) {
  const party = requireExactObject(
    value,
    ['adultCount', 'childCount', 'infantCount', 'totalCount'],
    'reservation party response',
  )
  for (const field of ['adultCount', 'childCount', 'infantCount']) {
    if (!Number.isInteger(party[field]) || party[field] < 0) {
      throw new Error(`reservation party ${field} is invalid`)
    }
  }
  const total = party.adultCount + party.childCount + party.infantCount
  if (!Number.isInteger(party.totalCount) || party.totalCount < 1 || party.totalCount !== total) {
    throw new Error('reservation party totalCount is invalid')
  }
}

function validateMenuItem(value) {
  const item = requireExactObject(
    value,
    ['menuId', 'menuName', 'unitPrice', 'quantity'],
    'reservation menu selection response',
  )
  requirePublicId(item.menuId, 'reservation menuId')
  requireBoundedString(item.menuName, 'reservation menuName', 1, 100)
  if (!Number.isSafeInteger(item.unitPrice) || item.unitPrice < 0) {
    throw new Error('reservation menu unitPrice is invalid')
  }
  if (!Number.isInteger(item.quantity) || item.quantity < 1) {
    throw new Error('reservation menu quantity is invalid')
  }
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
  const data = requireExactObject(parseEnvelope(response).data, [
    'reservationId',
    'storeId',
    'storeName',
    'serviceDate',
    'timeStatus',
    'startAt',
    'serviceEndAt',
    'timeZoneId',
    'party',
    'status',
    'menuSelections',
    'createdAt',
    'cancelledBy',
    'cancellationReason',
  ], 'reservation response data')
  requirePublicId(data.reservationId, 'reservationId')
  requirePublicId(data.storeId, 'reservation storeId')
  requireBoundedString(data.storeName, 'reservation storeName', 1, 100)
  if (!isCalendarDate(data.serviceDate)) {
    throw new Error('reservation serviceDate is invalid')
  }
  if (!TIME_STATUSES.has(data.timeStatus)) throw new Error('reservation timeStatus is invalid')
  for (const field of ['startAt', 'serviceEndAt']) {
    if (data[field] !== null) requireOffsetDateTime(data[field], `reservation ${field}`)
  }
  if (data.timeZoneId !== null) {
    requireBoundedString(data.timeZoneId, 'reservation timeZoneId', 1, 64)
  }
  validateParty(data.party)
  if (!RESERVATION_STATUSES.has(data.status)) throw new Error('reservation status is invalid')
  if (!Array.isArray(data.menuSelections)) throw new Error('reservation menuSelections must be an array')
  data.menuSelections.forEach(validateMenuItem)
  requireOffsetDateTime(data.createdAt, 'reservation createdAt')
  if (!CANCELLED_BY.has(data.cancelledBy)) throw new Error('reservation cancelledBy is invalid')
  if (data.cancellationReason !== null) {
    requireBoundedString(data.cancellationReason, 'reservation cancellationReason', 0, 500)
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
