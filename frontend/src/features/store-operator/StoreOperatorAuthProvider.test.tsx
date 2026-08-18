import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import { server } from '../../test/msw/server'
import { CONSUMER_REFRESH_PATH } from '../auth/test/handlers'
import * as storeOperatorAuthApi from './api/storeOperatorAuthApi'
import {
  StoreOperatorAuthProvider,
  useStoreOperatorAuth,
} from './StoreOperatorAuthProvider'
import {
  OPERATOR_CSRF_PATH,
  OPERATOR_REFRESH_PATH,
  OPERATOR_SESSIONS_PATH,
  OPERATOR_SESSION_CURRENT_PATH,
  authenticatedOperator,
  managedStore,
  operatorStorePath,
  tokenData,
  unauthenticatedOperator,
} from './test/handlers'

function Probe() {
  const { status, apiClient, signIn, signOut, signOutPending } =
    useStoreOperatorAuth()
  const [signInSettled, setSignInSettled] = useState(false)
  const [signOutSettled, setSignOutSettled] = useState(false)
  const [protectedRequestSettled, setProtectedRequestSettled] = useState(false)

  return (
    <div>
      <p data-testid="status">{status}</p>
      <p data-testid="sign-out-pending">{String(signOutPending)}</p>
      <p data-testid="sign-in-settled">{String(signInSettled)}</p>
      <p data-testid="sign-out-settled">{String(signOutSettled)}</p>
      <p data-testid="protected-request-settled">
        {String(protectedRequestSettled)}
      </p>
      <button
        type="button"
        onClick={() => {
          void signIn({ email: 'a@b.co', password: 'Miriyum1!' })
            .catch(() => undefined)
            .finally(() => setSignInSettled(true))
        }}
      >
        로그인
      </button>
      <button
        type="button"
        onClick={() => {
          void signOut()
            .catch(() => undefined)
            .finally(() => setSignOutSettled(true))
        }}
      >
        로그아웃
      </button>
      <button
        type="button"
        onClick={() => {
          // 401 판정을 보는 테스트라 실패가 정상 경로다. 삼켜서 unhandled
          // rejection으로 다른 테스트를 오염시키지 않게 한다.
          apiClient('/api/v1/store-operators/stores/{storeId}', {
            method: 'get',
            pathParams: { storeId: '7' },
          })
            .catch(() => undefined)
            .finally(() => setProtectedRequestSettled(true))
        }}
      >
        보호 API 호출
      </button>
    </div>
  )
}

function renderProvider() {
  return render(
    <StoreOperatorAuthProvider>
      <Probe />
    </StoreOperatorAuthProvider>,
  )
}

/** 비동기 결과를 테스트가 원하는 순간까지 붙잡아 인증 경합을 재현한다. */
function deferred<T>() {
  let resolve = (_value: T) => {}
  let reject = (_reason: unknown) => {}
  const promise = new Promise<T>((settle, fail) => {
    resolve = settle
    reject = fail
  })
  return { promise, resolve, reject }
}

afterEach(() => vi.restoreAllMocks())

