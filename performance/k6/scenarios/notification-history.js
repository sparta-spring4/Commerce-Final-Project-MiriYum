import { classifyStatus, parseEnvelope, recordClassification } from '../lib/contracts.js'
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

function validatePage(response) {
  const data = parseEnvelope(response).data
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('notification history data must be an object')
  }
  if (!Array.isArray(data.items) || typeof data.hasNext !== 'boolean') {
    throw new Error('notification history page contract is invalid')
  }
  if (data.hasNext && (typeof data.nextCursor !== 'string' || data.nextCursor === '')) {
    throw new Error('notification history next page requires a cursor')
  }
  if (!data.hasNext && data.nextCursor !== null) {
    throw new Error('notification history final page cursor must be null')
  }
  for (const item of data.items) {
    if (item === null || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error('notification history item must be an object')
    }
    for (const field of ITEM_FIELDS) {
      if (!Object.prototype.hasOwnProperty.call(item, field)) {
        throw new Error(`notification history item must contain ${field}`)
      }
    }
  }
  return data
}

function getPage({ client, url, accessToken, tags, request }) {
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
    page: classification === 'success' ? validatePage(response) : null,
  }
}

export function runNotificationHistory({ client, baseUrl, accessToken, pageSize, tags = {} }) {
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) {
    throw new Error('notification history pageSize must be between 1 and 50')
  }
  const first = getPage({
    client,
    url: `${baseUrl}/api/v1/consumers/me/notifications?size=${pageSize}`,
    accessToken,
    tags,
    request: 'notificationHistoryFirstPage',
  })
  if (first.page === null || !first.page.hasNext) {
    return {
      firstStatus: first.response.status,
      nextStatus: null,
      itemCount: first.page === null ? 0 : first.page.items.length,
      classification: first.classification,
    }
  }

  const next = getPage({
    client,
    url: `${baseUrl}/api/v1/consumers/me/notifications?size=${pageSize}&cursor=${encodeURIComponent(first.page.nextCursor)}`,
    accessToken,
    tags,
    request: 'notificationHistoryNextPage',
  })
  return {
    firstStatus: first.response.status,
    nextStatus: next.response.status,
    itemCount: first.page.items.length + (next.page === null ? 0 : next.page.items.length),
    classification: next.classification,
  }
}
