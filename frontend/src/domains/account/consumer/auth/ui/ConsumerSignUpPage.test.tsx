import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../../test/msw/envelope'
import { server } from '../../../../../test/msw/server'
import { AccountErrorCode } from '../../../../../shared/auth/authErrors'
import { CONSUMER_ACCOUNTS_PATH } from '../test/handlers'
import { ConsumerSignUpPage } from './ConsumerSignUpPage'

/**
 * 가입 화면은 인증 shell 없이도 동작한다. 가입은 보호 API가 아니고
 * 성공해도 자동 로그인하지 않으므로 provider로 감싸지 않는다.
 */
function renderSignUp() {
  return render(
    <MemoryRouter initialEntries={[CONSUMER_PATHS.signUp]}>
      <Routes>
        <Route path={CONSUMER_PATHS.signUp} element={<ConsumerSignUpPage />} />
        <Route path={CONSUMER_PATHS.signIn} element={<p>로그인 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

/**
 * 레이블은 정확히 일치시킨다. getByLabelText는 문자열에 완전 일치를 적용하므로
 * "비밀번호"가 "비밀번호 확인"까지 잡지 않는다.
 */
const LABEL = {
  email: '이메일',
  password: '비밀번호',
  passwordConfirm: '비밀번호 확인',
  phoneNumber: '휴대전화 번호',
  nickname: '닉네임',
} as const

type FieldName = keyof typeof LABEL

const VALID: Record<FieldName, string> = {
  email: 'user@example.com',
  password: 'Miriyum1!',
  passwordConfirm: 'Miriyum1!',
  phoneNumber: '010-1234-5678',
  nickname: '미리냠',
}

function fillValidForm(overrides: Partial<Record<FieldName, string>> = {}) {
  const values = { ...VALID, ...overrides }
  for (const field of Object.keys(LABEL) as FieldName[]) {
    fireEvent.change(screen.getByLabelText(LABEL[field]), {
      target: { value: values[field] },
    })
  }
}

function checkAge() {
  fireEvent.click(screen.getByRole('checkbox', { name: /만 14세 이상입니다/ }))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '가입하기' }))
}

function createdResponse() {
  return successResponse({
    accountId: '01JBQ8Z4T7K2N9V6M3P5R8W1AC',
    accountType: 'CONSUMER',
    status: 'ACTIVE',
  })
}

describe('일반 사용자 회원가입 화면', () => {
  it('계약이 정한 여섯 필드만 보낸다', async () => {
    let body: unknown = null
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, async ({ request }) => {
        body = await request.json()
        return createdResponse()
      }),
    )

    renderSignUp()
    fillValidForm()
    checkAge()
    submit()

    await waitFor(() => expect(body).not.toBeNull())

    // 스키마가 additionalProperties: false라 임의 필드를 더하면 400이 된다.
    expect(Object.keys(body as object).sort()).toEqual([
      'ageConfirmed',
      'email',
      'nickname',
      'password',
      'passwordConfirm',
      'phoneNumber',
    ])
  })

  it('휴대전화 번호를 계약 형식으로 정규화해 보낸다', async () => {
    let body: { phoneNumber?: string } | null = null
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, async ({ request }) => {
        body = (await request.json()) as { phoneNumber: string }
        return createdResponse()
      }),
    )

    renderSignUp()
    fillValidForm({ phoneNumber: '010 1234 5678' })
    checkAge()
    submit()

    await waitFor(() => expect(body?.phoneNumber).toBe('01012345678'))
  })

  it('가입에 성공해도 자동 로그인하지 않고 로그인 화면으로 보낸다', async () => {
    server.use(http.post(CONSUMER_ACCOUNTS_PATH, () => createdResponse()))

    renderSignUp()
    fillValidForm()
    checkAge()
    submit()

    expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
  })

  it('만 14세 확인을 하지 않으면 제출하지 않는다', async () => {
    let called = false
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, () => {
        called = true
        return createdResponse()
      }),
    )

    renderSignUp()
    fillValidForm()
    submit()

    expect(
      await screen.findByText('만 14세 이상임을 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('비밀번호 확인이 다르면 해당 입력에 오류를 붙인다', async () => {
    renderSignUp()
    fillValidForm({ passwordConfirm: 'Miriyum2!' })
    checkAge()
    submit()

    await waitFor(() =>
      expect(screen.getByLabelText(LABEL.passwordConfirm)).toHaveAccessibleDescription(
        '비밀번호가 서로 다릅니다.',
      ),
    )
  })

  it('약한 비밀번호를 제출 전에 거절한다', async () => {
    renderSignUp()
    fillValidForm({ password: 'onlylowercase', passwordConfirm: 'onlylowercase' })
    checkAge()
    submit()

    expect(
      await screen.findByText(
        '대문자·소문자·숫자·특수문자 가운데 3종 이상을 포함해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('ACCOUNT_001 이메일 중복을 이메일 입력에 붙인다', async () => {
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, () =>
        errorResponse(
          409,
          AccountErrorCode.EMAIL_ALREADY_EXISTS,
          '이미 가입된 이메일입니다.',
        ),
      ),
    )

    renderSignUp()
    fillValidForm()
    checkAge()
    submit()

    await waitFor(() =>
      expect(screen.getByLabelText(LABEL.email)).toHaveAccessibleDescription(
        '이미 가입된 이메일입니다.',
      ),
    )
  })

  it('ACCOUNT_002 휴대전화 중복을 전화번호 입력에 붙인다', async () => {
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, () =>
        errorResponse(
          409,
          AccountErrorCode.PHONE_ALREADY_EXISTS,
          '이미 가입된 휴대전화 번호입니다.',
        ),
      ),
    )

    renderSignUp()
    fillValidForm()
    checkAge()
    submit()

    await waitFor(() =>
      expect(
        screen.getByLabelText(LABEL.phoneNumber),
      ).toHaveAccessibleDescription(/이미 가입된 휴대전화 번호입니다./),
    )
  })

  it('COMMON_012는 성공으로 바꾸지 않고 일시 불가로 안내한다', async () => {
    server.use(
      http.post(CONSUMER_ACCOUNTS_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 이용할 수 없습니다.'),
      ),
    )

    renderSignUp()
    fillValidForm()
    checkAge()
    submit()

    expect(
      await screen.findByText(
        '지금은 가입을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('로그인 화면')).not.toBeInTheDocument()
  })
})
