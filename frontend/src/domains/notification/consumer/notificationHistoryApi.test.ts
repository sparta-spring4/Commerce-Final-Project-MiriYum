import { http, HttpResponse } from 'msw'
import { describe, expect, test } from 'vitest'

import { createApiClient } from '../../../shared/api/client'
import { server } from '../../../test/msw/server'

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

  test('rejects a success envelope with malformed history data', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '알림 이력을 조회했습니다.',
          data: { hasNext: false, nextCursor: null },
        }),
      ),
    )

    const { readNotificationHistoryPage } = await import(
      './notificationHistoryApi'
    )

    await expect(
      readNotificationHistoryPage(createApiClient()),
    ).rejects.toMatchObject({
      name: 'ApiContractError',
      violation: 'notificationHistoryData',
    })
  })

  test('rejects a history item without its required delivery time', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '알림 이력을 조회했습니다.',
          data: {
            items: [
              {
                notificationId: '1001',
                purpose: 'RESERVATION_CONFIRMED',
                title: '예약이 확정되었습니다.',
                resource: { type: 'RESERVATION', id: '501' },
                occurredAt: '2026-08-13T10:00:00+09:00',
                createdAt: '2026-08-13T10:00:01+09:00',
                action: null,
              },
            ],
            hasNext: false,
            nextCursor: null,
          },
        }),
      ),
    )

    const { readNotificationHistoryPage } = await import(
      './notificationHistoryApi'
    )

    await expect(
      readNotificationHistoryPage(createApiClient()),
    ).rejects.toMatchObject({
      name: 'ApiContractError',
      violation: 'notificationHistoryData',
    })
  })

  test('keeps the page when one history item has an unknown action object', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '알림 이력을 조회했습니다.',
          data: {
            items: [
              {
                notificationId: '1001',
                purpose: 'RESERVATION_CONFIRMED',
                title: '예약이 확정되었습니다.',
                resource: { type: 'RESERVATION', id: '501' },
                occurredAt: '2026-08-13T10:00:00+09:00',
                createdAt: '2026-08-13T10:00:01+09:00',
                deliveredAt: '2026-08-13T10:00:02+09:00',
                action: {
                  type: 'WAITING_DETAIL',
                  resource: { type: 'WAITING', id: '701' },
                  availability: 'AVAILABLE',
                  expiresAt: null,
                },
              },
              {
                notificationId: '1000',
                purpose: 'RESERVATION_CANCELLED',
                title: '예약이 취소되었습니다.',
                resource: { type: 'RESERVATION', id: '500' },
                occurredAt: '2026-08-13T09:00:00+09:00',
                createdAt: '2026-08-13T09:00:01+09:00',
                deliveredAt: '2026-08-13T09:00:02+09:00',
                action: null,
              },
            ],
            hasNext: false,
            nextCursor: null,
          },
        }),
      ),
    )

    const { readNotificationHistoryPage } = await import(
      './notificationHistoryApi'
    )

    const page = await readNotificationHistoryPage(createApiClient())

    expect(page.items.map((item) => item.title)).toEqual([
      '예약이 확정되었습니다.',
      '예약이 취소되었습니다.',
    ])
  })
})
