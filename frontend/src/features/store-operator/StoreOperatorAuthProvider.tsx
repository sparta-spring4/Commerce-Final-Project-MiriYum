import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { ReactNode } from 'react'
import { createApiClient, type ApiClient } from '../../shared/api/client'
import { isApiError } from '../../shared/api/apiError'
import { isRefreshableAuthError } from '../auth/model/authErrors'
import {
  STORE_OPERATOR_CSRF_COOKIE,
  readCookie,
} from '../auth/model/csrfCookie'
import {
  prepareStoreOperatorCsrfToken,
  refreshStoreOperatorToken,
  signInStoreOperator,
  signOutStoreOperator,
  type LoginRequest,
} from './api/storeOperatorAuthApi'

/**
 * 매장 운영자 shell의 인증 상태.
 *
 * `restoring`은 새로고침 직후 재발급을 시도하는 동안이다. 이 상태를
 * `unauthenticated`와 합치면 보호 화면이 로그인으로 튕겼다가 되돌아온다.
 */
export type StoreOperatorAuthStatus =
  | 'restoring'
  | 'authenticated'
  | 'unauthenticated'

export interface StoreOperatorAuthContextValue {
  status: StoreOperatorAuthStatus
  /** 보호 API 호출용 client. Access Token과 401 재발급이 이미 걸려 있다. */
  apiClient: ApiClient
  signIn: (credentials: LoginRequest) => Promise<void>
  signOut: () => Promise<void>
}

const StoreOperatorAuthContext =
  createContext<StoreOperatorAuthContextValue | null>(null)

export function useStoreOperatorAuth(): StoreOperatorAuthContextValue {
  const value = useContext(StoreOperatorAuthContext)
  if (value === null) {
    throw new Error(
      'useStoreOperatorAuth는 StoreOperatorAuthProvider 안에서만 사용할 수 있습니다.',
    )
  }
  return value
}

/**
 * 매장 운영자 인증 shell.
 *
 * Access JWT는 이 컴포넌트의 ref에만 있다. Refresh JWT는 HttpOnly 쿠키다.
 * 일반 사용자 shell과 provider·쿠키 이름·client를 공유하지 않는다. 한쪽 토큰으로
 * 다른 쪽 화면이 열리는 상황을 구조적으로 막는다.
 */
export function StoreOperatorAuthProvider({
  children,
}: {
  children: ReactNode
}) {
  const accessTokenRef = useRef<string | null>(null)
  const [status, setStatus] = useState<StoreOperatorAuthStatus>('restoring')

  /**
   * 진행 중인 재발급을 공유한다. 보호 API 여러 개가 동시에 401을 받아 각자
   * 재발급을 부르면, 회전하는 Refresh 토큰 구성에서 뒤늦은 요청이 세션을 끊는다.
   */
  const refreshInFlight = useRef<Promise<boolean> | null>(null)

  const clearSession = useCallback(() => {
    accessTokenRef.current = null
    setStatus('unauthenticated')
  }, [])

  const runRefresh = useCallback(async (): Promise<boolean> => {
    try {
      const token = await refreshStoreOperatorToken()
      accessTokenRef.current = token.accessToken
      setStatus('authenticated')
      return true
    } catch {
      // 재발급 실패는 오류 화면이 아니라 비로그인 상태다.
      clearSession()
      return false
    }
  }, [clearSession])

  const refreshOnce = useCallback((): Promise<boolean> => {
    refreshInFlight.current ??= runRefresh().finally(() => {
      refreshInFlight.current = null
    })
    return refreshInFlight.current
  }, [runRefresh])

  const apiClient = useMemo(
    () =>
      createApiClient({
        getAccessToken: () => accessTokenRef.current,
        onUnauthorized: async (error) => {
          // 만료만 재발급 대상이다. namespace 불일치는 다른 shell의 토큰을 쓴
          // 상황이라 조용히 이어 붙이면 shell 분리 규칙이 깨진다.
          if (!isRefreshableAuthError(error.code)) {
            clearSession()
            return false
          }
          return refreshOnce()
        },
      }),
    [clearSession, refreshOnce],
  )

  useEffect(() => {
    void refreshOnce()
  }, [refreshOnce])

  const signIn = useCallback(async (credentials: LoginRequest) => {
    const token = await signInStoreOperator(credentials)
    accessTokenRef.current = token.accessToken
    setStatus('authenticated')
  }, [])

  const signOut = useCallback(async () => {
    try {
      // 서버가 쿠키를 내려주므로 값을 읽기 전에 준비를 먼저 요청한다.
      await prepareStoreOperatorCsrfToken()
      const csrfToken = readCookie(STORE_OPERATOR_CSRF_COOKIE)
      if (csrfToken !== null) {
        await signOutStoreOperator(csrfToken)
      }
    } catch (error) {
      // 서버 정리에 실패해도 클라이언트 메모리는 반드시 비운다.
      if (!isApiError(error)) {
        throw error
      }
    } finally {
      clearSession()
    }
  }, [clearSession])

  const value = useMemo(
    () => ({ status, apiClient, signIn, signOut }),
    [status, apiClient, signIn, signOut],
  )

  return (
    <StoreOperatorAuthContext.Provider value={value}>
      {children}
    </StoreOperatorAuthContext.Provider>
  )
}
