import { http, HttpResponse } from 'msw'
import { describe, expect, test } from 'vitest'

import { createApiClient } from '../../shared/api/client'
import { server } from '../../test/msw/server'

const NOTIFICATION_HISTORY_PATH = '/api/v1/consumers/me/notifications'

function emptyHistoryResponse() {
  return {
    code: 'SUCCESS',
    message: '알림 이력을 조회했습니다.',
    data: {
      items: [],
      hasNext: false,
      nextCursor: null,
    },
  } as const
}

describe('readNotificationHistoryPage', () => {
  test('omits cursor for the newest page request', async () => {
    let receivedCursor: string | null = 'not-requested'
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, ({ request }) => {
        receivedCursor = new URL(request.url).searchParams.get('cursor')
        return HttpResponse.json(emptyHistoryResponse())
      }),
    )

    const { readNotificationHistoryPage } = await import(
      './notificationHistoryApi'
    )
    const page = await readNotificationHistoryPage(createApiClient())

    expect(receivedCursor).toBeNull()
    expect(page).toEqual({ items: [], hasNext: false, nextCursor: null })
  })

  test('passes the server cursor without decoding or rewriting it', async () => {
    const opaqueCursor = 'v1_AbC-123_XyZ'
    let receivedCursor: string | null = null
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, ({ request }) => {
        receivedCursor = new URL(request.url).searchParams.get('cursor')
        return HttpResponse.json(emptyHistoryResponse())
      }),
    )

    const { readNotificationHistoryPage } = await import(
      './notificationHistoryApi'
    )
    await readNotificationHistoryPage(createApiClient(), {
      cursor: opaqueCursor,
    })

    expect(receivedCursor).toBe(opaqueCursor)
  })
})
