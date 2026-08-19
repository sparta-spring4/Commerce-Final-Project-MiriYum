import type { QueryClient } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { consumerAccountKeys } from '../../../domains/account/consumer/profile'
import { pickupKeys } from '../../../domains/pickup/consumer/api/queries'
import { reservationKeys } from '../../../domains/reservation/consumer/api/queries'
import { ConsumerAuthProvider, useConsumerAuth } from './ConsumerAuthProvider'
import { AuthErrorCode } from '../../../shared/auth/authErrors'
import { CONSUMER_CSRF_COOKIE } from '../../../domains/account/consumer/auth/model/csrfCookie'
import {
  CONSUMER_CSRF_PATH,
  CONSUMER_REFRESH_PATH,
  CONSUMER_SESSIONS_PATH,
  CONSUMER_SESSION_CURRENT_PATH,
  authenticatedConsumer,
  signOutHandlers,
  tokenData,
  unauthenticatedConsumer,
} from '../../../domains/account/consumer/auth/test/handlers'

/** 보호 API 호출을 대신하는 임의 경로. 계약에 있는 경로 하나를 빌린다. */
const PROTECTED_PATH = '/api/v1/consumers/me'

function Probe() {
  const { status, apiClient, signIn, completeKakaoSignIn, signOut, signOutNotice } =
    useConsumerAuth()

  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="sign-out-notice">{signOutNotice ?? 'none'}</p>
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
          void completeKakaoSignIn('kakao-access-token')
        }}
      >
        카카오 로그인 완료
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
  let queryClient: QueryClient | null = null

  const rendered = render(
    <TestQueryProvider
      onReady={(client) => {
        queryClient = client
      }}
    >
      <ConsumerAuthProvider>
        <Probe />
      </ConsumerAuthProvider>
    </TestQueryProvider>,
  )

  if (queryClient === null) {
    throw new Error('TestQueryProvider가 QueryClient를 넘겨주지 않았습니다.')
  }

  return { ...rendered, queryClient: queryClient as QueryClient }
}

/** 보호 데이터가 캐시에 남아 있는 상태를 만든다. */
function seedProtectedCache(queryClient: QueryClient, owner: string) {
  queryClient.setQueryData(consumerAccountKeys.me(), { nickname: owner })
  queryClient.setQueryData(
    ['consumer', 'notification-history', 0],
    { items: [{ title: owner }] },
  )
  queryClient.setQueryData(reservationKeys.detail('r-1'), { storeName: owner })
  queryClient.setQueryData(pickupKeys.detail('p-1'), { storeName: owner })
}

function cachedOwners(queryClient: QueryClient): unknown[] {
  return [
    queryClient.getQueryData(consumerAccountKeys.me()),
    queryClient.getQueryData(['consumer', 'notification-history', 0]),
    queryClient.getQueryData(reservationKeys.detail('r-1')),
    queryClient.getQueryData(pickupKeys.detail('p-1')),
  ]
}

function status() {
  return screen.getByTestId('status').textContent
}

function signOutNotice() {
  return screen.getByTestId('sign-out-notice').textContent
}

/** 응답을 테스트가 원하는 순간까지 붙잡아 경합 구간을 만든다. */
function deferred() {
  let resolve = () => {}
  const promise = new Promise<void>((settle) => {
    resolve = settle
  })
  return { promise, resolve: () => resolve() }
}

