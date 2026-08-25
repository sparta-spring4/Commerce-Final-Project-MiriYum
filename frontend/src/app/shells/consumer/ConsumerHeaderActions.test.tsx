import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, test, vi } from 'vitest'

vi.mock('./ConsumerAccountMenu', () => ({ ConsumerAccountMenu: () => <span>계정</span> }))

describe('ConsumerHeaderActions', () => {
  test.each([
    [0, null],
    [1, '1'],
    [99, '99'],
    [100, '99+'],
    [321, '99+'],
  ])('renders unread count %i as %s', async (unreadCount, expected) => {
    const { NotificationCenterContext } = await import('../../../domains/notification/consumer/NotificationCenterProvider')
    type NotificationCenterContextValue = import('../../../domains/notification/consumer/NotificationCenterProvider').NotificationCenterContextValue
    const { ConsumerHeaderActions } = await import('./ConsumerHeaderActions')
    const queryClient = new QueryClient()
    const value: NotificationCenterContextValue = {
      unreadCount,
      isUnreadCountError: false,
      connectionState: null,
      markRead: vi.fn(),
      markAllRead: vi.fn(),
    }

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <NotificationCenterContext.Provider value={value}>
            <ConsumerHeaderActions />
          </NotificationCenterContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    const link = screen.getByRole('link', { name: expected === null ? '알림' : `알림, 읽지 않은 알림 ${unreadCount}개` })
    expect(link).toHaveAttribute('href', '/mypage/notifications')
    if (expected === null) expect(screen.queryByTestId('notification-badge')).not.toBeInTheDocument()
    else expect(screen.getByTestId('notification-badge')).toHaveTextContent(expected)
  })

  test('announces a count lookup failure instead of showing zero', async () => {
    const { NotificationCenterContext } = await import('../../../domains/notification/consumer/NotificationCenterProvider')
    type NotificationCenterContextValue = import('../../../domains/notification/consumer/NotificationCenterProvider').NotificationCenterContextValue
    const { ConsumerHeaderActions } = await import('./ConsumerHeaderActions')
    const value: NotificationCenterContextValue = {
      unreadCount: null,
      isUnreadCountError: true,
      connectionState: null,
      markRead: vi.fn(),
      markAllRead: vi.fn(),
    }

    render(
      <MemoryRouter>
        <NotificationCenterContext.Provider value={value}>
          <ConsumerHeaderActions />
        </NotificationCenterContext.Provider>
      </MemoryRouter>,
    )

    expect(
      screen.getByRole('link', { name: '알림, 읽지 않은 알림 개수를 확인할 수 없음' }),
    ).toBeVisible()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })
})
