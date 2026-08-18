import { render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
  unauthenticatedPlatformOperator,
} from '../features/platform-operator-auth/test/handlers'
import { successResponse } from '../test/msw/envelope'
import { server } from '../test/msw/server'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.resetModules()
})

describe('플랫폼 운영자 route context', () => {
  test('기능 플래그가 켜지면 /admin/login이 콘솔 로그인 화면에 매칭된다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    server.use(unauthenticatedPlatformOperator)
    window.history.pushState({}, '', '/admin/login')

    const { default: App } = await import('./App')
    render(<App />)

    expect(
      await screen.findByRole('heading', { name: '운영자 로그인' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('페이지를 찾을 수 없습니다')).not.toBeInTheDocument()
  })

  test('관리자 상세 경로에서도 소비자 세션 복구를 호출하지 않는다', async () => {
    vi.stubEnv('VITE_PLATFORM_OPERATOR_ENABLED', 'true')
    let consumerRefreshCalls = 0
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
      http.post('/api/v1/consumers/auth/token-refreshes', () => {
        consumerRefreshCalls += 1
        return successResponse(null)
      }),
      http.get('/api/v1/platform-operators/members', () =>
        successResponse({
          content: [],
          number: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        }),
      ),
    )
    window.history.pushState({}, '', '/admin/members')

    const { default: App } = await import('./App')
    render(<App />)

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '회원 관리' })).toBeInTheDocument(),
    )
    expect(consumerRefreshCalls).toBe(0)
  })
})
