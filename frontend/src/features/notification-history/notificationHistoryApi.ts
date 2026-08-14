import type { ApiClient } from '../../shared/api/client'
import { ApiContractError } from '../../shared/api/apiError'
import type { components } from '../../shared/api/generated/notification'

const NOTIFICATION_HISTORY_PATH = '/api/v1/consumers/me/notifications'

export type NotificationHistoryItem =
  components['schemas']['NotificationHistoryItem']
export type NotificationHistoryPage =
  components['schemas']['NotificationHistoryPageData']

type ReadNotificationHistoryPageOptions = {
  cursor?: string
  signal?: AbortSignal
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isResource(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.type === 'string' &&
    typeof value.id === 'string'
  )
}

function isAction(value: unknown): boolean {
  if (value === null) {
    return true
  }
  if (
    !isRecord(value) ||
    !isResource(value.resource) ||
    !['AVAILABLE', 'EXPIRED', 'SUPERSEDED', 'UNAVAILABLE'].includes(
      String(value.availability),
    ) ||
    !(value.expiresAt === null || typeof value.expiresAt === 'string')
  ) {
    return false
  }

  const resource = value.resource as Record<string, unknown>
  return (
    (value.type === 'RESERVATION_DETAIL' &&
      resource.type === 'RESERVATION') ||
    (value.type === 'PICKUP_RESERVATION_DETAIL' &&
      resource.type === 'PICKUP_RESERVATION') ||
    (value.type === 'MENU_SUBSTITUTION_REVIEW' &&
      resource.type === 'MENU_SUBSTITUTION_PROPOSAL')
  )
}

function isNotificationHistoryItem(
  value: unknown,
): value is NotificationHistoryItem {
  return (
    isRecord(value) &&
    typeof value.notificationId === 'string' &&
    typeof value.purpose === 'string' &&
    typeof value.title === 'string' &&
    isResource(value.resource) &&
    typeof value.occurredAt === 'string' &&
    typeof value.createdAt === 'string' &&
    typeof value.deliveredAt === 'string' &&
    !Number.isNaN(Date.parse(value.deliveredAt)) &&
    isAction(value.action)
  )
}

function isNotificationHistoryPage(
  value: unknown,
): value is NotificationHistoryPage {
  if (!isRecord(value)) {
    return false
  }

  return (
    Array.isArray(value.items) &&
    value.items.every(isNotificationHistoryItem) &&
    typeof value.hasNext === 'boolean' &&
    (typeof value.nextCursor === 'string' || value.nextCursor === null)
  )
}

export async function readNotificationHistoryPage(
  apiClient: ApiClient,
  options: ReadNotificationHistoryPageOptions = {},
): Promise<NotificationHistoryPage> {
  const response = await apiClient(NOTIFICATION_HISTORY_PATH, {
    method: 'get',
    query: { cursor: options.cursor },
    signal: options.signal,
  })

  if (!isNotificationHistoryPage(response.data)) {
    throw new ApiContractError(200, 'notificationHistoryData')
  }

  return response.data
}
