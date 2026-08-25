import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useState, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { describe, expect, test, vi } from 'vitest'

import { createApiClient } from '../../../shared/api/client'
import { server } from '../../../test/msw/server'
import type { NotificationEventStreamClient } from './notificationEventStream'

const UNREAD_PATH = '/api/v1/consumers/me/notifications/unread-count'

function createEventStream() {
  let changed: (() => void) | undefined
  const subscribe = vi.fn(async (options: { signal: AbortSignal; onChanged: () => void }) => {
    changed = options.onChanged
    await new Promise<void>((resolve) => options.signal.addEventListener('abort', () => resolve(), { once: true }))
  })
  return {
    client: { subscribe } as NotificationEventStreamClient,
    emit: () => changed?.(),
    subscribe,
  }
}

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('NotificationCenterProvider', () => {
  test('does not query or open a stream before authentication completes', async () => {
    let unreadCalls = 0
    server.use(http.get(UNREAD_PATH, () => {
      unreadCalls += 1
      return HttpResponse.json({ code: 'SUCCESS', message: '조회', data: { unreadCount: 1 } })
    }))
    const events = createEventStream()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { NotificationCenterProvider } = await import('./NotificationCenterProvider')

    render(
      <NotificationCenterProvider
        apiClient={createApiClient()}
        eventStream={events.client}
        sessionKey={0}
        enabled={false}
      >
        <span>복구 중</span>
      </NotificationCenterProvider>,
      { wrapper: wrapper(queryClient) },
    )

    expect(screen.getByText('복구 중')).toBeVisible()
    expect(unreadCalls).toBe(0)
    expect(events.subscribe).not.toHaveBeenCalled()
  })

  test('keeps one stream and refetches unread count and history after a changed signal', async () => {
    let unreadCount = 1
    let calls = 0
    server.use(http.get(UNREAD_PATH, () => {
      calls += 1
      return HttpResponse.json({ code: 'SUCCESS', message: '조회', data: { unreadCount } })
    }))
    const events = createEventStream()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    queryClient.setQueryData(['consumer', 'notification-history', 4], { pages: [] })
    const { NotificationCenterProvider, useNotificationCenter } = await import('./NotificationCenterProvider')

    function Probe() {
      const center = useNotificationCenter()
      return <span>{center.unreadCount}</span>
    }

    render(
      <NotificationCenterProvider apiClient={createApiClient()} eventStream={events.client} sessionKey={4}>
        <Probe />
      </NotificationCenterProvider>,
      { wrapper: wrapper(queryClient) },
    )
    expect(await screen.findByText('1')).toBeVisible()
    expect(events.subscribe).toHaveBeenCalledTimes(1)

    unreadCount = 3
    await act(async () => events.emit())
    expect(await screen.findByText('3')).toBeVisible()
    expect(calls).toBeGreaterThanOrEqual(2)
    expect(
      queryClient.getQueryState(['consumer', 'notification-history', 4])?.isInvalidated,
    ).toBe(true)
  })

  test('does not convert a count lookup failure into zero', async () => {
    server.use(http.get(UNREAD_PATH, () => HttpResponse.error()))
    const events = createEventStream()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { NotificationCenterProvider, useNotificationCenter } = await import('./NotificationCenterProvider')

    function Probe() {
      const center = useNotificationCenter()
      return <span>{center.isUnreadCountError ? 'error' : String(center.unreadCount)}</span>
    }

    render(
      <NotificationCenterProvider apiClient={createApiClient()} eventStream={events.client} sessionKey={1}>
        <Probe />
      </NotificationCenterProvider>,
      { wrapper: wrapper(queryClient) },
    )
    expect(await screen.findByText('error')).toBeVisible()
  })

  test('does not overwrite a newer SSE count with a delayed read response', async () => {
    let unreadCount = 2
    let releaseRead!: () => void
    const readPending = new Promise<void>((resolve) => {
      releaseRead = resolve
    })
    server.use(
      http.get(UNREAD_PATH, () =>
        HttpResponse.json({ code: 'SUCCESS', message: '조회', data: { unreadCount } }),
      ),
      http.post('/api/v1/consumers/me/notifications/1001/reads', async () => {
        await readPending
        return HttpResponse.json({ code: 'SUCCESS', message: '읽음', data: { unreadCount: 0 } })
      }),
    )
    const events = createEventStream()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { NotificationCenterProvider, useNotificationCenter } = await import('./NotificationCenterProvider')

    function Probe() {
      const center = useNotificationCenter()
      const [readCompleted, setReadCompleted] = useState(false)
      return (
        <>
          <span>count:{center.unreadCount}</span>
          <button type="button" onClick={() => void center.markRead('1001').then(() => setReadCompleted(true))}>읽음</button>
          {readCompleted ? <span>완료</span> : null}
        </>
      )
    }

    render(
      <NotificationCenterProvider apiClient={createApiClient()} eventStream={events.client} sessionKey={5}>
        <Probe />
      </NotificationCenterProvider>,
      { wrapper: wrapper(queryClient) },
    )
    expect(await screen.findByText('count:2')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '읽음' }))
    unreadCount = 1
    await act(async () => events.emit())
    expect(await screen.findByText('count:1')).toBeVisible()

    await act(async () => releaseRead())
    expect(await screen.findByText('완료')).toBeVisible()
    expect(screen.getByText('count:1')).toBeVisible()
  })
})
