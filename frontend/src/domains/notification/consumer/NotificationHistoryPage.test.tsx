import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, test, vi } from 'vitest'

import { createApiClient } from '../../../shared/api/client'
import { server } from '../../../test/msw/server'
import type {
  NotificationEventConnectionState,
  NotificationEventStreamClient,
} from './notificationEventStream'

const NOTIFICATION_HISTORY_PATH = '/api/v1/consumers/me/notifications'

afterEach(() => {
  vi.unstubAllEnvs()
})

function historyItem(overrides: Record<string, unknown> = {}) {
  return {
    notificationId: '1001',
    purpose: 'RESERVATION_CONFIRMED',
    title: '예약이 확정되었습니다.',
    resource: { type: 'RESERVATION', id: '501' },
    occurredAt: '2026-08-13T10:00:00+09:00',
    createdAt: '2026-08-13T10:00:01+09:00',
    deliveredAt: '2026-08-13T10:00:02+09:00',
    action: null,
    ...overrides,
  }
}

function successResponse(
  items: unknown[],
  options: { hasNext?: boolean; nextCursor?: string | null } = {},
) {
  return {
    code: 'SUCCESS',
    message: '알림 이력을 조회했습니다.',
    data: {
      items,
      hasNext: options.hasNext ?? false,
      nextCursor: options.nextCursor ?? null,
    },
  }
}

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
}

function eventStreamHarness() {
  const subscriptions: Array<
    Parameters<NotificationEventStreamClient['subscribe']>[0]
  > = []
  const client: NotificationEventStreamClient = {
    subscribe: async (subscription) => {
      subscriptions.push(subscription)
      if (subscription.signal.aborted) {
        return
      }
      await new Promise<void>((resolve) => {
        subscription.signal.addEventListener('abort', () => resolve(), {
          once: true,
        })
      })
    },
  }

  return {
    client,
    subscriptions,
    emitChanged() {
      subscriptions.at(-1)?.onChanged()
    },
    emitState(state: NotificationEventConnectionState) {
      subscriptions.at(-1)?.onConnectionStateChange(state)
    },
  }
}

async function renderPage(
  queryClient = createTestQueryClient(),
  sessionKey = 0,
  eventStream = eventStreamHarness().client,
) {
  const { NotificationHistoryPage } = await import('./NotificationHistoryPage')
  const apiClient = createApiClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }

  const rendered = render(
    <NotificationHistoryPage
      apiClient={apiClient}
      eventStream={eventStream}
      sessionKey={sessionKey}
    />,
    { wrapper: Wrapper },
  )
  return {
    ...rendered,
    rerenderSession(nextSessionKey: number) {
      rendered.rerender(
        <NotificationHistoryPage
          apiClient={apiClient}
          eventStream={eventStream}
          sessionKey={nextSessionKey}
        />,
      )
    },
  }
}

