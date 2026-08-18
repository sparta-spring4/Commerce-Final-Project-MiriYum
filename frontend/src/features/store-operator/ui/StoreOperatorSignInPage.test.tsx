import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { AuthErrorCode } from '../../auth/model/authErrors'
import { StoreOperatorAuthProvider } from '../StoreOperatorAuthProvider'
import {
  OPERATOR_CSRF_PATH,
  OPERATOR_REFRESH_PATH,
  OPERATOR_SESSION_CURRENT_PATH,
  OPERATOR_SESSIONS_PATH,
  tokenData,
  unauthenticatedOperator,
} from '../test/handlers'
import { LocationProbe } from '../test/renderOperator'
import { StoreOperatorSignInPage } from './StoreOperatorSignInPage'

function renderSignInAt(route: string = ROUTES.storeOperatorSignIn) {
  return render(
    <StoreOperatorAuthProvider>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route
            path={ROUTES.storeOperatorSignIn}
            element={<StoreOperatorSignInPage />}
          />
          <Route path={ROUTES.storeOperatorHome} element={<LocationProbe />} />
          <Route
            path={ROUTES.storeOperatorOperatingHours}
            element={<LocationProbe />}
          />
        </Routes>
      </MemoryRouter>
    </StoreOperatorAuthProvider>,
  )
}

function fillCredentials(email = 'owner@example.com', password = 'Miriyum1!') {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('비밀번호'), {
    target: { value: password },
  })
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
}

describe('식당 대표자 로그인 화면', () => {
  it('제출 전에 이메일 형식을 검증하고 서버를 부르지 않는다', async () => {
    let called = false
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () => {
        called = true
        return successResponse(tokenData())
      }),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials('not-an-email')
    submit()

    expect(
      await screen.findByText('이메일 형식으로 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('AUTH_005는 계정 존재 여부를 나누지 않는다', async () => {
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.INVALID_CREDENTIALS,
          '이메일 또는 비밀번호가 올바르지 않습니다.',
        ),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    expect(
      await screen.findByText('이메일 또는 비밀번호를 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/가입되지 않은/)).not.toBeInTheDocument()
  })

  it('요청 제한을 구분해 안내한다', async () => {
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () =>
        errorResponse(429, 'COMMON_010', '요청이 많습니다.'),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    expect(
      await screen.findByText(
        '로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('로그인 성공 후 목적지가 없으면 운영 홈으로 간다', async () => {
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () => successResponse(tokenData())),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        ROUTES.storeOperatorHome,
      ),
    )
  })

  it('보존한 목적지로 복귀한다', async () => {
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () => successResponse(tokenData())),
    )

    renderSignInAt(
      `${ROUTES.storeOperatorSignIn}?returnTo=%2Fstore-operator%2Fstores%2F7%2Foperating-hours`,
    )
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/store-operator/stores/7/operating-hours',
      ),
    )
  })

  it('외부 오리진 목적지는 무시하고 운영 홈으로 보낸다', async () => {
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () => successResponse(tokenData())),
    )

    renderSignInAt(
      `${ROUTES.storeOperatorSignIn}?returnTo=https%3A%2F%2Fevil.example%2Fsteal`,
    )
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        ROUTES.storeOperatorHome,
      ),
    )
    expect(screen.getByTestId('location')).not.toHaveTextContent('evil.example')
  })

  it('세션 복구가 끝나기 전에는 로그인을 보내지 않는다', async () => {
    let sessionCalls = 0
    let releaseRefresh = () => {}
    const refreshBlocked = new Promise<void>((resolve) => {
      releaseRefresh = resolve
    })

    server.use(
      // 재발급 응답을 붙들어 restoring 상태를 유지한다.
      http.post(OPERATOR_REFRESH_PATH, async () => {
        await refreshBlocked
        return errorResponse(
          401,
          AuthErrorCode.REFRESH_TOKEN_REQUIRED,
          'Refresh Token 쿠키가 필요합니다.',
        )
      }),
      http.post(OPERATOR_SESSIONS_PATH, () => {
        sessionCalls += 1
        return successResponse(tokenData())
      }),
    )

    renderSignInAt()

    fillCredentials()
    submit()

    // 복구 중인 재발급과 로그인이 같은 토큰 자리를 두고 경쟁하지 않게 막는다.
    expect(sessionCalls).toBe(0)
    expect(screen.getByRole('button', { name: '로그인' })).toBeDisabled()
    expect(
      screen.getByText(
        '로그인 상태를 확인하는 중입니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()

    releaseRefresh()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled(),
    )

    submit()
    await waitFor(() => expect(sessionCalls).toBe(1))
  })

  it('계정 유형을 고르는 입력 없이 셸별 진입점만 제공한다', async () => {
    server.use(unauthenticatedOperator)

    renderSignInAt()

    expect(
      await screen.findByRole('link', { name: '일반 사용자 로그인' }),
    ).toHaveAttribute('href', ROUTES.consumerSignIn)
    expect(screen.queryByLabelText('계정 유형')).not.toBeInTheDocument()
  })

  it('완료되지 않은 로그아웃을 알리고 서버 정리를 다시 시도한다', async () => {
    let deletes = 0
    sessionStorage.setItem('MIRIYUM_STORE_OPERATOR_SIGN_OUT_PENDING', 'true')
    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'
    server.use(
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({
          token: 'operator-csrf',
          headerName: 'X-CSRF-TOKEN',
        }),
      ),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => {
        deletes += 1
        return successResponse(null)
      }),
    )

    renderSignInAt()

    expect(
      await screen.findByText('이전 세션 로그아웃을 완료하지 못했습니다.'),
    ).toBeInTheDocument()
    fireEvent.click(
      screen.getByRole('button', { name: '로그아웃 다시 시도' }),
    )

    await waitFor(() => expect(deletes).toBe(1))
    await waitFor(() =>
      expect(
        screen.queryByText('이전 세션 로그아웃을 완료하지 못했습니다.'),
      ).not.toBeInTheDocument(),
    )
  })
})
