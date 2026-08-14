import type { ApiClient } from '../../shared/api/client'
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

export async function readNotificationHistoryPage(
  apiClient: ApiClient,
  options: ReadNotificationHistoryPageOptions = {},
): Promise<NotificationHistoryPage> {
  const response = await apiClient(NOTIFICATION_HISTORY_PATH, {
    method: 'get',
    query: { cursor: options.cursor },
    signal: options.signal,
  })

  return response.data
}
