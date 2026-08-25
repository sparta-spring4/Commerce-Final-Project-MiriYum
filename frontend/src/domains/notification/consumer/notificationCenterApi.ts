import type { ApiClient } from '../../../shared/api/client'
import { ApiContractError } from '../../../shared/api/apiError'

const UNREAD_COUNT_PATH = '/api/v1/consumers/me/notifications/unread-count'
const READ_NOTIFICATION_PATH = '/api/v1/consumers/me/notifications/{notificationId}/reads'
const READ_ALL_NOTIFICATIONS_PATH = '/api/v1/consumers/me/notifications/reads'

function parseUnreadCount(data: unknown): number {
  if (
    typeof data !== 'object' ||
    data === null ||
    !('unreadCount' in data) ||
    !Number.isSafeInteger(data.unreadCount) ||
    (data.unreadCount as number) < 0
  ) {
    throw new ApiContractError(200, 'notificationUnreadCountData')
  }
  return data.unreadCount as number
}

export async function readNotificationUnreadCount(
  apiClient: ApiClient,
  signal?: AbortSignal,
): Promise<number> {
  const response = await apiClient(UNREAD_COUNT_PATH, {
    method: 'get',
    signal,
  })
  return parseUnreadCount(response.data)
}

export async function readNotification(
  apiClient: ApiClient,
  notificationId: string,
): Promise<number> {
  const response = await apiClient(READ_NOTIFICATION_PATH, {
    method: 'post',
    pathParams: { notificationId },
  })
  return parseUnreadCount(response.data)
}

export async function readAllNotifications(apiClient: ApiClient): Promise<number> {
  const response = await apiClient(READ_ALL_NOTIFICATIONS_PATH, {
    method: 'post',
  })
  return parseUnreadCount(response.data)
}
