import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { AuthErrorCode } from '../../../shared/auth/authErrors'
import {
  PlatformOperatorAuthProvider,
  usePlatformOperatorAuth,
} from './PlatformOperatorAuthProvider'
import {
  PO_CSRF_PATH,
  PO_INITIAL_PASSWORD_PATH,
  PO_MEMBERS_PATH,
  PO_REFRESH_PATH,
  PO_SESSIONS_PATH,
  PO_SESSION_CURRENT_PATH,
  authenticatedPlatformOperator,
  currentPlatformOperator,
  platformOperatorTokenData,
  restrictedPlatformOperator,
  unauthenticatedPlatformOperator,
} from '../../../domains/account/platform-operator/auth/test/handlers'
import { decideCapability } from '../../../domains/account/platform-operator/auth/model/capabilities'

function Probe() {
  const {
    status,
    apiClient,
    signIn,
    changeInitialPassword,
    sessionDeadlines,
    signOut,
    signOutNotice,
    capabilities,
  } = usePlatformOperatorAuth()

  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="capability-status">
        {capabilities.status === 'unknown'
          ? capabilities.reason
          : capabilities.status}
      </p>
      <p data-testid="member-read-decision">
        {decideCapability(capabilities, 'MEMBER_READ_MINIMAL')}
      </p>
      <p data-testid="sign-out-notice">{signOutNotice ?? 'none'}</p>
      <button type="button" onClick={() => void signOut()}>
        로그아웃
      </button>
      <p data-testid="absolute-expiry">
        {sessionDeadlines?.absoluteExpiresAt ?? 'none'}
      </p>
      <button
        type="button"
        onClick={() => {
          void signIn({ email: 'op@miriyum.hq', password: 'Miriyum1!' })
        }}
      >
        로그인
      </button>
      <button
        type="button"
        onClick={() => {
          void changeInitialPassword({
            currentPassword: 'Temp1234!',
            newPassword: 'Miriyum1!',
            newPasswordConfirm: 'Miriyum1!',
          })
        }}
      >
        비밀번호 변경
      </button>
      <button
        type="button"
        onClick={() => {
          void apiClient(PO_MEMBERS_PATH, { method: 'get' }).catch(() => {})
        }}
      >
        회원 조회
      </button>
    </div>
  )
}

