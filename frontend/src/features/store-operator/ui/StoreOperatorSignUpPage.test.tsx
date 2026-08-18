import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { AccountErrorCode } from '../../auth/model/authErrors'
import { OPERATOR_ACCOUNTS_PATH } from '../test/handlers'
import { LocationProbe } from '../test/renderOperator'
import { StoreOperatorSignUpPage } from './StoreOperatorSignUpPage'

/**
 * 가입 화면은 인증 shell 밖에서도 동작해야 한다.
 * provider 없이 렌더해 셸 상태에 얽히지 않는 것을 함께 확인한다.
 */
function renderSignUp() {
  return render(
    <MemoryRouter initialEntries={[ROUTES.storeOperatorSignUp]}>
      <Routes>
        <Route
          path={ROUTES.storeOperatorSignUp}
          element={<StoreOperatorSignUpPage />}
        />
        <Route path={ROUTES.storeOperatorSignIn} element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

function fillForm(overrides: Partial<Record<string, string>> = {}) {
  const values: Record<string, string> = {
    이메일: 'owner@example.com',
    대표자명: '김대표',
    '휴대전화 번호': '010-1234-5678',
    비밀번호: 'Miriyum1!',
    '비밀번호 확인': 'Miriyum1!',
    ...overrides,
  }
  for (const [label, value] of Object.entries(values)) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '가입하기' }))
}

describe('식당 대표자 회원가입 화면', () => {
  it('계약이 정한 다섯 필드만 보낸다', async () => {
    let body: unknown = null
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, async ({ request }) => {
        body = await request.json()
        return successResponse({ accountId: '3' })
      }),
    )

    renderSignUp()
    fillForm()
    submit()

    await waitFor(() => expect(body).not.toBeNull())
    // 스키마가 additionalProperties: false다. 임의 필드를 더하면 전부 거절된다.
    expect(body).toEqual({
      email: 'owner@example.com',
      password: 'Miriyum1!',
      passwordConfirm: 'Miriyum1!',
      phoneNumber: '01012345678',
      displayName: '김대표',
    })
  })

  it('가입 성공 후 자동 로그인하지 않고 로그인 화면으로 보낸다', async () => {
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, () =>
        successResponse({ accountId: '3' }),
      ),
    )

    renderSignUp()
    fillForm()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        ROUTES.storeOperatorSignIn,
      ),
    )
  })

  it('대표자명 길이를 제출 전에 검증한다', async () => {
    let called = false
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, () => {
        called = true
        return successResponse({ accountId: '3' })
      }),
    )

    renderSignUp()
    fillForm({ 대표자명: '김' })
    submit()

    expect(
      await screen.findByText('대표자명은 2~50자여야 합니다.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('비밀번호가 서로 다르면 서버를 부르지 않는다', async () => {
    let called = false
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, () => {
        called = true
        return successResponse({ accountId: '3' })
      }),
    )

    renderSignUp()
    fillForm({ '비밀번호 확인': 'Miriyum2!' })
    submit()

    expect(await screen.findByText('비밀번호가 서로 다릅니다.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('중복 이메일과 중복 전화번호를 구분해 안내한다', async () => {
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, () =>
        errorResponse(
          409,
          AccountErrorCode.EMAIL_ALREADY_EXISTS,
          '이미 사용 중인 이메일입니다.',
        ),
      ),
    )

    renderSignUp()
    fillForm()
    submit()

    expect(
      await screen.findByText('이미 사용 중인 이메일입니다.'),
    ).toBeInTheDocument()
  })

  it('서버 필드 오류를 해당 입력에 연결한다', async () => {
    server.use(
      http.post(OPERATOR_ACCOUNTS_PATH, () =>
        errorResponse(400, 'COMMON_001', '입력값이 올바르지 않습니다.', [
          { field: 'phoneNumber', reason: '사용할 수 없는 번호입니다.' },
        ]),
      ),
    )

    renderSignUp()
    fillForm()
    submit()

    await waitFor(() =>
      expect(screen.getByLabelText('휴대전화 번호')).toHaveAccessibleDescription(
        expect.stringContaining('사용할 수 없는 번호입니다.'),
      ),
    )
  })
})
