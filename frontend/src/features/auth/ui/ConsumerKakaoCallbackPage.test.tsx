import { render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { ConsumerAuthProvider, useConsumerAuth } from '../ConsumerAuthProvider'
import { unauthenticatedConsumer } from '../test/handlers'
import { ConsumerKakaoCallbackPage } from './ConsumerKakaoCallbackPage'
import { ConsumerKakaoSignUpPage } from './ConsumerKakaoSignUpPage'

const KAKAO_SESSION_PATH = '/api/v1/consumers/auth/kakao/sessions'

function AuthenticatedProbe() {
  const { status } = useConsumerAuth()
  return <p data-testid="auth-status">{status}</p>
}

function renderCallback(route: string) {
  return render(
    <TestQueryProvider>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={ROUTES.consumerKakaoCallback} element={<ConsumerKakaoCallbackPage />} />
            <Route path={ROUTES.consumerKakaoSignUp} element={<ConsumerKakaoSignUpPage />} />
            <Route path={ROUTES.home} element={<AuthenticatedProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
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