function renderProvider() {
  return render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <Probe />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('플랫폼 운영자 인증 shell', () => {
  beforeEach(() => {
    server.use(currentPlatformOperator())
  })

  it('세션 복구에 실패하면 비로그인이 된다', async () => {
    server.use(unauthenticatedPlatformOperator)
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
  })

  it('복구된 세션의 만료 시각을 서버 응답에서 가져온다', async () => {
    server.use(authenticatedPlatformOperator(), currentPlatformOperator())
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
    // 화면이 "15분" 같은 수치를 스스로 정하지 않고 서버 값을 쓴다.
    expect(screen.getByTestId('absolute-expiry')).toHaveTextContent(
      '2026-08-17T18:00:00Z',
    )
  })

  it('복구된 세션은 현재 운영자 API가 준 권한만 허용한다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['MEMBER_READ_MINIMAL'] }),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('capability-status')).toHaveTextContent(
        'loaded',
      ),
    )
    expect(screen.getByTestId('member-read-decision')).toHaveTextContent(
      'allowed',
    )
  })

  it('현재 운영자 조회가 실패하면 권한을 추정하지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get('/api/v1/platform-operators/me', () =>
        errorResponse(503, 'COMMON_012', '서비스를 사용할 수 없습니다.'),
      ),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('capability-status')).toHaveTextContent(
        'loadFailed',
      ),
    )
    expect(screen.getByTestId('member-read-decision')).toHaveTextContent(
      'undetermined',
    )
  })

  /**
   * 임시 비밀번호 세션을 authenticated로 취급하면 콘솔이 열린 채 모든 업무
   * 조회가 403으로 실패한다. 별도 상태로 갈라 두어야 가드가 비밀번호 변경
   * 화면으로 보낼 수 있다.
   */
  it('비밀번호 변경이 필요한 세션은 restricted로 구분한다', async () => {
    server.use(restrictedPlatformOperator)
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('restricted'),
    )
  })

  it('최초 비밀번호를 바꾸면 다시 로그인하지 않고 정상 세션이 된다', async () => {
    server.use(
      restrictedPlatformOperator,
      http.put(PO_INITIAL_PASSWORD_PATH, () =>
        successResponse(
          platformOperatorTokenData({ passwordChangeRequired: false }),
        ),
      ),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('restricted'),
    )

    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
  })

  it('로그인 응답이 변경 필요를 알리면 곧바로 restricted가 된다', async () => {
    server.use(
      unauthenticatedPlatformOperator,
      http.post(PO_SESSIONS_PATH, () =>
        successResponse(
          platformOperatorTokenData({ passwordChangeRequired: true }),
        ),
      ),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('restricted'),
    )
  })

  /**
   * 중앙 세션이 회수되면(AUTH_015) 재발급해도 같은 결과다. 재발급 대상으로
   * 두면 로그아웃된 운영자가 회전을 계속 시도한다.
   */
  it('세션 무효 401은 재발급하지 않고 세션을 끝낸다', async () => {
    let refreshCalls = 0
    server.use(
      authenticatedPlatformOperator(),
      http.post(PO_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(platformOperatorTokenData())
      }),
      http.get(PO_MEMBERS_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID,
          '플랫폼 운영자 세션이 더 이상 유효하지 않습니다.',
        ),
      ),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
    const callsAfterRestore = refreshCalls

    fireEvent.click(screen.getByRole('button', { name: '회원 조회' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    expect(refreshCalls).toBe(callsAfterRestore)
  })

  it('Access Token 만료 401은 한 번 재발급하고 재시도한다', async () => {
    let refreshCalls = 0
    let memberCalls = 0
    server.use(
      http.post(PO_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(platformOperatorTokenData())
      }),
      http.get(PO_MEMBERS_PATH, () => {
        memberCalls += 1
        if (memberCalls === 1) {
          return errorResponse(
            401,
            AuthErrorCode.ACCESS_TOKEN_EXPIRED,
            'Access Token이 만료됐습니다.',
          )
        }
        return successResponse({
          content: [],
          page: {
            number: 0,
            size: 20,
            totalElements: 0,
            totalPages: 0,
            hasNext: false,
          },
        })
      }),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
    const callsAfterRestore = refreshCalls

    fireEvent.click(screen.getByRole('button', { name: '회원 조회' }))

    await waitFor(() => expect(memberCalls).toBe(2))
    expect(refreshCalls).toBe(callsAfterRestore + 1)
    expect(screen.getByTestId('status')).toHaveTextContent('authenticated')
  })

  /**
   * 서버 폐기를 확인하지 못한 로그아웃의 경고는 provider가 들고 있어야 한다.
   *
   * 콘솔 레이아웃에 두면 렌더링되지 않는다. `signOut()`이 결과와 무관하게
   * 세션을 비우고, status가 `unauthenticated`가 되는 즉시 가드가 로그인으로
   * redirect하며 레이아웃을 unmount하기 때문이다. 실제로 그렇게 구현했다가
   * 리뷰에서 지적받았다(PR #414).
   */
  it('서버 폐기를 확인하지 못하면 세션을 비우고도 경고를 남긴다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(PO_CSRF_PATH, () =>
        successResponse({ token: 'csrf-1', headerName: 'X-CSRF-TOKEN' }),
      ),
      // 서버가 요청을 처리하지 못했다. 중앙 세션이 남아 있을 수 있다.
      http.delete(PO_SESSION_CURRENT_PATH, () =>
        errorResponse(403, AuthErrorCode.CSRF_TOKEN_INVALID, 'CSRF 검증 실패'),
      ),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    // 로컬 자격은 무조건 비운다.
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    // 그리고 경고가 남아 있어야 로그인 화면이 표시할 수 있다.
    expect(screen.getByTestId('sign-out-notice')).toHaveTextContent(
      'unconfirmed',
    )
  })

  it('서버 폐기가 확인되면 경고를 남기지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      http.get(PO_CSRF_PATH, () =>
        successResponse({ token: 'csrf-1', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(PO_SESSION_CURRENT_PATH, () => successResponse(null)),
    )
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    expect(screen.getByTestId('sign-out-notice')).toHaveTextContent('none')
  })
})