describe('NotificationHistoryPage', () => {
  test('refetches the current MySQL-backed history after a changed signal', async () => {
    let title = '변경 전 알림'
    let requests = 0
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () => {
        requests += 1
        return HttpResponse.json(successResponse([historyItem({ title })]))
      }),
    )
    const events = eventStreamHarness()
    const queryClient = createTestQueryClient()
    queryClient.setQueryData(
      ['consumer', 'notification-history', 8],
      '다른 세션 캐시',
    )

    await renderPage(queryClient, 7, events.client)
    expect(await screen.findByText('변경 전 알림')).toBeVisible()

    title = 'MySQL에서 다시 읽은 알림'
    events.emitChanged()

    expect(await screen.findByText('MySQL에서 다시 읽은 알림')).toBeVisible()
    expect(requests).toBe(2)
    expect(
      queryClient.getQueryState(['consumer', 'notification-history', 8])
        ?.isInvalidated,
    ).toBe(false)
  })

  test('announces that realtime updates are recovering', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([])),
      ),
    )
    const events = eventStreamHarness()

    await renderPage(createTestQueryClient(), 0, events.client)
    await screen.findByText('아직 받은 알림이 없습니다.')
    events.emitState('reconnecting')

    expect(
      await screen.findByRole('status', {
        name: '실시간 알림 연결을 복구하는 중입니다.',
      }),
    ).toBeVisible()
  })

  test('announces when realtime updates are unavailable', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([])),
      ),
    )
    const events = eventStreamHarness()

    await renderPage(createTestQueryClient(), 0, events.client)
    await screen.findByText('아직 받은 알림이 없습니다.')
    events.emitState('unavailable')

    expect(
      await screen.findByText('실시간 알림 연결을 사용할 수 없습니다.'),
    ).toHaveAttribute('role', 'status')
  })

  test('aborts the previous stream before subscribing for a new session', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([])),
      ),
    )
    const events = eventStreamHarness()
    const page = await renderPage(createTestQueryClient(), 1, events.client)
    await waitFor(() => expect(events.subscriptions).toHaveLength(1))
    const previousSignal = events.subscriptions[0]?.signal

    page.rerenderSession(2)

    await waitFor(() => expect(events.subscriptions).toHaveLength(2))
    expect(previousSignal?.aborted).toBe(true)
    expect(events.subscriptions[1]?.signal.aborted).toBe(false)
  })

  test('links available reservation and pickup actions to their protected details', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          successResponse([
            historyItem({
              notificationId: '1001',
              action: {
                type: 'RESERVATION_DETAIL',
                resource: { type: 'RESERVATION', id: '501' },
                availability: 'AVAILABLE',
                expiresAt: null,
              },
            }),
            historyItem({
              notificationId: '1002',
              purpose: 'PICKUP_RESERVATION_CONFIRMED',
              title: '픽업 예약이 확정되었습니다.',
              resource: { type: 'PICKUP_RESERVATION', id: 'pickup/701' },
              action: {
                type: 'PICKUP_RESERVATION_DETAIL',
                resource: {
                  type: 'PICKUP_RESERVATION',
                  id: 'pickup/701',
                },
                availability: 'AVAILABLE',
                expiresAt: null,
              },
            }),
          ]),
        ),
      ),
    )

    await renderPage()

    expect(screen.getByRole('heading', { name: '알림 이력' })).toBeVisible()
    expect(await screen.findByText('예약이 확정되었습니다.')).toBeVisible()
    expect(screen.getByText('예약 확정')).toBeVisible()
    const deliveredTime = document.querySelector('time')
    expect(deliveredTime).toHaveAttribute(
      'dateTime',
      '2026-08-13T10:00:02+09:00',
    )
    expect(screen.getByRole('link', { name: '예약 상세 보기' })).toHaveAttribute(
      'href',
      '/reservations/501',
    )
    expect(
      screen.getByRole('link', { name: '픽업 상세 보기' }),
    ).toHaveAttribute('href', '/pickup-reservations/pickup%2F701')
  })

  test.each(['EXPIRED', 'SUPERSEDED', 'UNAVAILABLE'])(
    'disables a %s detail action instead of navigating',
    async (availability) => {
      server.use(
        http.get(NOTIFICATION_HISTORY_PATH, () =>
          HttpResponse.json(
            successResponse([
              historyItem({
                action: {
                  type: 'RESERVATION_DETAIL',
                  resource: { type: 'RESERVATION', id: '501' },
                  availability,
                  expiresAt: null,
                },
              }),
            ]),
          ),
        ),
      )

      await renderPage()

      expect(
        await screen.findByRole('button', { name: '예약 상세 보기' }),
      ).toBeDisabled()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    },
  )

  test.each([
    {
      name: 'menu substitution review',
      action: {
        type: 'MENU_SUBSTITUTION_REVIEW',
        resource: { type: 'MENU_SUBSTITUTION_PROPOSAL', id: '801' },
        availability: 'AVAILABLE',
        expiresAt: null,
      },
    },
    {
      name: 'crossed action and resource types',
      action: {
        type: 'RESERVATION_DETAIL',
        resource: { type: 'PICKUP_RESERVATION', id: '701' },
        availability: 'AVAILABLE',
        expiresAt: null,
      },
    },
    {
      name: 'unknown action type',
      action: {
        type: 'UNKNOWN_DETAIL',
        resource: { type: 'RESERVATION', id: '501' },
        availability: 'AVAILABLE',
        expiresAt: null,
      },
    },
    {
      name: 'blank resource id',
      action: {
        type: 'RESERVATION_DETAIL',
        resource: { type: 'RESERVATION', id: '   ' },
        availability: 'AVAILABLE',
        expiresAt: null,
      },
    },
  ])('does not execute $name', async ({ action }) => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([historyItem({ action })])),
      ),
    )

    await renderPage()

    expect(await screen.findByText('예약이 확정되었습니다.')).toBeVisible()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  test('renders the delivery time in Asia/Seoul on a UTC device', async () => {
    vi.stubEnv('TZ', 'UTC')
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          successResponse([
            historyItem({ deliveredAt: '2026-08-13T01:00:02Z' }),
          ]),
        ),
      ),
    )

    await renderPage()
    await screen.findByText('예약이 확정되었습니다.')

    const deliveredTime = document.querySelector('time')
    expect(deliveredTime).toHaveTextContent('2026. 8. 13.')
    expect(deliveredTime).toHaveTextContent('10:00')
  })

  test('renders an empty result as a normal state', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([])),
      ),
    )

    await renderPage()

    expect(await screen.findByText('아직 받은 알림이 없습니다.')).toBeVisible()
  })

  test('loads the next page with the opaque cursor unchanged', async () => {
    const opaqueCursor = 'v1_AbC-123_XyZ'
    const receivedCursors: Array<string | null> = []
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        receivedCursors.push(cursor)
        return HttpResponse.json(
          cursor === null
            ? successResponse([historyItem()], {
                hasNext: true,
                nextCursor: opaqueCursor,
              })
            : successResponse([
                historyItem({
                  notificationId: '1000',
                  purpose: 'RESERVATION_CANCELLED',
                  title: '예약이 취소되었습니다.',
                }),
              ]),
        )
      }),
    )

    await renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(await screen.findByText('예약이 취소되었습니다.')).toBeVisible()
    expect(receivedCursors).toEqual([null, opaqueCursor])
    expect(
      screen.queryByRole('button', { name: '더 보기' }),
    ).not.toBeInTheDocument()
  })

  test('resets an invalid cursor to the newest history', async () => {
    const invalidCursor = 'invalid_cursor'
    const receivedCursors: Array<string | null> = []
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor')
        receivedCursors.push(cursor)
        if (cursor === invalidCursor) {
          return HttpResponse.json(
            { code: 'NOTIFICATION_001', message: '커서가 올바르지 않습니다.' },
            { status: 400 },
          )
        }
        if (receivedCursors.length === 1) {
          return HttpResponse.json(
            successResponse([historyItem()], {
              hasNext: true,
              nextCursor: invalidCursor,
            }),
          )
        }
        return HttpResponse.json(successResponse([]))
      }),
    )

    await renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }))
    fireEvent.click(
      await screen.findByRole('button', { name: '최신 알림부터 다시 보기' }),
    )

    expect(await screen.findByText('아직 받은 알림이 없습니다.')).toBeVisible()
    expect(receivedCursors).toEqual([null, invalidCursor, null])
  })

  test.each([
    [401, 'AUTH_001', '로그인이 필요합니다.'],
    [403, 'AUTH_011', '현재 계정으로 알림 이력을 볼 수 없습니다.'],
    [503, 'COMMON_012', '알림 이력을 잠시 불러올 수 없습니다.'],
  ])('renders the %i error without exposing the server message', async (
    status,
    code,
    expectedMessage,
  ) => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          { code, message: '서버 내부 상세 메시지' },
          { status },
        ),
      ),
    )

    await renderPage()

    expect(await screen.findByText(expectedMessage)).toBeVisible()
    expect(screen.queryByText('서버 내부 상세 메시지')).not.toBeInTheDocument()
  })

  test('offers the existing consumer sign-in route after a 401', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          { code: 'AUTH_001', message: '인증이 필요합니다.' },
          { status: 401 },
        ),
      ),
    )

    await renderPage()

    expect(
      await screen.findByRole('link', { name: '로그인하기' }),
    ).toHaveAttribute('href', '/sign-in')
  })

  test('offers recheck while the notification service recovers', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          { code: 'COMMON_012', message: '일시적으로 사용할 수 없습니다.' },
          { status: 503 },
        ),
      ),
    )

    await renderPage()

    expect(
      await screen.findByRole('button', { name: '다시 확인' }),
    ).toBeVisible()
  })

  test('offers recheck when the request result cannot be confirmed', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () => HttpResponse.error()),
    )

    await renderPage()

    expect(
      await screen.findByText('알림 이력 조회 결과를 확인할 수 없습니다.'),
    ).toBeVisible()
    expect(screen.getByRole('button', { name: '다시 확인' })).toBeVisible()
  })

  test('does not render malformed success data', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '알림 이력을 조회했습니다.',
          data: { hasNext: false, nextCursor: null },
        }),
      ),
    )

    await renderPage()

    expect(
      await screen.findByText('알림 이력을 복구하는 중입니다.'),
    ).toBeVisible()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  test('renders only public fields from a history item', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          successResponse([
            historyItem({
              address: '민감한 원문 주소',
              body: '노출하면 안 되는 전체 본문',
              providerPayload: 'provider-secret',
              retryCount: 7,
              auditNote: '내부 감사 메모',
              deliveryStatus: 'FAILED',
            }),
          ]),
        ),
      ),
    )

    await renderPage()
    await screen.findByText('예약이 확정되었습니다.')

    for (const forbiddenText of [
      '민감한 원문 주소',
      '노출하면 안 되는 전체 본문',
      'provider-secret',
      '내부 감사 메모',
      'FAILED',
      '7',
    ]) {
      expect(screen.queryByText(forbiddenText)).not.toBeInTheDocument()
    }
  })

  test('removes one consumer history immediately when the page unmounts', async () => {
    let title = '첫 번째 소비자의 예약 알림'
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([historyItem({ title })])),
      ),
    )
    const queryClient = createTestQueryClient()

    const firstPage = await renderPage(queryClient)
    expect(await screen.findByText(title)).toBeVisible()
    expect(
      queryClient.getQueryData(['consumer', 'notification-history', 0]),
    ).toBeDefined()

    firstPage.unmount()

    expect(
      queryClient.getQueryData(['consumer', 'notification-history', 0]),
    ).toBeUndefined()

    title = '두 번째 소비자의 예약 알림'
    await renderPage(queryClient)

    expect(
      screen.queryByText('첫 번째 소비자의 예약 알림'),
    ).not.toBeInTheDocument()
    expect(await screen.findByText(title)).toBeVisible()
  })

  test('does not share history cache between consumer sessions', async () => {
    let title = '첫 번째 소비자의 예약 알림'
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(successResponse([historyItem({ title })])),
      ),
    )
    const queryClient = createTestQueryClient()

    const firstPage = await renderPage(queryClient, 1)
    expect(await screen.findByText(title)).toBeVisible()
    firstPage.unmount()

    title = '두 번째 소비자의 예약 알림'
    await renderPage(queryClient, 2)

    expect(
      queryClient.getQueryData(['consumer', 'notification-history', 1]),
    ).toBeUndefined()
    expect(await screen.findByText('두 번째 소비자의 예약 알림')).toBeVisible()
    expect(
      queryClient.getQueryData(['consumer', 'notification-history', 2]),
    ).toBeDefined()
  })
})
