import type { QueryClient } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
import { ConsumerKakaoSignUpPage } from './ConsumerKakaoSignUpPage'

const KAKAO_ACCOUNT_PATH = '/api/v1/consumers/auth/kakao/accounts'

function AuthStatusProbe() {
  const { status } = useConsumerAuth()
  return <p data-testid="auth-status">{status}</p>
}

function renderSignUp() {
  let queryClient: QueryClient | null = null

  const rendered = render(
    <TestQueryProvider onReady={(client) => { queryClient = client }}>
      <ConsumerAuthProvider>
        <MemoryRouter
          initialEntries={[
            {
              pathname: ROUTES.consumerKakaoSignUp,
              state: { signUpTicket: 'temporary-ticket' },
            },
          ]}
        >
          <AuthStatusProbe />
          <Routes>
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

    const { queryClient } = renderSignUp()
    await waitFor(() => expect(screen.getByTestId('auth-status')).toHaveTextContent('unauthenticated'))
    seedProtectedCache(queryClient)

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
    expect(protectedCache(queryClient)).toEqual([undefined, undefined, undefined])
  })
})
