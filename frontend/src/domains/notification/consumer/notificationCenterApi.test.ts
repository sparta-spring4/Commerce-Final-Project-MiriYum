import { http, HttpResponse } from 'msw'
import { describe, expect, test } from 'vitest'

import { createApiClient } from '../../../shared/api/client'
import { server } from '../../../test/msw/server'

const UNREAD_PATH = '/api/v1/consumers/me/notifications/unread-count'
const READ_ALL_PATH = '/api/v1/consumers/me/notifications/reads'

function response(unreadCount: unknown) {
  return {
    code: 'SUCCESS',
    message: '처리했습니다.',
    data: { unreadCount },
  }
}

describe('notificationCenterApi', () => {
  test('reads the server-owned unread count', async () => {
    server.use(
      http.get(UNREAD_PATH, () => HttpResponse.json(response(12))),
    )
    const { readNotificationUnreadCount } = await import('./notificationCenterApi')

    await expect(readNotificationUnreadCount(createApiClient())).resolves.toBe(12)
  })

  test.each([-1, 1.5, '3'])('rejects malformed unread count %j', async (count) => {
    server.use(
      http.get(UNREAD_PATH, () => HttpResponse.json(response(count))),
    )
    const { readNotificationUnreadCount } = await import('./notificationCenterApi')

    await expect(readNotificationUnreadCount(createApiClient())).rejects.toMatchObject({
      name: 'ApiContractError',
      violation: 'notificationUnreadCountData',
    })
  })

  test('marks one notification read using an encoded path parameter', async () => {
    let calledUrl = ''
    server.use(
      http.post('/api/v1/consumers/me/notifications/:notificationId/reads', ({ request }) => {
        calledUrl = request.url
        return HttpResponse.json(response(2))
      }),
    )
    const { readNotification } = await import('./notificationCenterApi')

    await expect(readNotification(createApiClient(), 'notice/1')).resolves.toBe(2)
    expect(calledUrl).toContain('/notifications/notice%2F1/reads')
  })

  test('marks every public unread notification read', async () => {
    let calls = 0
    server.use(
      http.post(READ_ALL_PATH, () => {
        calls += 1
        return HttpResponse.json(response(0))
      }),
    )
    const { readAllNotifications } = await import('./notificationCenterApi')

    await expect(readAllNotifications(createApiClient())).resolves.toBe(0)
    expect(calls).toBe(1)
  })
})
