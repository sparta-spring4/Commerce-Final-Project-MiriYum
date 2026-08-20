import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { PUBLIC_PATHS } from '../../../../../app/routes/paths/publicPaths'
import { STORE_OPERATOR_PATHS } from '../../../../../app/routes/paths/storeOperatorPaths'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../../../../test/msw/envelope'
import { server } from '../../../../../test/msw/server'
import { TestQueryProvider } from '../../../../../test/TestQueryProvider'
import { ConsumerAuthProvider } from '../../../../../app/shells/consumer/ConsumerAuthProvider'
import { AuthErrorCode } from '../../../../../shared/auth/authErrors'
import { browserRedirect, KAKAO_CALLBACK_PATH } from '../model/kakaoOAuth'
import {
  CONSUMER_KAKAO_AUTHORIZATIONS_PATH,
  CONSUMER_SESSIONS_PATH,
  tokenData,
  unauthenticatedConsumer,
} from '../test/handlers'
import { ConsumerSignInPage } from './ConsumerSignInPage'

function LocationProbe() {
  const { pathname, search } = useLocation()
  return <p data-testid="location">{`${pathname}${search}`}</p>
}

function renderSignInAt(route: string = CONSUMER_PATHS.signIn) {
  return render(
    <TestQueryProvider>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route
              path={CONSUMER_PATHS.signIn}
              element={<ConsumerSignInPage />}
            />
            <Route path={PUBLIC_PATHS.home} element={<LocationProbe />} />
            <Route path="/mypage" element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )
}

function fillCredentials(
  email = 'user@example.com',
  password = 'Miriyum1!',
) {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('비밀번호'), {
    target: { value: password },
  })
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '로그인' }))
}

describe('일반 사용자 로그인 화면', () => {
  it('제출 전에 이메일 형식을 검증하고 서버를 부르지 않는다', async () => {
    let called = false
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () => {
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

  it('오류 문구를 입력과 프로그램적으로 연결한다', async () => {
    server.use(unauthenticatedConsumer)

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials('not-an-email')
    submit()

    await waitFor(() =>
      expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription(
        '이메일 형식으로 입력해 주세요.',
      ),
    )
  })

  it('AUTH_005는 계정 존재 여부를 나누지 않고 통합 문구로 안내한다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () =>
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
    // 어떤 쪽이 틀렸는지 알려 주면 가입 여부 확인 통로가 된다.
    expect(screen.queryByText(/가입되지 않은/)).not.toBeInTheDocument()
  })

  it('AUTH_011 계정 제한은 자격 오류와 다른 문구로 안내한다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () =>
        errorResponse(
          403,
          AuthErrorCode.ACCOUNT_RESTRICTED,
          '현재 계정 상태로는 이용할 수 없습니다.',
        ),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    expect(
      await screen.findByText(
        '현재 계정 상태로는 로그인할 수 없습니다. 고객센터에 문의해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('요청 제한을 이해 가능한 문구로 안내한다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () =>
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

  it('로그인에 성공하면 보존한 목적지로 복귀한다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () => successResponse(tokenData())),
    )

    renderSignInAt(`${CONSUMER_PATHS.signIn}?returnTo=%2Fmypage%3Ftab%3Dlist`)
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/mypage?tab=list',
      ),
    )
  })

  it('보존한 목적지가 외부 오리진이면 홈으로 보낸다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () => successResponse(tokenData())),
    )

    renderSignInAt(
      `${CONSUMER_PATHS.signIn}?returnTo=https%3A%2F%2Fevil.example%2Fsteal`,
    )
    await screen.findByLabelText('이메일')

    fillCredentials()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/'),
    )
    expect(screen.getByTestId('location')).not.toHaveTextContent('evil.example')
  })

  it('매장 운영자 로그인은 같은 폼이 아니라 별도 진입점으로 보낸다', async () => {
    server.use(unauthenticatedConsumer)

    renderSignInAt()

    expect(
      await screen.findByRole('link', { name: '식당 대표자 로그인' }),
    ).toHaveAttribute('href', STORE_OPERATOR_PATHS.signIn)
    // 한 폼에서 역할을 골라 다른 shell 권한을 열지 않는다.
    expect(screen.queryByLabelText('계정 유형')).not.toBeInTheDocument()
  })
})

describe('일반 사용자 카카오 로그인 시작', () => {
  // jsdom에는 전체 이동 구현이 없다. 이 이음새만 대체하고 API 호출은 실제로 보낸다.
  function stubRedirect() {
    return vi.spyOn(browserRedirect, 'assign').mockImplementation(() => {})
  }

  function clickKakao() {
    fireEvent.click(screen.getByRole('button', { name: '카카오 로그인' }))
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('서버가 발급한 인가 주소로 이동하고 콜백 주소를 함께 보낸다', async () => {
    const assign = stubRedirect()
    const authorizationUrl =
      'https://kauth.kakao.com/oauth/authorize?client_id=rest-key&redirect_uri=x&response_type=code&state=signed-state'
    let requestBody: unknown = null
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_KAKAO_AUTHORIZATIONS_PATH, async ({ request }) => {
        requestBody = await request.json()
        return successResponse({ authorizationUrl })
      }),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    clickKakao()

    await waitFor(() => expect(assign).toHaveBeenCalledWith(authorizationUrl))
    // 인가 주소는 서버가 만든다. client_id·state를 프론트가 조립하지 않는다.
    expect(requestBody).toEqual({
      redirectUri: `${window.location.origin}${KAKAO_CALLBACK_PATH}`,
    })
  })

  it('이메일·비밀번호를 비워 둬도 카카오 로그인을 시작한다', async () => {
    const assign = stubRedirect()
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_KAKAO_AUTHORIZATIONS_PATH, () =>
        successResponse({ authorizationUrl: 'https://kauth.kakao.com/x' }),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    // 비밀번호 폼 검증이 다른 로그인 수단을 막으면 안 된다.
    clickKakao()

    await waitFor(() => expect(assign).toHaveBeenCalled())
    expect(
      screen.queryByText('이메일 형식으로 입력해 주세요.'),
    ).not.toBeInTheDocument()
  })

  it('카카오 로그인을 쓸 수 없는 상태면 이동하지 않고 안내한다', async () => {
    const assign = stubRedirect()
    server.use(
      unauthenticatedConsumer,
      // 서버는 카카오 연동이 꺼져 있거나 콜백 주소가 허용 목록에 없으면 COMMON_012로 막는다.
      http.post(CONSUMER_KAKAO_AUTHORIZATIONS_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 이용할 수 없습니다.'),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    clickKakao()

    expect(
      await screen.findByText(
        '카카오 로그인을 지금 이용할 수 없습니다. 다른 방법으로 로그인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    // 실패한 뒤에는 다시 누를 수 있어야 한다.
    expect(screen.getByRole('button', { name: '카카오 로그인' })).toBeEnabled()
  })

  it('요청 제한은 이용 불가와 다른 문구로 안내한다', async () => {
    stubRedirect()
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_KAKAO_AUTHORIZATIONS_PATH, () =>
        errorResponse(429, 'COMMON_010', '요청이 많습니다.'),
      ),
    )

    renderSignInAt()
    await screen.findByLabelText('이메일')

    clickKakao()

    expect(
      await screen.findByText(
        '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
  })
})
