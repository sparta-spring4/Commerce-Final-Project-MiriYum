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

function isNotificationHistoryPage(
  value: unknown,
): value is NotificationHistoryPage {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }

  const page = value as Record<string, unknown>
  return (
    Array.isArray(page.items) &&
    typeof page.hasNext === 'boolean' &&
    (typeof page.nextCursor === 'string' || page.nextCursor === null)
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
