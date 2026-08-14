import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { describe, expect, test } from 'vitest'

import { createApiClient } from '../../shared/api/client'
import { server } from '../../test/msw/server'

const NOTIFICATION_HISTORY_PATH = '/api/v1/consumers/me/notifications'

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

async function renderPage() {
  const { NotificationHistoryPage } = await import('./NotificationHistoryPage')
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }

  render(<NotificationHistoryPage apiClient={createApiClient()} />, {
    wrapper: Wrapper,
  })
}

describe('NotificationHistoryPage', () => {
  test('renders delivered history without an unsupported action control', async () => {
    server.use(
      http.get(NOTIFICATION_HISTORY_PATH, () =>
        HttpResponse.json(
          successResponse([
            historyItem({
              action: {
                type: 'RESERVATION_DETAIL',
                resource: { type: 'RESERVATION', id: '501' },
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
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
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
})
