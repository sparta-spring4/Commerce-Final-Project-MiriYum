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

  it('계정 유형을 고르는 입력 없이 셸별 진입점만 제공한다', async () => {
    server.use(unauthenticatedOperator)

    renderSignInAt()

    expect(
      await screen.findByRole('link', { name: '일반 사용자 로그인' }),
    ).toHaveAttribute('href', ROUTES.consumerSignIn)
    expect(screen.queryByLabelText('계정 유형')).not.toBeInTheDocument()
  })
})
