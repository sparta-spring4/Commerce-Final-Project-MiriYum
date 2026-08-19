import { render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { successResponse } from '../test/msw/envelope'
import { server } from '../test/msw/server'
import { createQueryClient } from '../shared/api/queryClient'
import { ConsumerAuthProvider } from '../domains/account/consumer/auth'
import { PlatformOperatorAuthProvider } from '../domains/account/platform-operator/auth'

const CONSUMER_REFRESH_PATH = '/api/v1/consumers/auth/token-refreshes'
const PO_REFRESH_PATH = '/api/v1/platform-operators/auth/token-refreshes'

/**
 * 두 shell이 서로의 인증 요청을 유발하지 않는지 고정한다.
 *
 * 운영 콘솔을 `ConsumerAuthProvider` 안에 두었던 적이 있다. 그러면 콘솔을
 * 열 때마다 소비자 세션 복구 요청이 함께 나갔다. 화면은 정상으로 보이지만
 * 운영자 브라우저에서 소비자 인증 시도가 계속 기록된다(PR #414 리뷰 지적).
 *
 * `App.tsx`는 경로로 셸을 가르므로 여기서는 provider 단위로 검증한다.
 * 한쪽 provider만 mount했을 때 다른 쪽 endpoint가 호출되지 않아야 한다.
 */
describe('셸 분리', () => {
  it('운영자 provider는 소비자 세션 복구를 호출하지 않는다', async () => {
    let consumerRefreshCalls = 0
    let operatorRefreshCalls = 0
    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => {
        consumerRefreshCalls += 1
        return successResponse(null)
      }),
      http.post(PO_REFRESH_PATH, () => {
        operatorRefreshCalls += 1
        return successResponse(null)
      }),
    )

    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={['/admin']}>
          <PlatformOperatorAuthProvider>
            <p>운영 콘솔</p>
          </PlatformOperatorAuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByText('운영 콘솔')
    await waitFor(() => expect(operatorRefreshCalls).toBeGreaterThan(0))
    expect(consumerRefreshCalls).toBe(0)
  })

  it('소비자 provider는 운영자 세션 복구를 호출하지 않는다', async () => {
    let consumerRefreshCalls = 0
    let operatorRefreshCalls = 0
    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => {
        consumerRefreshCalls += 1
        return successResponse(null)
      }),
      http.post(PO_REFRESH_PATH, () => {
        operatorRefreshCalls += 1
        return successResponse(null)
      }),
    )

    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={['/']}>
          <ConsumerAuthProvider>
            <p>소비자 화면</p>
          </ConsumerAuthProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByText('소비자 화면')
    await waitFor(() => expect(consumerRefreshCalls).toBeGreaterThan(0))
    expect(operatorRefreshCalls).toBe(0)
  })
})
