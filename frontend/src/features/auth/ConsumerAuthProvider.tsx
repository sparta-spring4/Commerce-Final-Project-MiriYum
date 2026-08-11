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
import {
  prepareConsumerCsrfToken,
  refreshConsumerToken,
  signInConsumer,
  signOutConsumer,
  type LoginRequest,
} from './api/consumerAuthApi'
import { isRefreshableAuthError } from './model/authErrors'
import { CONSUMER_CSRF_COOKIE, readCookie } from './model/csrfCookie'

/**
 * 일반 사용자 shell의 인증 상태.
 *
 * `restoring`은 새로고침 직후 재발급을 시도하는 동안이다. 이 상태를
 * `unauthenticated`와 합치면 보호 화면이 로그인으로 튕겼다가 되돌아온다.
 */
export type ConsumerAuthStatus =
  | 'restoring'
  | 'authenticated'
  | 'unauthenticated'

export interface ConsumerAuthContextValue {
  status: ConsumerAuthStatus
  /** 보호 API 호출용 client. Access Token과 401 재발급이 이미 걸려 있다. */
  apiClient: ApiClient
  signIn: (credentials: LoginRequest) => Promise<void>
  signOut: () => Promise<void>
}

const ConsumerAuthContext = createContext<ConsumerAuthContextValue | null>(null)

export function useConsumerAuth(): ConsumerAuthContextValue {
  const value = useContext(ConsumerAuthContext)
  if (value === null) {
    throw new Error(
      'useConsumerAuth는 ConsumerAuthProvider 안에서만 사용할 수 있습니다.',
    )
  }
  return value
}

/**
 * 일반 사용자 인증 shell.
 *
 * Access JWT는 이 컴포넌트의 ref에만 있다. state로 두면 React DevTools와
 * 직렬화 경로에 노출되고, Web Storage에 두면 계약이 금지한 저장이 된다.
 * Refresh JWT는 HttpOnly 쿠키라 여기서 읽지 않는다.
 *
 * 매장 운영자 shell은 별도 provider를 쓴다. 두 namespace의 토큰과 상태를
 * 한 저장소에 합치지 않는다.
 */
export function ConsumerAuthProvider({ children }: { children: ReactNode }) {
  const accessTokenRef = useRef<string | null>(null)
  const [status, setStatus] = useState<ConsumerAuthStatus>('restoring')

  /**
   * 진행 중인 재발급을 공유한다.
   *
   * 보호 API 여러 개가 동시에 401을 받으면 각자 재발급을 부른다. 서버가
   * Refresh 토큰을 회전시키는 구성에서는 뒤늦은 요청이 이미 쓰인 토큰을 보내
   * 세션이 끊긴다. 한 번만 부르고 결과를 함께 기다린다.
   */
  const refreshInFlight = useRef<Promise<boolean> | null>(null)

  const clearSession = useCallback(() => {
    accessTokenRef.current = null
    setStatus('unauthenticated')
  }, [])

  const runRefresh = useCallback(async (): Promise<boolean> => {
    try {
      const token = await refreshConsumerToken()
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
          // 만료만 재발급 대상이다. 형식 오류·namespace 불일치는 재발급해도 같다.
          if (!isRefreshableAuthError(error.code)) {
            clearSession()
            return false
          }
          return refreshOnce()
        },
      }),
    [clearSession, refreshOnce],
  )

  // 새로고침 직후 같은 shell의 재발급으로 세션을 복구한다.
  useEffect(() => {
    void refreshOnce()
  }, [refreshOnce])

  const signIn = useCallback(async (credentials: LoginRequest) => {
    const token = await signInConsumer(credentials)
    accessTokenRef.current = token.accessToken
    setStatus('authenticated')
  }, [])

  const signOut = useCallback(async () => {
    try {
      // 서버가 쿠키를 내려주므로 값을 읽기 전에 준비를 먼저 요청한다.
      await prepareConsumerCsrfToken()
      const csrfToken = readCookie(CONSUMER_CSRF_COOKIE)
      if (csrfToken !== null) {
        await signOutConsumer(csrfToken)
      }
    } catch (error) {
      // 서버 정리에 실패해도 클라이언트 메모리는 반드시 비운다.
      // 401·403은 이미 세션이 없다는 뜻이므로 사용자에게 되묻지 않는다.
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
    <ConsumerAuthContext.Provider value={value}>
      {children}
    </ConsumerAuthContext.Provider>
  )
}
