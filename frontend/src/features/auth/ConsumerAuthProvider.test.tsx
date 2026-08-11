import { render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import { server } from '../../test/msw/server'
import { ConsumerAuthProvider, useConsumerAuth } from './ConsumerAuthProvider'
import { AuthErrorCode } from './model/authErrors'
import { CONSUMER_CSRF_COOKIE } from './model/csrfCookie'
import {
  CONSUMER_CSRF_PATH,
  CONSUMER_REFRESH_PATH,
  CONSUMER_SESSIONS_PATH,
  CONSUMER_SESSION_CURRENT_PATH,
  authenticatedConsumer,
  signOutHandlers,
  tokenData,
  unauthenticatedConsumer,
} from './test/handlers'

/** 보호 API 호출을 대신하는 임의 경로. 계약에 있는 경로 하나를 빌린다. */
const PROTECTED_PATH = '/api/v1/consumers/me'

function Probe() {
  const { status, apiClient, signIn, signOut } = useConsumerAuth()

  return (
    <div>
      <p data-testid="status">{status}</p>
      <button
        type="button"
        onClick={() => {
          void signIn({ email: 'user@example.com', password: 'Miriyum1!' })
        }}
      >
        로그인
      </button>
      <button type="button" onClick={() => void signOut()}>
        로그아웃
      </button>
      <button
        type="button"
        onClick={() => {
          void apiClient(PROTECTED_PATH, { method: 'get' }).catch(() => {})
        }}
      >
        보호 API 호출
      </button>
    </div>
  )
}

function renderProvider() {
  return render(
    <ConsumerAuthProvider>
      <Probe />
    </ConsumerAuthProvider>,
  )
}

function status() {
  return screen.getByTestId('status').textContent
}

afterEach(() => {
  document.cookie = `${CONSUMER_CSRF_COOKIE}=; Max-Age=0; path=/`
})

describe('일반 사용자 인증 shell', () => {
  it('새로고침 후 재발급으로 세션을 복구한다', async () => {
    server.use(authenticatedConsumer())

    renderProvider()

    await waitFor(() => expect(status()).toBe('authenticated'))
  })

  it('재발급이 실패하면 오류가 아니라 비로그인 상태로 되돌린다', async () => {
    server.use(unauthenticatedConsumer)

    renderProvider()

    await waitFor(() => expect(status()).toBe('unauthenticated'))
  })

  it('복구가 끝나기 전에는 restoring 상태를 유지한다', () => {
    server.use(unauthenticatedConsumer)

    renderProvider()

    // 첫 렌더에서 미인증으로 확정하면 보호 화면이 잠깐 로그인으로 튕긴다.
    expect(status()).toBe('restoring')
  })

  it('로그인하면 Access Token을 메모리에 담고 인증 상태로 바꾼다', async () => {
    let authorization: string | null = null
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () =>
        successResponse(tokenData('fresh-token')),
      ),
      http.get(PROTECTED_PATH, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    screen.getByRole('button', { name: '로그인' }).click()
    await waitFor(() => expect(status()).toBe('authenticated'))

    screen.getByRole('button', { name: '보호 API 호출' }).click()
    await waitFor(() => expect(authorization).toBe('Bearer fresh-token'))
  })

  it('Access Token을 Web Storage와 쿠키에 기록하지 않는다', async () => {
    server.use(authenticatedConsumer('secret-access-token'))

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    expect(JSON.stringify(localStorage)).not.toContain('secret-access-token')
    expect(JSON.stringify(sessionStorage)).not.toContain('secret-access-token')
    expect(document.cookie).not.toContain('secret-access-token')
  })

  it('AUTH_002 만료는 한 번 재발급한 뒤 원 요청을 재시도한다', async () => {
    let protectedCalls = 0
    let refreshCalls = 0

    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(tokenData(`token-${refreshCalls}`))
      }),
      http.get(PROTECTED_PATH, () => {
        protectedCalls += 1
        if (protectedCalls === 1) {
          return errorResponse(
            401,
            AuthErrorCode.ACCESS_TOKEN_EXPIRED,
            'Access Token이 만료됐습니다.',
          )
        }
        return successResponse(null)
      }),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))
    expect(refreshCalls).toBe(1)

    screen.getByRole('button', { name: '보호 API 호출' }).click()

    await waitFor(() => expect(protectedCalls).toBe(2))
    expect(refreshCalls).toBe(2)
    expect(status()).toBe('authenticated')
  })

  it('AUTH_004 namespace 불일치는 재발급하지 않고 세션을 끊는다', async () => {
    let refreshCalls = 0

    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(tokenData())
      }),
      http.get(PROTECTED_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.TOKEN_NAMESPACE_MISMATCH,
          '토큰 namespace가 일치하지 않습니다.',
        ),
      ),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))
    expect(refreshCalls).toBe(1)

    screen.getByRole('button', { name: '보호 API 호출' }).click()

    // 다른 shell의 토큰을 재발급으로 이어 붙이면 shell 분리가 깨진다.
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(refreshCalls).toBe(1)
  })

  it('로그아웃은 CSRF 값을 헤더로 되돌려 보낸다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`
    let sentCsrf: string | null = null

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, ({ request }) => {
        sentCsrf = request.headers.get('X-CSRF-TOKEN')
        return successResponse(null)
      }),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    screen.getByRole('button', { name: '로그아웃' }).click()

    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(sentCsrf).toBe('csrf-value')
  })

  it('서버 로그아웃이 실패해도 클라이언트 상태를 비운다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, () =>
        errorResponse(
          403,
          AuthErrorCode.CSRF_TOKEN_INVALID,
          'CSRF 검증에 실패했습니다.',
        ),
      ),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    screen.getByRole('button', { name: '로그아웃' }).click()

    await waitFor(() => expect(status()).toBe('unauthenticated'))
  })

  it('로그아웃 뒤 보호 API에 이전 토큰을 보내지 않는다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`
    let authorization: string | null = 'not-called'

    server.use(
      authenticatedConsumer('old-token'),
      ...signOutHandlers(),
      http.get(PROTECTED_PATH, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return errorResponse(
          401,
          AuthErrorCode.ACCESS_TOKEN_REQUIRED,
          'Access Token이 필요합니다.',
        )
      }),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    screen.getByRole('button', { name: '로그아웃' }).click()
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    screen.getByRole('button', { name: '보호 API 호출' }).click()
    await waitFor(() => expect(authorization).toBeNull())
  })
})
