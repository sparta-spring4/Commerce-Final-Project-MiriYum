import {
  classifyStatus,
  parseEnvelope,
  recordClassification,
  requireOffsetDateTime,
} from '../lib/contracts.js'
import { bearerHeaders } from '../lib/session.js'

const ITEM_FIELDS = [
  'notificationId',
  'purpose',
  'title',
  'resource',
  'occurredAt',
  'createdAt',
  'deliveredAt',
  'action',
]

const PURPOSES = new Set([
  'RESERVATION_CONFIRMED',
  'RESERVATION_CHANGED',
  'RESERVATION_REJECTED',
  'RESERVATION_CANCELLED',
  'RESERVATION_EXPIRED',
  'RESERVATION_VISIT_REMINDER',
  'RESERVATION_COORDINATION_REQUIRED',
  'RESERVATION_VISIT_COMPLETED',
  'RESERVATION_NO_SHOW',
  'PICKUP_RESERVATION_CONFIRMED',
  'PICKUP_RESERVATION_CANCELLED',
  'MENU_HOLD_FULFILLMENT_AT_RISK',
  'MENU_SUBSTITUTION_PROPOSED',
  'MENU_SUBSTITUTION_ACCEPTED',
  'MENU_SUBSTITUTION_REJECTED',
  'MENU_SUBSTITUTION_EXPIRED',
  'WAITING_ENTRY_IMMINENT',
  'WAITING_CALLED',
  'WAITING_CANCELLED',
  'WAITING_NO_SHOW',
  'WAITING_CHECKED_IN',
  'WAITING_CLOSED_BY_STORE',
])
const RESERVATION_TERMINAL_PURPOSES = new Set([
  'RESERVATION_VISIT_COMPLETED',
  'RESERVATION_NO_SHOW',
])
const RESOURCE_TYPES = new Set([
  'RESERVATION',
  'MENU_HOLD',
  'PICKUP_RESERVATION',
  'MENU_SUBSTITUTION_PROPOSAL',
  'WAITING_TEAM',
])
const ACTION_RESOURCE_TYPES = Object.freeze({
  RESERVATION_DETAIL: 'RESERVATION',
  PICKUP_RESERVATION_DETAIL: 'PICKUP_RESERVATION',
  MENU_SUBSTITUTION_REVIEW: 'MENU_SUBSTITUTION_PROPOSAL',
})
const ACTION_AVAILABILITY = new Set(['AVAILABLE', 'EXPIRED', 'SUPERSEDED', 'UNAVAILABLE'])

