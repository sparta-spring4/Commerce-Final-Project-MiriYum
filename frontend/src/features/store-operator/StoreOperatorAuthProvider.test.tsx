import { render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../test/msw/envelope'
import { server } from '../../test/msw/server'
import { CONSUMER_REFRESH_PATH } from '../auth/test/handlers'
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
  const { status, apiClient, signIn, signOut } = useStoreOperatorAuth()

  return (
    <div>
      <p data-testid="status">{status}</p>
      <button
        type="button"
        onClick={() => void signIn({ email: 'a@b.co', password: 'Miriyum1!' })}
      >
        로그인
      </button>
      <button type="button" onClick={() => void signOut()}>
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
          }).catch(() => undefined)
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

  it('서버 정리에 실패해도 클라이언트 세션은 비운다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(OPERATOR_CSRF_PATH, () =>
        errorResponse(401, 'AUTH_001', '인증이 필요합니다.'),
      ),
    )

    renderProvider()
    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('authenticated'),
    )

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    await waitFor(() =>
      expect(screen.getByTestId('status')).toHaveTextContent('unauthenticated'),
    )
  })
})
