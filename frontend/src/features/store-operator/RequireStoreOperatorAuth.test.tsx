import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ROUTES } from '../../app/routes'
import { server } from '../../test/msw/server'
import { LocationProbe } from './test/renderOperator'
import { RequireStoreOperatorAuth } from './RequireStoreOperatorAuth'
import { StoreOperatorAuthProvider } from './StoreOperatorAuthProvider'
import { authenticatedOperator, unauthenticatedOperator } from './test/handlers'

function renderGuardAt(route: string) {
  return render(
    <StoreOperatorAuthProvider>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<RequireStoreOperatorAuth />}>
            <Route
              path={ROUTES.storeOperatorHome}
              element={<p>운영자 보호 화면</p>}
            />
          </Route>
          <Route path={ROUTES.storeOperatorSignIn} element={<LocationProbe />} />
          <Route path={ROUTES.consumerSignIn} element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </StoreOperatorAuthProvider>,
  )
}

describe('매장 운영자 보호 route 가드', () => {
  it('세션 복구 중에는 로그인으로 보내지 않는다', () => {
    server.use(unauthenticatedOperator)

    renderGuardAt(ROUTES.storeOperatorHome)

    expect(
      screen.getByText('로그인 상태를 확인하는 중입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('미인증이면 운영자 로그인으로 보내고 목적지를 보존한다', async () => {
    server.use(unauthenticatedOperator)

    renderGuardAt(ROUTES.storeOperatorHome)

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        `${ROUTES.storeOperatorSignIn}?returnTo=%2Fstore-operator`,
      ),
    )
    // 교차 셸로 보내지 않는다. 일반 사용자 로그인은 다른 namespace다.
    expect(screen.getByTestId('location').textContent).not.toMatch(
      /^\/sign-in/,
    )
  })

  it('인증되면 보호 화면을 그린다', async () => {
    server.use(authenticatedOperator())

    renderGuardAt(ROUTES.storeOperatorHome)

    expect(await screen.findByText('운영자 보호 화면')).toBeInTheDocument()
  })
})