function requireExactObject(value, fields, name) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${name} must be an object`)
  }
  const keys = Object.keys(value)
  if (keys.length !== fields.length || keys.some((key) => !fields.includes(key))) {
    throw new Error(`${name} fields do not match the OpenAPI contract`)
  }
}

function requirePublicId(value, name) {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) {
    throw new Error(`${name} must be a public ID`)
  }
}

function validateResource(resource, expectedType, name) {
  requireExactObject(resource, ['type', 'id'], name)
  if (!RESOURCE_TYPES.has(resource.type)
      || (expectedType !== undefined && resource.type !== expectedType)) {
    throw new Error(`${name} type is invalid`)
  }
  requirePublicId(resource.id, `${name} id`)
}

function validateAction(action) {
  if (action === null) return
  requireExactObject(action, ['type', 'resource', 'availability', 'expiresAt'], 'notification action')
  const expectedResourceType = ACTION_RESOURCE_TYPES[action.type]
  if (expectedResourceType === undefined) {
    throw new Error('notification action type is invalid')
  }
  validateResource(action.resource, expectedResourceType, 'notification action resource')
  if (!ACTION_AVAILABILITY.has(action.availability)) {
    throw new Error('notification action availability is invalid')
  }
  if (action.expiresAt !== null) {
    requireOffsetDateTime(action.expiresAt, 'notification action expiresAt')
  }
}

function validateItem(item) {
  requireExactObject(item, ITEM_FIELDS, 'notification history item')
  requirePublicId(item.notificationId, 'notification history notificationId')
  if (!PURPOSES.has(item.purpose)) throw new Error('notification history purpose is invalid')
  if (typeof item.title !== 'string'
      || Array.from(item.title).length < 1
      || Array.from(item.title).length > 100) {
    throw new Error('notification history title must contain 1 to 100 characters')
  }
  validateResource(item.resource, undefined, 'notification history resource')
  requireOffsetDateTime(item.occurredAt, 'notification history occurredAt')
  requireOffsetDateTime(item.createdAt, 'notification history createdAt')
  requireOffsetDateTime(item.deliveredAt, 'notification history deliveredAt')
  if (RESERVATION_TERMINAL_PURPOSES.has(item.purpose) && item.action !== null) {
    throw new Error('reservation terminal notification action must be null')
  }
  validateAction(item.action)
}

function validatePage(response, pageSize) {
  const envelope = parseEnvelope(response)
  if (envelope.code !== 'SUCCESS'
      || typeof envelope.message !== 'string'
      || envelope.message.length < 1) {
    throw new Error('notification history success envelope is invalid')
  }
  const data = envelope.data
  requireExactObject(data, ['items', 'hasNext', 'nextCursor'], 'notification history data')
  if (!Array.isArray(data.items) || typeof data.hasNext !== 'boolean') {
    throw new Error('notification history page contract is invalid')
  }
  if (data.items.length > pageSize || data.items.length > 50) {
    throw new Error('notification history page exceeds the requested size')
  }
  if (data.hasNext
      && (typeof data.nextCursor !== 'string'
        || data.nextCursor.length < 1
        || data.nextCursor.length > 512
        || !/^[A-Za-z0-9_-]+$/.test(data.nextCursor))) {
    throw new Error('notification history next page requires a cursor')
  }
  if (!data.hasNext && data.nextCursor !== null) {
    throw new Error('notification history final page cursor must be null')
  }
  data.items.forEach(validateItem)
  return data
}

function getPage({ client, url, accessToken, pageSize, tags, request }) {
  const requestTagSet = { phase: 'measured', ...tags, request }
  const response = client.get(url, {
    headers: bearerHeaders(accessToken),
    tags: requestTagSet,
    redirects: 0,
  })
  const classification = recordClassification(
    classifyStatus(response.status, []),
    requestTagSet,
  )
  return {
    response,
    classification,
    page: classification === 'success' ? validatePage(response, pageSize) : null,
  }
}

export function runNotificationHistory({
  client,
  baseUrl,
  accessToken,
  pageSize,
  minimumDeliveredItemsPerAccount,
  tags = {},
}) {
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) {
    throw new Error('notification history pageSize must be between 1 and 50')
  }
  if (!Number.isInteger(minimumDeliveredItemsPerAccount)
      || minimumDeliveredItemsPerAccount <= pageSize) {
    throw new Error('notification history fixture must promise at least two pages')
  }
  const first = getPage({
    client,
    url: `${baseUrl}/api/v1/consumers/me/notifications?size=${pageSize}`,
    accessToken,
    pageSize,
    tags,
    request: 'notificationHistoryFirstPage',
  })
  if (first.page === null) {
    return {
      firstStatus: first.response.status,
      nextStatus: null,
      itemCount: 0,
      classification: first.classification,
    }
  }
  if (!first.page.hasNext) {
    throw new Error('notification history fixture did not provide the promised second page')
  }

  const next = getPage({
    client,
    url: `${baseUrl}/api/v1/consumers/me/notifications?size=${pageSize}&cursor=${first.page.nextCursor}`,
    accessToken,
    pageSize,
    tags,
    request: 'notificationHistoryNextPage',
  })
  const itemCount = first.page.items.length + (next.page === null ? 0 : next.page.items.length)
  if (next.page !== null && next.page.items.length === 0) {
    throw new Error('notification history second page must contain a delivered item')
  }
  if (next.page !== null
      && itemCount < Math.min(minimumDeliveredItemsPerAccount, pageSize + 1)) {
    throw new Error('notification history fixture contains fewer delivered items than promised')
  }
  return {
    firstStatus: first.response.status,
    nextStatus: next.response.status,
    itemCount,
    classification: next.classification,
  }
}
