import { STORE_OPERATOR_PATHS } from '../../routes/paths/storeOperatorPaths'
import { fillPath } from '../../routes/path'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../../test/msw/server'
import { CurrentStoreProvider } from './CurrentStoreProvider'
import { StoreOperatorAuthProvider } from './StoreOperatorAuthProvider'
import {
  STORE_ID,
  authenticatedOperator,
  managedStoreHandler,
} from '../../../domains/store/store-operator/test/handlers'
import { StoreOperatorLayout } from './StoreOperatorLayout'

/**
 * 셸을 실제 route 계층 그대로 렌더한다.
 *
 * 레이아웃은 자기 route 패턴이 없어 자식 경로에서 `:storeId`를 직접 대조한다.
 * `Outlet` 없이 렌더하면 그 대조가 성립하지 않는다.
 */
function renderShell(route: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[route]}>
        <StoreOperatorAuthProvider>
          <CurrentStoreProvider>
            <Routes>
              <Route element={<StoreOperatorLayout />}>
                <Route
                  path={STORE_OPERATOR_PATHS.store}
                  element={<p>매장 본문</p>}
                />
                <Route
                  path={STORE_OPERATOR_PATHS.home}
                  element={<p>운영 홈 본문</p>}
                />
              </Route>
            </Routes>
          </CurrentStoreProvider>
        </StoreOperatorAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const STORE_ROUTE = fillPath(STORE_OPERATOR_PATHS.store, { storeId: STORE_ID })

describe('매장 운영자 셸', () => {
  it('현재 매장 이름과 운영 상태를 상단에 표시한다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderShell(STORE_ROUTE)

    expect(await screen.findByText('카페 에비뉴')).toBeInTheDocument()
    expect(screen.getByText('영업 중')).toBeInTheDocument()
  })

  it('매장 조회에 실패해도 셸이 본문 대신 오류 화면을 대신 띄우지 않는다', async () => {
    // 머리말이 같은 실패를 두 번 알리지 않는다. 본문이 설명할 몫이다.
    server.use(authenticatedOperator())

    renderShell(STORE_ROUTE)

    expect(await screen.findByText('매장 본문')).toBeInTheDocument()
    expect(screen.getByText(`매장 #${STORE_ID}`)).toBeInTheDocument()
  })

  it('매장을 알면 운영 홈과 관리 항목을 함께 보여 준다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderShell(STORE_ROUTE)

    expect(
      await screen.findByRole('link', { name: '운영 홈' }),
    ).toHaveAttribute('href', STORE_OPERATOR_PATHS.home)
    expect(screen.getByRole('link', { name: '메뉴 관리' })).toHaveAttribute(
      'href',
      fillPath(STORE_OPERATOR_PATHS.menus, { storeId: STORE_ID }),
    )
  })

  it('매장을 모르면 매장별 항목 없이 운영 홈만 남긴다', async () => {
    server.use(authenticatedOperator())

    renderShell(STORE_OPERATOR_PATHS.home)

    expect(
      await screen.findByRole('link', { name: '운영 홈' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: '메뉴 관리' }),
    ).not.toBeInTheDocument()
  })

  it('좁은 화면 내비 토글은 닫힘에서 시작해 눌러야 펼쳐진다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderShell(STORE_ROUTE)

    const toggle = await screen.findByRole('button', { name: '메뉴' })
    // 초기값이 열림이면 모바일에서 본문이 항목 여덟 개 아래로 밀린다.
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('항목을 고르면 열려 있던 내비를 닫는다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderShell(STORE_ROUTE)

    const toggle = await screen.findByRole('button', { name: '메뉴' })
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('link', { name: '메뉴 관리' }))

    // 열린 채로 두면 이동한 화면의 본문이 항목 아래에 가린다.
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('로그아웃은 상단 바에 둔다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderShell(STORE_ROUTE)

    const signOut = await screen.findByRole('button', { name: '로그아웃' })
    expect(signOut).toBeInTheDocument()
  })
})
