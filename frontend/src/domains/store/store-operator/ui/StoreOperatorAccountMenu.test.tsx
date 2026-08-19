import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { AuthErrorCode } from '../../../../shared/auth/authErrors'
import { CurrentStoreProvider, useCurrentStore } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { StoreOperatorAuthProvider } from '../../../../app/shells/store-operator/StoreOperatorAuthProvider'
import {
  OPERATOR_CSRF_PATH,
  OPERATOR_SESSION_CURRENT_PATH,
  STORE_ID,
  authenticatedOperator,
} from '../test/handlers'
import { StoreOperatorAccountMenu } from '../../../../app/shells/store-operator/StoreOperatorLayout'

const STORAGE_KEY = 'MIRIYUM_STORE_OPERATOR_CURRENT_STORE'

function LocationProbe() {
  const { pathname } = useLocation()
  return <p data-testid="location">{pathname}</p>
}

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
      <MemoryRouter initialEntries={[STORE_OPERATOR_PATHS.home]}>
        <StoreOperatorAuthProvider>
          <CurrentStoreProvider>
            <LocationProbe />
            <StoreProbe />
            <StoreOperatorAccountMenu />
          </CurrentStoreProvider>
        </StoreOperatorAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** 로그아웃 전에 매장을 선택해 둔다. 정리 대상이 있어야 검증이 성립한다. */
async function signInAndSelectStore() {
  renderAccountMenu()
  await screen.findByRole('button', { name: '로그아웃' })

  fireEvent.click(screen.getByRole('button', { name: '매장 선택' }))
  expect(screen.getByTestId('store')).toHaveTextContent(STORE_ID)
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(STORE_ID)
}

/** 로그아웃 뒤 클라이언트에 남아야 할 것이 없는지 한 번에 확인한다. */
async function expectSignedOutLocally() {
  await waitFor(() =>
    expect(screen.getByTestId('location')).toHaveTextContent(
      STORE_OPERATOR_PATHS.signIn,
    ),
  )
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  expect(screen.getByTestId('store')).toHaveTextContent('없음')
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

    await signInAndSelectStore()
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    // 남겨 두면 같은 브라우저에서 다른 대표자가 로그인했을 때 이전 계정의
    // 매장이 현재 매장으로 잡힌다.
    await expectSignedOutLocally()
  })

  it('CSRF 준비가 네트워크 오류로 끊겨도 로그아웃을 끝낸다', async () => {
    server.use(
      authenticatedOperator(),
      // 서버에 닿지 못한 실패다. client가 NetworkError로 감싸 다시 던진다.
      http.get(OPERATOR_CSRF_PATH, () => HttpResponse.error()),
    )

    await signInAndSelectStore()
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    // 예외가 흘러 정리 단계를 건너뛰면 매장 ID가 남고 화면도 그대로 머문다.
    await expectSignedOutLocally()
  })

  it('세션 삭제 요청이 네트워크 오류로 끊겨도 로그아웃을 끝낸다', async () => {
    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'

    server.use(
      authenticatedOperator(),
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({ token: 'operator-csrf', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => HttpResponse.error()),
    )

    await signInAndSelectStore()
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await expectSignedOutLocally()
  })

  it('서버가 세션 삭제를 거절해도 로그아웃을 끝낸다', async () => {
    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'

    server.use(
      authenticatedOperator(),
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({ token: 'operator-csrf', headerName: 'X-CSRF-TOKEN' }),
      ),
      // 이미 세션이 없다는 뜻이므로 사용자에게 되묻지 않는다.
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.REFRESH_TOKEN_INVALID,
          'Refresh Token이 올바르지 않습니다.',
        ),
      ),
    )

    await signInAndSelectStore()
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await expectSignedOutLocally()
  })
})