describe('매장 운영자 인증 shell', () => {
  it('일반 사용자 재발급 경로를 부르지 않는다', async () => {
    let consumerCalled = false
    server.use(
      http.post(CONSUMER_REFRESH_PATH, () => {
        consumerCalled = true
        return successResponse(tokenData())
      }),
      unauthenticatedOperator,
    )

    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    // namespace를 공유하면 한쪽 셸의 세션으로 다른 쪽 화면이 열린다.
    expect(consumerCalled).toBe(false)
  })

  it('새로고침 직후 재발급으로 세션을 복구한다', async () => {
    server.use(authenticatedOperator())

    renderProvider()

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
  })

  it('복구가 끝나기 전에는 restoring 상태를 유지한다', () => {
    server.use(unauthenticatedOperator)

    renderProvider()

    expect(screen.getByTestId('status')).toHaveTextContent('restoring')
  })

  it('로그인에 성공하면 보호 API에 Access Token을 붙인다', async () => {
    let authorization: string | null = null
    server.use(
      unauthenticatedOperator,
      http.post(OPERATOR_SESSIONS_PATH, () =>
        successResponse(tokenData('fresh-operator-token')),
      ),
      http.get(operatorStorePath(), ({ request }) => {
        authorization = request.headers.get('authorization')
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() =>
      expect(authorization).toBe('Bearer fresh-operator-token'),
    )
  })

  it('초기 재발급과 로그인 요청을 순서대로 처리한다', async () => {
    const refresh = deferred<
      Awaited<ReturnType<typeof storeOperatorAuthApi.refreshStoreOperatorToken>>
    >()
    let refreshStarted = false
    const authorizations: Array<string | null> = []

    vi.spyOn(storeOperatorAuthApi, 'refreshStoreOperatorToken').mockImplementation(
      () => {
        refreshStarted = true
        return refresh.promise
      },
    )

    server.use(
      http.post(OPERATOR_SESSIONS_PATH, () =>
        successResponse(tokenData('fresh-operator-token')),
      ),
      http.get(operatorStorePath(), ({ request }) => {
        authorizations.push(request.headers.get('authorization'))
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    expect(screen.getByTestId('status')).toHaveTextContent('restoring')
    await waitFor(() => expect(refreshStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    refresh.reject(new Error('초기 재발급 실패'))
    await refresh.promise.catch(() => undefined)
    await waitFor(() =>
      expect(screen.getByTestId('sign-in-settled')).toHaveTextContent('true'),
    )
    expect(screen.getByTestId('status')).toHaveTextContent('authenticated')

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(authorizations).toHaveLength(1))
    expect(authorizations[0]).toBe('Bearer fresh-operator-token')
  })

  it('복구 중 로그인이 실패하면 restoring에 머물지 않는다', async () => {
    const refresh = deferred<
      Awaited<ReturnType<typeof storeOperatorAuthApi.refreshStoreOperatorToken>>
    >()
    let refreshStarted = false

    vi.spyOn(storeOperatorAuthApi, 'refreshStoreOperatorToken').mockImplementation(
      () => {
        refreshStarted = true
        return refresh.promise
      },
    )

    server.use(
      http.post(OPERATOR_SESSIONS_PATH, () =>
        errorResponse(401, 'AUTH_005', '이메일 또는 비밀번호가 올바르지 않습니다.'),
      ),
    )

    renderProvider()
    await waitFor(() => expect(refreshStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    refresh.resolve({
      accessToken: 'stale-initial-token',
      tokenType: 'Bearer',
      expiresIn: 900,
    })
    await refresh.promise
    await waitFor(() =>
      expect(screen.getByTestId('sign-in-settled')).toHaveTextContent('true'),
    )

    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')
  })

  it('로그인 실패 뒤 도착한 401이 세션을 되살리지 않는다', async () => {
    const signInFailure = deferred<void>()
    const protectedResponse = deferred<void>()
    let signInStarted = false
    let protectedRequestStarted = false
    let refreshCalls = 0

    server.use(
      http.post(OPERATOR_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(
          tokenData(
            refreshCalls === 1
              ? 'old-operator-token'
              : 'resurrected-operator-token',
          ),
        )
      }),
      http.post(OPERATOR_SESSIONS_PATH, async () => {
        signInStarted = true
        await signInFailure.promise
        return errorResponse(
          401,
          'AUTH_005',
          '이메일 또는 비밀번호가 올바르지 않습니다.',
        )
      }),
      http.get(operatorStorePath(), async () => {
        protectedRequestStarted = true
        await protectedResponse.promise
        return errorResponse(401, 'AUTH_002', 'Access Token이 만료되었습니다.')
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    await waitFor(() => expect(signInStarted).toBe(true))
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(protectedRequestStarted).toBe(true))

    signInFailure.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('sign-in-settled')).toHaveTextContent('true'),
    )
    protectedResponse.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('protected-request-settled')).toHaveTextContent(
        'true',
      ),
    )

    expect(refreshCalls).toBe(1)
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')
  })

  it('만료 401은 재발급하고 같은 요청을 다시 보낸다', async () => {
    let attempts = 0
    server.use(
      authenticatedOperator('first-token'),
      http.get(operatorStorePath(), () => {
        attempts += 1
        if (attempts === 1) {
          return errorResponse(401, 'AUTH_002', 'Access Token이 만료되었습니다.')
        }
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))

    await waitFor(() => expect(attempts).toBe(2))
    expect(screen.getByTestId('status')).toHaveTextContent('authenticated')
  })

  it('namespace 불일치 401은 재발급하지 않고 세션을 비운다', async () => {
    let refreshCalls = 0
    server.use(
      http.post(OPERATOR_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(tokenData())
      }),
      http.get(operatorStorePath(), () =>
        errorResponse(401, 'AUTH_004', '토큰 namespace가 일치하지 않습니다.'),
      ),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )
    expect(refreshCalls).toBe(1)

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    // 다른 셸의 토큰을 조용히 이어 붙이지 않는다.
    expect(refreshCalls).toBe(1)
  })

  it('로그아웃은 운영자 namespace 경로만 호출한다', async () => {
    const called: string[] = []
    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'

    server.use(
      authenticatedOperator(),
      http.get(OPERATOR_CSRF_PATH, () => {
        called.push('csrf')
        return successResponse({ token: 'operator-csrf', headerName: 'X-CSRF-TOKEN' })
      }),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, ({ request }) => {
        called.push(request.headers.get('x-csrf-token') ?? 'no-header')
        return successResponse(null)
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    expect(called).toEqual(['csrf', 'operator-csrf'])
  })

  it('로그아웃 뒤 늦게 성공한 초기 재발급이 세션을 되살리지 않는다', async () => {
    const refresh = deferred<
      Awaited<ReturnType<typeof storeOperatorAuthApi.refreshStoreOperatorToken>>
    >()
    let refreshStarted = false
    const authorizations: Array<string | null> = []

    vi.spyOn(storeOperatorAuthApi, 'refreshStoreOperatorToken').mockImplementation(
      () => {
        refreshStarted = true
        return refresh.promise
      },
    )

    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'
    server.use(
      http.get(OPERATOR_CSRF_PATH, () =>
        successResponse({
          token: 'operator-csrf',
          headerName: 'X-CSRF-TOKEN',
        }),
      ),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => successResponse(null)),
      http.get(operatorStorePath(), ({ request }) => {
        authorizations.push(request.headers.get('authorization'))
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    await waitFor(() => expect(refreshStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )

    refresh.resolve({
      accessToken: 'late-operator-token',
      tokenType: 'Bearer',
      expiresIn: 900,
    })
    await refresh.promise

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(authorizations).toHaveLength(1))
    expect(authorizations).toEqual([null])
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')
  })

  it('로그아웃 서버 요청 중에도 늦은 재발급이 세션을 되살리지 않는다', async () => {
    const refresh = deferred<
      Awaited<ReturnType<typeof storeOperatorAuthApi.refreshStoreOperatorToken>>
    >()
    const csrf = deferred<void>()
    let refreshStarted = false
    let csrfStarted = false
    const authorizations: Array<string | null> = []

    vi.spyOn(storeOperatorAuthApi, 'refreshStoreOperatorToken').mockImplementation(
      () => {
        refreshStarted = true
        return refresh.promise
      },
    )

    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'
    server.use(
      http.get(OPERATOR_CSRF_PATH, async () => {
        csrfStarted = true
        await csrf.promise
        return successResponse({
          token: 'operator-csrf',
          headerName: 'X-CSRF-TOKEN',
        })
      }),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => successResponse(null)),
      http.get(operatorStorePath(), ({ request }) => {
        authorizations.push(request.headers.get('authorization'))
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    await waitFor(() => expect(refreshStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    refresh.resolve({
      accessToken: 'late-operator-token',
      tokenType: 'Bearer',
      expiresIn: 900,
    })
    await refresh.promise
    await waitFor(() => expect(csrfStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(authorizations).toHaveLength(1))
    expect(authorizations).toEqual([null])
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')

    csrf.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('sign-out-settled')).toHaveTextContent('true'),
    )
  })

  it('로그아웃 서버 정리 중 시작한 새 세션을 이전 로그아웃이 폐기하지 않는다', async () => {
    const csrf = deferred<void>()
    let csrfStarted = false
    const serverOrder: string[] = []
    let authorization: string | null = null

    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'
    server.use(
      authenticatedOperator('old-operator-token'),
      http.get(OPERATOR_CSRF_PATH, async () => {
        csrfStarted = true
        await csrf.promise
        return successResponse({
          token: 'operator-csrf',
          headerName: 'X-CSRF-TOKEN',
        })
      }),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => {
        serverOrder.push('sign-out')
        return successResponse(null)
      }),
      http.post(OPERATOR_SESSIONS_PATH, () => {
        serverOrder.push('sign-in')
        return successResponse(tokenData('new-operator-token'))
      }),
      http.get(operatorStorePath(), ({ request }) => {
        authorization = request.headers.get('authorization')
        return successResponse(managedStore())
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(csrfStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    csrf.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('sign-in-settled')).toHaveTextContent('true'),
    )
    await waitFor(() =>
      expect(screen.getByTestId('sign-out-settled')).toHaveTextContent('true'),
    )

    expect(serverOrder).toEqual(['sign-out', 'sign-in'])
    expect(screen.getByTestId('status')).toHaveTextContent('authenticated')
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(authorization).toBe('Bearer new-operator-token'))
  })

  it('로그아웃 서버 정리 중 시작한 로그인이 실패해도 기존 세션을 폐기한다', async () => {
    const csrf = deferred<void>()
    let csrfStarted = false
    const serverOrder: string[] = []

    document.cookie = 'MIRIYUM_STORE_OPERATOR_XSRF_TOKEN=operator-csrf'
    server.use(
      authenticatedOperator('old-operator-token'),
      http.get(OPERATOR_CSRF_PATH, async () => {
        csrfStarted = true
        await csrf.promise
        return successResponse({
          token: 'operator-csrf',
          headerName: 'X-CSRF-TOKEN',
        })
      }),
      http.delete(OPERATOR_SESSION_CURRENT_PATH, () => {
        serverOrder.push('sign-out')
        return successResponse(null)
      }),
      http.post(OPERATOR_SESSIONS_PATH, () => {
        serverOrder.push('sign-in')
        return errorResponse(
          401,
          'AUTH_005',
          '이메일 또는 비밀번호가 올바르지 않습니다.',
        )
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(csrfStarted).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '로그인' }))
    csrf.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('sign-in-settled')).toHaveTextContent('true'),
    )
    await waitFor(() =>
      expect(screen.getByTestId('sign-out-settled')).toHaveTextContent('true'),
    )

    expect(serverOrder).toEqual(['sign-out', 'sign-in'])
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')
  })

  it('서버 정리에 실패해도 클라이언트 세션은 비운다', async () => {
    let refreshCalls = 0
    server.use(
      http.post(OPERATOR_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(tokenData())
      }),
      http.get(OPERATOR_CSRF_PATH, () =>
        errorResponse(401, 'AUTH_001', '인증이 필요합니다.'),
      ),
    )

    const view = renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    expect(screen.getByTestId('sign-out-pending')).toHaveTextContent('true')

    view.unmount()
    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
    // 서버 로그아웃이 끝나지 않았다는 경계가 새로고침 뒤에도 남아, 남은
    // Refresh 쿠키로 이전 세션을 자동 복원하지 않는다.
    expect(refreshCalls).toBe(1)
  })

  it('로그아웃 실패 뒤 도착한 401이 세션을 되살리지 않는다', async () => {
    const signOutFailure = deferred<void>()
    const protectedResponse = deferred<void>()
    let refreshCalls = 0
    let protectedRequestStarted = false

    server.use(
      http.post(OPERATOR_REFRESH_PATH, () => {
        refreshCalls += 1
        return successResponse(
          tokenData(
            refreshCalls === 1
              ? 'old-operator-token'
              : 'resurrected-operator-token',
          ),
        )
      }),
      http.get(OPERATOR_CSRF_PATH, async () => {
        await signOutFailure.promise
        return errorResponse(503, 'COMMON_001', '로그아웃 서버 정리 실패')
      }),
      http.get(operatorStorePath(), async () => {
        protectedRequestStarted = true
        await protectedResponse.promise
        return errorResponse(401, 'AUTH_002', 'Access Token이 만료되었습니다.')
      }),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    fireEvent.click(screen.getByRole('button', { name: '보호 API 호출' }))
    await waitFor(() => expect(protectedRequestStarted).toBe(true))

    signOutFailure.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('sign-out-settled')).toHaveTextContent('true'),
    )
    protectedResponse.resolve()
    await waitFor(() =>
      expect(screen.getByTestId('protected-request-settled')).toHaveTextContent(
        'true',
      ),
    )

    expect(refreshCalls).toBe(1)
    expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated')
  })
})
