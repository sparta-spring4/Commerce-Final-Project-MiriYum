import { CONSUMER_PATHS } from '../../routes/paths/consumerPaths'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { readReturnTo } from '../../returnTo'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { ConsumerAuthProvider } from './ConsumerAuthProvider'
import { RequireConsumerAuth } from './RequireConsumerAuth'
import { authenticatedConsumer, unauthenticatedConsumer } from '../../../domains/account/consumer/auth/test/handlers'

/**
 * 로그인 화면 자리에 현재 router 위치를 노출한다.
 * MemoryRouter는 window.location을 바꾸지 않으므로 useLocation으로 읽는다.
 */
function SignInProbe() {
  const { pathname, search } = useLocation()
  return <p data-testid="sign-in">{`${pathname}${search}`}</p>
}

function renderGuardedAt(route: string) {
  return render(
    <TestQueryProvider>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route element={<RequireConsumerAuth />}>
              <Route path="/mypage" element={<p>마이페이지 내용</p>} />
            </Route>
            <Route path={CONSUMER_PATHS.signIn} element={<SignInProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
}

describe('일반 사용자 보호 route 가드', () => {
  it('세션 복구 중에는 판정하지 않는다', () => {
    server.use(unauthenticatedConsumer)

    renderGuardedAt('/mypage')

    // 복구 전에 미인증으로 판정하면 새로고침마다 로그인 화면이 깜빡인다.
    expect(
      screen.getByText('로그인 상태를 확인하는 중입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('sign-in')).not.toBeInTheDocument()
  })

  it('인증되면 보호 화면을 보여 준다', async () => {
    server.use(authenticatedConsumer())

    renderGuardedAt('/mypage')

    expect(await screen.findByText('마이페이지 내용')).toBeInTheDocument()
  })

  it('미인증이면 로그인으로 보내고 원래 목적지를 보존한다', async () => {
    server.use(unauthenticatedConsumer)

    renderGuardedAt('/mypage?tab=reservations')

    await waitFor(() => expect(screen.getByTestId('sign-in')).toBeInTheDocument())

    const shown = screen.getByTestId('sign-in').textContent ?? ''
    expect(shown.startsWith(`${CONSUMER_PATHS.signIn}?`)).toBe(true)
    expect(screen.queryByText('마이페이지 내용')).not.toBeInTheDocument()

    const search = shown.slice(shown.indexOf('?'))
    expect(readReturnTo(search, 'http://localhost')).toBe(
      '/mypage?tab=reservations',
    )
  })

  it('보존한 목적지가 외부 오리진이면 복귀 대상으로 인정하지 않는다', () => {
    // 가드가 만들어 둔 returnTo를 읽는 쪽 규칙을 함께 고정한다.
    expect(
      readReturnTo('?returnTo=https%3A%2F%2Fevil.example', 'http://localhost'),
    ).toBeNull()
    expect(
      readReturnTo('?returnTo=%2F%5Cevil.example', 'http://localhost'),
    ).toBeNull()
  })
})
