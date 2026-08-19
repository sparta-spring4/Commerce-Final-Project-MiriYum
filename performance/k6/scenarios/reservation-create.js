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

const RESERVATION_STATUSES = new Set(['CONFIRMED', 'CANCELLED', 'FULFILLED', 'NO_SHOW'])
const TIME_STATUSES = new Set(['RESOLVED', 'LEGACY_UNRESOLVED'])
const CANCELLED_BY = new Set(['CONSUMER', 'STORE_OPERATOR', null])
const DEPOSIT_RESPONSIBILITIES = new Set([
  'CONSUMER',
  'STORE_RESPONSIBLE',
  'PLATFORM_RESPONSIBLE',
])
const DEPOSIT_REFUND_RATES = new Set([0, 5000, 10000])
const DEPOSIT_STATUSES = new Set([
  'PENDING',
  'PROCESSING',
  'COMPLETED',
  'RECONCILIATION_REQUIRED',
  'RECOVERY_REQUIRED',
])
const PAYMENT_DISPOSITION_STATUSES = new Set([
  null,
  'PROCESSING',
  'COMPLETED',
  'FAILED',
  'RECONCILIATION_REQUIRED',
])
const FAILURE_CLASSIFICATIONS = new Set([null, 'RETRYABLE', 'PERMANENT', 'UNKNOWN'])

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

function requireNullableSafeInteger(value, name, minimum) {
  if (value !== null && (!Number.isSafeInteger(value) || value < minimum)) {
    throw new Error(`${name} is invalid`)
  }
}

function validateDepositDisposition(value) {
  if (value === null) return
  const disposition = requireExactObject(value, [
    'policyVersion',
    'responsibilityCode',
    'targetRefundRateBasisPoints',
    'originalAmountMinor',
    'targetRefundAmountMinor',
    'completedRefundAmountMinor',
    'withheldAmountMinor',
    'currency',
    'dispositionId',
    'refundId',
    'status',
    'paymentDispositionStatus',
    'failureClassification',
    'createdAt',
    'updatedAt',
    'completedAt',
    'paymentRequestedAt',
    'paymentUpdatedAt',
  ], 'reservation depositDisposition')
  if (disposition.policyVersion !== 2) {
    throw new Error('reservation depositDisposition policyVersion is invalid')
  }
  if (!DEPOSIT_RESPONSIBILITIES.has(disposition.responsibilityCode)) {
    throw new Error('reservation depositDisposition responsibilityCode is invalid')
  }
  if (!DEPOSIT_REFUND_RATES.has(disposition.targetRefundRateBasisPoints)) {
    throw new Error('reservation depositDisposition targetRefundRateBasisPoints is invalid')
  }
  requireNullableSafeInteger(
    disposition.originalAmountMinor,
    'reservation depositDisposition originalAmountMinor',
    1,
  )
  for (const field of [
    'targetRefundAmountMinor',
    'completedRefundAmountMinor',
    'withheldAmountMinor',
  ]) {
    requireNullableSafeInteger(
      disposition[field],
      `reservation depositDisposition ${field}`,
      0,
    )
  }
  if (disposition.currency !== null
      && (typeof disposition.currency !== 'string'
        || !/^[A-Z]{3}$/.test(disposition.currency))) {
    throw new Error('reservation depositDisposition currency is invalid')
  }
  if (disposition.dispositionId !== null
      && (typeof disposition.dispositionId !== 'string'
        || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          disposition.dispositionId,
        ))) {
    throw new Error('reservation depositDisposition dispositionId is invalid')
  }
  if (disposition.refundId !== null) {
    requirePublicId(disposition.refundId, 'reservation depositDisposition refundId')
  }
  if (!DEPOSIT_STATUSES.has(disposition.status)) {
    throw new Error('reservation depositDisposition status is invalid')
  }
  if (!PAYMENT_DISPOSITION_STATUSES.has(disposition.paymentDispositionStatus)) {
    throw new Error('reservation depositDisposition paymentDispositionStatus is invalid')
  }
  if (!FAILURE_CLASSIFICATIONS.has(disposition.failureClassification)) {
    throw new Error('reservation depositDisposition failureClassification is invalid')
  }
  requireOffsetDateTime(disposition.createdAt, 'reservation depositDisposition createdAt')
  requireOffsetDateTime(disposition.updatedAt, 'reservation depositDisposition updatedAt')
  for (const field of ['completedAt', 'paymentRequestedAt', 'paymentUpdatedAt']) {
    if (disposition[field] !== null) {
      requireOffsetDateTime(
        disposition[field],
        `reservation depositDisposition ${field}`,
      )
    }
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
    'depositDisposition',
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
  validateDepositDisposition(data.depositDisposition)
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
