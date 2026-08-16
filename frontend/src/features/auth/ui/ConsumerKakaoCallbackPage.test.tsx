import type { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { consumerAccountKeys } from '../../consumer-account'
import { pickupKeys } from '../../pickup-reservations/api/queries'
import { reservationKeys } from '../../reservations/api/queries'
import { ConsumerAuthProvider, useConsumerAuth } from '../ConsumerAuthProvider'
import { unauthenticatedConsumer } from '../test/handlers'
import { ConsumerKakaoCallbackPage } from './ConsumerKakaoCallbackPage'
import { ConsumerKakaoSignUpPage } from './ConsumerKakaoSignUpPage'

const KAKAO_SESSION_PATH = '/api/v1/consumers/auth/kakao/sessions'

function AuthStatusProbe() {
  const { status } = useConsumerAuth()
  return <p data-testid="auth-status">{status}</p>
}

function renderCallback(route: string) {
  let queryClient: QueryClient | null = null

  const rendered = render(
    <TestQueryProvider onReady={(client) => { queryClient = client }}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <AuthStatusProbe />
          <Routes>
            <Route path={ROUTES.consumerKakaoCallback} element={<ConsumerKakaoCallbackPage />} />
            <Route path={ROUTES.consumerKakaoSignUp} element={<ConsumerKakaoSignUpPage />} />
            <Route path={ROUTES.home} element={<p>홈</p>} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )

  if (queryClient === null) {
    throw new Error('TestQueryProvider가 QueryClient를 넘겨주지 않았습니다.')
  }

  return { ...rendered, queryClient: queryClient as QueryClient }
}

function seedProtectedCache(queryClient: QueryClient) {
  queryClient.setQueryData(consumerAccountKeys.me(), { nickname: '이전 사용자' })
  queryClient.setQueryData(reservationKeys.detail('r-1'), { storeName: '이전 사용자' })
  queryClient.setQueryData(pickupKeys.detail('p-1'), { storeName: '이전 사용자' })
}

function protectedCache(queryClient: QueryClient) {
  return [
    queryClient.getQueryData(consumerAccountKeys.me()),
    queryClient.getQueryData(reservationKeys.detail('r-1')),
    queryClient.getQueryData(pickupKeys.detail('p-1')),
  ]
}

describe('일반 사용자 카카오 콜백', () => {
  it('연결된 카카오 계정이면 code·state를 세션 API에 교환하고 메모리 로그인 상태로 이동한다', async () => {
    let requestBody: unknown = null
    server.use(
      unauthenticatedConsumer,
      http.post(KAKAO_SESSION_PATH, async ({ request }) => {
        requestBody = await request.json()
        return successResponse({
          status: 'AUTHENTICATED',
          accessToken: 'kakao-access-token',
          tokenType: 'Bearer',
          expiresIn: 900,
        })
      }),
    )

    renderCallback('/auth/kakao/callback?code=authorization-code&state=signed-state')

    expect(await screen.findByTestId('auth-status')).toHaveTextContent('authenticated')
    expect(requestBody).toEqual({
      authorizationCode: 'authorization-code',
      state: 'signed-state',
      redirectUri: `${window.location.origin}/auth/kakao/callback`,
    })
  })

  it('첫 카카오 계정이면 가입 티켓을 URL에 노출하지 않고 가입 완료 화면으로 이동한다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(KAKAO_SESSION_PATH, () =>
        successResponse({ status: 'SIGN_UP_REQUIRED', signUpTicket: 'temporary-ticket' }),
      ),
    )

    renderCallback('/auth/kakao/callback?code=authorization-code&state=signed-state')

    expect(await screen.findByRole('heading', { name: '카카오로 가입하기' })).toBeInTheDocument()
    expect(window.location.search).not.toContain('temporary-ticket')
  })

  it('카카오 콜백도 인증 상태 전환 전에 이전 보호 캐시를 지운다', async () => {
    let resolveSession: (() => void) | undefined
    const sessionResponse = new Promise<void>((resolve) => {
      resolveSession = resolve
    })

    server.use(
      unauthenticatedConsumer,
      http.post(KAKAO_SESSION_PATH, async () => {
        await sessionResponse
        return successResponse({
          status: 'AUTHENTICATED',
          accessToken: 'kakao-access-token',
          tokenType: 'Bearer',
          expiresIn: 900,
        })
      }),
    )

    const { queryClient } = renderCallback('/auth/kakao/callback?code=authorization-code&state=signed-state')
    await waitFor(() => expect(screen.getByTestId('auth-status')).toHaveTextContent('unauthenticated'))
    seedProtectedCache(queryClient)

    resolveSession?.()

    await waitFor(() => expect(screen.getByTestId('auth-status')).toHaveTextContent('authenticated'))
    expect(protectedCache(queryClient)).toEqual([undefined, undefined, undefined])
  })

  it('code 또는 state가 없으면 세션 API를 호출하지 않고 다시 시작하도록 안내한다', async () => {
    let called = false
    server.use(
      unauthenticatedConsumer,
      http.post(KAKAO_SESSION_PATH, () => {
        called = true
        return successResponse({ status: 'AUTHENTICATED', accessToken: 'unused' })
      }),
    )

    renderCallback('/auth/kakao/callback?code=authorization-code')

    expect(await screen.findByText('카카오 로그인 정보를 확인하지 못했습니다. 다시 시도해 주세요.')).toBeInTheDocument()
    await waitFor(() => expect(called).toBe(false))
  })
})
