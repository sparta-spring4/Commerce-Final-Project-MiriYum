import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import { server } from '../../test/msw/server'
import { TestQueryProvider } from '../../test/TestQueryProvider'
import { AuthErrorCode } from '../auth/model/authErrors'
import {
  PlatformOperatorAuthProvider,
  usePlatformOperatorAuth,
} from './PlatformOperatorAuthProvider'
import {
  PO_INITIAL_PASSWORD_PATH,
  PO_MEMBERS_PATH,
  PO_REFRESH_PATH,
  PO_SESSIONS_PATH,
  authenticatedPlatformOperator,
  platformOperatorTokenData,
  restrictedPlatformOperator,
  unauthenticatedPlatformOperator,
} from './test/handlers'

function Probe() {
  const { status, apiClient, signIn, changeInitialPassword, sessionDeadlines } =
    usePlatformOperatorAuth()

  return (
    <div>
      <p data-testid="status">{status}</p>
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
  it('세션 복구에 실패하면 비로그인이 된다', async () => {
    server.use(unauthenticatedPlatformOperator)
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
  })

  it('복구된 세션의 만료 시각을 서버 응답에서 가져온다', async () => {
    server.use(authenticatedPlatformOperator())
    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
    // 화면이 "15분" 같은 수치를 스스로 정하지 않고 서버 값을 쓴다.
    expect(screen.getByTestId('absolute-expiry')).toHaveTextContent(
      '2026-08-17T18:00:00Z',
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
})
