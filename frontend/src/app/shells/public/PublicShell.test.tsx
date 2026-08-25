import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'

import { createApiClient } from '../../../shared/api/client'
import { server } from '../../../test/msw/server'
import type { NotificationEventStreamClient } from '../../../domains/notification/consumer/notificationEventStream'
import type { WaitingEventStreamClient } from '../../../domains/waiting/consumer/waitingEventStream'
import type { ConsumerAuthContextValue } from '../consumer/ConsumerAuthProvider'
import { useConsumerAuth } from '../consumer/ConsumerAuthProvider'
import { PublicShell } from './PublicShell'

vi.mock('../consumer/ConsumerAuthProvider', async () => {
  const actual = await vi.importActual<
    typeof import('../consumer/ConsumerAuthProvider')
  >('../consumer/ConsumerAuthProvider')

  return { ...actual, useConsumerAuth: vi.fn() }
})

const UNREAD_PATH = '/api/v1/consumers/me/notifications/unread-count'

function createEventStream() {
  let subscribedSignal: AbortSignal | undefined
  const subscribe = vi.fn(
    async ({ signal }: { signal: AbortSignal }) => {
      subscribedSignal = signal
      await new Promise<void>((resolve) =>
        signal.addEventListener('abort', () => resolve(), { once: true }),
      )
    },
  )

  return {
    client: { subscribe } as NotificationEventStreamClient,
    isAborted: () => subscribedSignal?.aborted ?? false,
    subscribe,
  }
}

function createAuthValue(
  status: ConsumerAuthContextValue['status'],
  notificationEventStream: NotificationEventStreamClient,
): ConsumerAuthContextValue {
  return {
    status,
    sessionKey: 7,
    apiClient: createApiClient(),
    notificationEventStream,
    waitingEventStream: { subscribe: vi.fn() } as WaitingEventStreamClient,
    signIn: vi.fn(),
    completeKakaoSignIn: vi.fn(),
    signOut: vi.fn(),
    signOutNotice: null,
    dismissSignOutNotice: vi.fn(),
  }
}

function renderPublicShell() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={children}>
              <Route index element={<p>홈</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  return render(<PublicShell />, { wrapper: Wrapper })
}

describe('PublicShell', () => {
  beforeEach(() => {
    vi.mocked(useConsumerAuth).mockReset()
  })

  test('로그인한 공개 화면에 미확인 알림과 단일 변경 stream을 연결한다', async () => {
    server.use(
      http.get(UNREAD_PATH, () =>
        HttpResponse.json({
          code: 'SUCCESS',
          message: '조회',
          data: { unreadCount: 3 },
        }),
      ),
    )
    const events = createEventStream()
    vi.mocked(useConsumerAuth).mockReturnValue(
      createAuthValue('authenticated', events.client),
    )

    const view = renderPublicShell()

    expect(
      await screen.findByRole('link', { name: '알림, 읽지 않은 알림 3개' }),
    ).toHaveAttribute('href', '/mypage/notifications')
    expect(screen.getByRole('button', { name: '내 계정' })).toBeVisible()
    expect(events.subscribe).toHaveBeenCalledTimes(1)

    view.unmount()
    expect(events.isAborted()).toBe(true)
  })

  test('비로그인 공개 화면은 보호 알림 조회와 stream을 시작하지 않는다', async () => {
    let unreadCalls = 0
    server.use(
      http.get(UNREAD_PATH, () => {
        unreadCalls += 1
        return HttpResponse.json({
          code: 'SUCCESS',
          message: '조회',
          data: { unreadCount: 3 },
        })
      }),
    )
    const events = createEventStream()
    vi.mocked(useConsumerAuth).mockReturnValue(
      createAuthValue('unauthenticated', events.client),
    )

    renderPublicShell()

    expect(screen.getByText('홈')).toBeVisible()
    expect(screen.getAllByRole('link', { name: '로그인' })).not.toHaveLength(0)
    expect(screen.queryByRole('link', { name: /알림/ })).not.toBeInTheDocument()
    expect(unreadCalls).toBe(0)
    expect(events.subscribe).not.toHaveBeenCalled()
  })
})