afterEach(() => {
  document.cookie = `${CONSUMER_CSRF_COOKIE}=; Max-Age=0; path=/`
  vi.restoreAllMocks()
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

  /*
   * 서버 폐기 실패를 성공과 같은 화면으로 끝내지 않는다.
   *
   * 계약이 이 endpoint의 403을 CsrfRejected(AUTH_009)로 정의한다. 서버가
   * 요청을 처리하지 않았다는 뜻이므로 세션은 그대로 살아 있다.
   */
  it('CSRF 거절은 로컬만 비우고 서버 폐기 미확인으로 남긴다', async () => {
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

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(signOutNotice()).toBe('unconfirmed')
  })

  it('서버 오류는 폐기 성공을 뜻하지 않으므로 미확인으로 남긴다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 이용할 수 없습니다.'),
      ),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(signOutNotice()).toBe('unconfirmed')
  })

  it('폐기할 세션이 없다는 응답은 미확인이 아니다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.REFRESH_TOKEN_INVALID,
          'Refresh Token이 올바르지 않습니다.',
        ),
      ),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    // 폐기할 것이 없었을 뿐 세션이 남지는 않는다. 겁줄 이유가 없다.
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(signOutNotice()).toBe('none')
  })

  /*
   * 조용한 생략을 없앤다.
   *
   * 쿠키를 읽지 못한다고 요청을 건너뛰면 화면만 로그아웃되고 서버 Refresh
   * family는 살아 있는데 아무 신호도 남지 않는다. 준비 응답의 token이 쿠키와
   * 같은 값이므로 그 값으로 헤더를 채워 실제로 보낸다.
   */
  it('CSRF 쿠키를 읽지 못해도 로그아웃 요청을 건너뛰지 않는다', async () => {
    // 쿠키를 심지 않는다. readCookie가 null을 돌려주는 상황이다.
    let deleteCalls = 0
    let sentCsrfHeader: string | null = null

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({
          token: 'token-from-body',
          headerName: 'X-CSRF-TOKEN',
        }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, ({ request }) => {
        deleteCalls += 1
        sentCsrfHeader = request.headers.get('X-CSRF-TOKEN')
        return successResponse(null)
      }),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(deleteCalls).toBe(1)
    expect(sentCsrfHeader).toBe('token-from-body')
    expect(signOutNotice()).toBe('none')
  })

  /*
   * 세션 되살아남 회귀.
   *
   * 재발급이 떠 있는 동안 로그아웃하면, 늦게 도착한 응답이 Access Token과
   * `authenticated`를 다시 써서 세션이 되살아난다. 공용 PC에서 로그아웃하고
   * 자리를 뜬 뒤에 벌어지는 일이라 화면만 로그아웃된 것보다 나쁘다.
   */
  it('로그아웃 뒤 늦게 도착한 재발급 결과로 세션이 되살아나지 않는다', async () => {
    const refresh = deferred()
    let authorization: string | null = 'not-called'

    server.use(
      http.post(CONSUMER_REFRESH_PATH, async () => {
        await refresh.promise
        return successResponse(tokenData('late-token'))
      }),
      ...signOutHandlers(),
      http.get(PROTECTED_PATH, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return successResponse(null)
      }),
    )

    renderProvider()

    // 복구 재발급이 응답을 기다리는 동안 로그아웃한다.
    await waitFor(() => expect(status()).toBe('restoring'))
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    refresh.resolve()

    // 늦게 온 토큰이 상태를 되돌리지 않는다.
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    expect(status()).toBe('unauthenticated')

    // 토큰도 남지 않아 보호 API에 실리지 않는다.
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(authorization).toBeNull())
  })

  /*
   * 이전 세션의 재발급을 새 세션이 물려받지 않는다.
   *
   * `refreshInFlight`를 비우지 않으면 세션 B의 401이 아직 끝나지 않은 세션 A의
   * 재발급 promise를 그대로 받는다. 그 결과는 세대 확인에 걸려 버려지므로
   * B의 요청은 재발급을 시도해 보지도 못하고 실패한다.
   */
  it('로그아웃하면 진행 중이던 재발급을 새 세션이 물려받지 않는다', async () => {
    const first = deferred()
    let refreshCalls = 0

    server.use(
      http.post(CONSUMER_REFRESH_PATH, async () => {
        refreshCalls += 1
        // 첫 재발급(세션 A 복구)만 붙잡아 둔다.
        if (refreshCalls === 1) {
          await first.promise
          return successResponse(tokenData('session-a'))
        }
        return successResponse(tokenData(`session-b-${refreshCalls}`))
      }),
      ...signOutHandlers(),
      http.post(CONSUMER_SESSIONS_PATH, () =>
        successResponse(tokenData('b-token')),
      ),
    )

    server.use(
      http.get(PROTECTED_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.ACCESS_TOKEN_EXPIRED,
          'Access Token이 만료됐습니다.',
        ),
      ),
    )

    renderProvider()
    await waitFor(() => expect(refreshCalls).toBe(1))

    // A의 재발급(R1)이 아직 떠 있는 상태로 로그아웃하고 B로 다시 로그인한다.
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() => expect(status()).toBe('authenticated'))

    /*
     * R1을 아직 풀지 않은 채로 B의 보호 API가 401을 받는다.
     *
     * `refreshInFlight`를 비우지 않았다면 여기서 R1을 그대로 물려받아 기다리므로
     * 두 번째 재발급이 아예 시작되지 않는다. 비웠다면 곧바로 새 재발급이 뜬다.
     */
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(refreshCalls).toBe(2))

    first.resolve()
    await waitFor(() => expect(status()).toBe('authenticated'))
  })

  /*
   * 늦게 끝난 이전 재발급이 현재 재발급의 자리를 지우지 않는다.
   *
   * `finally`가 무조건 비우면 R1이 늦게 끝나면서 R2의 참조까지 지운다. 그러면
   * 다음 401이 R3를 R2와 나란히 띄우고, Refresh 토큰 회전 구성에서는 둘 중
   * 하나가 토큰을 먼저 써 버려 나머지가 실패하며 살아 있는 세션이 끊긴다.
   */
  it('늦게 끝난 이전 재발급이 현재 재발급을 지우지 않는다', async () => {
    const first = deferred()
    const second = deferred()
    let refreshCalls = 0

    server.use(
      http.post(CONSUMER_REFRESH_PATH, async () => {
        refreshCalls += 1
        if (refreshCalls === 1) {
          await first.promise
          return successResponse(tokenData('session-a'))
        }
        // R2는 테스트가 풀 때까지 떠 있는다.
        await second.promise
        return successResponse(tokenData('session-b'))
      }),
      ...signOutHandlers(),
      http.post(CONSUMER_SESSIONS_PATH, () =>
        successResponse(tokenData('b-token')),
      ),
      http.get(PROTECTED_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.ACCESS_TOKEN_EXPIRED,
          'Access Token이 만료됐습니다.',
        ),
      ),
    )

    renderProvider()
    await waitFor(() => expect(refreshCalls).toBe(1))

    // 세션 전환. R1은 아직 떠 있다.
    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() => expect(status()).toBe('authenticated'))

    // B의 401이 R2를 띄운다.
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(refreshCalls).toBe(2))

    // R1이 뒤늦게 끝난다. R2의 자리를 지우면 안 된다.
    first.resolve()
    await waitFor(() => expect(refreshCalls).toBe(2))

    // 세 번째 401은 R2를 공유해야 한다. R3가 병렬로 뜨면 토큰 회전이 깨진다.
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(refreshCalls).toBe(2)

    second.resolve()
  })

  it('다시 로그인하면 지난 로그아웃 안내를 지운다', async () => {
    document.cookie = `${CONSUMER_CSRF_COOKIE}=csrf-value; path=/`

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_CSRF_PATH, () =>
        successResponse({ token: 'csrf-value', headerName: 'X-CSRF-TOKEN' }),
      ),
      http.delete(CONSUMER_SESSION_CURRENT_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 이용할 수 없습니다.'),
      ),
      http.post(CONSUMER_SESSIONS_PATH, () =>
        successResponse(tokenData('next-token')),
      ),
    )

    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(signOutNotice()).toBe('unconfirmed'))

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(status()).toBe('authenticated'))
    expect(signOutNotice()).toBe('none')
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

  /*
   * 계정 전환 회귀.
   *
   * query key에 계정 식별자가 없다. 세션이 끝날 때 캐시를 비우지 않으면 같은
   * 탭에서 다음 사용자가 로그인했을 때 이전 사용자의 프로필·예약이 먼저 그려진다.
   */
  it('로그아웃하면 이전 사용자의 보호 데이터를 캐시에서 지운다', async () => {
    server.use(authenticatedConsumer(), ...signOutHandlers())

    const { queryClient } = renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    seedProtectedCache(queryClient, 'A')
    expect(cachedOwners(queryClient)).not.toContain(undefined)

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    await waitFor(() =>
      expect(cachedOwners(queryClient)).toEqual([
        undefined,
        undefined,
        undefined,
        undefined,
      ]),
    )
  })

  it('재발급할 수 없는 401로 세션이 끊겨도 보호 데이터를 지운다', async () => {
    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => successResponse(tokenData())),
      http.get(PROTECTED_PATH, () =>
        errorResponse(
          401,
          AuthErrorCode.TOKEN_NAMESPACE_MISMATCH,
          '토큰 namespace가 일치하지 않습니다.',
        ),
      ),
    )

    const { queryClient } = renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    seedProtectedCache(queryClient, 'A')
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))

    // 로그아웃을 거치지 않고 끊긴 세션도 같은 정리를 거쳐야 한다.
    await waitFor(() => expect(status()).toBe('unauthenticated'))
    await waitFor(() =>
      expect(cachedOwners(queryClient)).toEqual([
        undefined,
        undefined,
        undefined,
        undefined,
      ]),
    )
  })

  it('다른 계정으로 로그인하면 이전 계정의 캐시를 넘겨받지 않는다', async () => {
    server.use(
      unauthenticatedConsumer,
      http.post(CONSUMER_SESSIONS_PATH, () =>
        successResponse(tokenData('b-token')),
      ),
    )

    const { queryClient } = renderProvider()
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    // A가 남기고 간 캐시.
    seedProtectedCache(queryClient, 'A')

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() => expect(status()).toBe('authenticated'))

    expect(cachedOwners(queryClient)).toEqual([
      undefined,
      undefined,
      undefined,
      undefined,
    ])
  })

  it('카카오 로그인 완료도 인증 상태 전환 전에 이전 보호 캐시를 지운다', async () => {
    server.use(unauthenticatedConsumer)

    const { queryClient } = renderProvider()
    await waitFor(() => expect(status()).toBe('unauthenticated'))

    seedProtectedCache(queryClient, '이전 사용자')
    const cacheClear = deferred()
    vi.spyOn(queryClient, 'cancelQueries').mockImplementation(() => cacheClear.promise)

    fireEvent.click(screen.getByRole('button', { name: '카카오 로그인 완료' }))

    await waitFor(() => expect(queryClient.cancelQueries).toHaveBeenCalledTimes(4))
    expect(status()).toBe('unauthenticated')
    expect(cachedOwners(queryClient)).not.toContain(undefined)

    cacheClear.resolve()
    await waitFor(() => expect(status()).toBe('authenticated'))
    expect(cachedOwners(queryClient)).toEqual([
      undefined,
      undefined,
      undefined,
      undefined,
    ])
  })
})
