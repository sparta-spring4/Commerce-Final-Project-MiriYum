import { fireEvent, render, screen } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { ConsumerAuthProvider, useConsumerAuth } from '../ConsumerAuthProvider'
import { unauthenticatedConsumer } from '../test/handlers'
import { ConsumerKakaoSignUpPage } from './ConsumerKakaoSignUpPage'

const KAKAO_ACCOUNT_PATH = '/api/v1/consumers/auth/kakao/accounts'

function AuthenticatedProbe() {
  const { status } = useConsumerAuth()
  return <p data-testid="auth-status">{status}</p>
}

describe('일반 사용자 카카오 가입 완료', () => {
  it('가입 티켓과 서비스 필수정보만 보내고 Access Token을 메모리 로그인 상태로 전환한다', async () => {
    let requestBody: unknown = null
    server.use(
      unauthenticatedConsumer,
      http.post(KAKAO_ACCOUNT_PATH, async ({ request }) => {
        requestBody = await request.json()
        return successResponse({
          status: 'AUTHENTICATED',
          accessToken: 'kakao-access-token',
          tokenType: 'Bearer',
          expiresIn: 900,
        })
      }),
    )

    render(
      <TestQueryProvider>
        <ConsumerAuthProvider>
          <MemoryRouter
            initialEntries={[
              {
                pathname: ROUTES.consumerKakaoSignUp,
                state: { signUpTicket: 'temporary-ticket' },
              },
            ]}
          >
            <Routes>
              <Route path={ROUTES.consumerKakaoSignUp} element={<ConsumerKakaoSignUpPage />} />
              <Route path={ROUTES.home} element={<AuthenticatedProbe />} />
            </Routes>
          </MemoryRouter>
        </ConsumerAuthProvider>
      </TestQueryProvider>,
    )

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'kakao@example.com' } })
    fireEvent.change(screen.getByLabelText('휴대전화 번호'), { target: { value: '010-1234-5678' } })
    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '카카오사용자' } })
    fireEvent.click(screen.getByRole('checkbox', { name: '(필수) 만 14세 이상입니다.' }))
    fireEvent.click(screen.getByRole('button', { name: '가입 완료' }))

    expect(await screen.findByTestId('auth-status')).toHaveTextContent('authenticated')
    expect(requestBody).toEqual({
      signUpTicket: 'temporary-ticket',
      email: 'kakao@example.com',
      phoneNumber: '01012345678',
      ageConfirmed: true,
      nickname: '카카오사용자',
    })
  })
})
