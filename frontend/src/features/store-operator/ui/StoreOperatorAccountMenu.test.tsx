import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { CurrentStoreProvider, useCurrentStore } from '../CurrentStoreProvider'
import { StoreOperatorAuthProvider } from '../StoreOperatorAuthProvider'
import {
  OPERATOR_CSRF_PATH,
  OPERATOR_SESSION_CURRENT_PATH,
  STORE_ID,
  authenticatedOperator,
} from '../test/handlers'
import { StoreOperatorAccountMenu } from './StoreOperatorLayout'

const STORAGE_KEY = 'MIRIYUM_STORE_OPERATOR_CURRENT_STORE'

function StoreProbe() {
  const { storeId, selectStore } = useCurrentStore()
  return (
    <div>
      <p data-testid="store">{storeId ?? '없음'}</p>
      <button type="button" onClick={() => selectStore(STORE_ID)}>
        매장 선택
      </button>
    </div>
  )
}

function renderAccountMenu() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <StoreOperatorAuthProvider>
          <CurrentStoreProvider>
            <StoreProbe />
            <StoreOperatorAccountMenu />
          </CurrentStoreProvider>
        </StoreOperatorAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('매장 운영자 계정 영역', () => {
  it('로그아웃하면 현재 매장 선택도 함께 지운다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => successResponse(null)),
    )

    renderAccountMenu()
    await screen.findByRole('button', { name: '로그아웃' })

    fireEvent.click(screen.getByRole('button', { name: '매장 선택' }))
    expect(screen.getByTestId('store')).toHaveTextContent(STORE_ID)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(STORE_ID)

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    // 남겨 두면 같은 브라우저에서 다른 대표자가 로그인했을 때 이전 계정의
    // 매장이 현재 매장으로 잡힌다.
    await waitFor(() =>
      expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull(),
    )
    expect(screen.getByTestId('store')).toHaveTextContent('없음')
  })

  it('서버 정리가 실패해도 매장 선택을 남기지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      // CSRF 준비가 실패하면 로그아웃 요청 자체를 보내지 못한다.
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => successResponse(null)),
    )

    renderAccountMenu()
    await screen.findByRole('button', { name: '로그아웃' })

    fireEvent.click(screen.getByRole('button', { name: '매장 선택' }))
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull(),
    )
  })
})
