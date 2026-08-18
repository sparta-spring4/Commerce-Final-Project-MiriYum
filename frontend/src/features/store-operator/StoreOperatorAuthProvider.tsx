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

  /**
   * 초기 복구와 유효한 세션에서만 재발급을 허용한다. 명시적
   * 로그아웃이나 실패한 로그인 뒤 늦게 도착한 401이 남아 있는
   * Refresh 쿠키로 세션을 되살리지 못하게 한다.
   */
  const refreshEligible = useRef(true)

  /**
   * Refresh·로그인·로그아웃은 모두 HttpOnly Refresh 쿠키를 바꿀 수 있다.
   * 호출 순서대로 실행해야 이전 응답이 새 쿠키를 덮거나 이전 로그아웃이 새
   * 로그인 세션을 폐기하지 않는다. 실패는 호출자에게 반환하되 큐는 이어 간다.
   */
  const serverSessionCommandTail = useRef<Promise<void>>(Promise.resolve())
  const runServerSessionCommand = useCallback(
    <T,>(command: () => Promise<T>): Promise<T> => {
      const result = serverSessionCommandTail.current.then(command)
      serverSessionCommandTail.current = result.then(
        () => undefined,
        () => undefined,
      )
      return result
    },
    [],
  )

  /**
   * 세션 세대. 로그인·로그아웃으로 세션이 바뀌면 이전 재발급 결과를 버린다.
   * 그렇지 않으면 늦은 성공이 로그아웃을 되돌리거나, 늦은 실패가 새 로그인의
   * Access Token을 지울 수 있다.
   */
  const sessionGeneration = useRef(0)

  const beginSessionTransition = useCallback(() => {
    sessionGeneration.current += 1
    refreshInFlight.current = null
    return sessionGeneration.current
  }, [])

  const clearSession = useCallback(() => {
    const generation = beginSessionTransition()
    refreshEligible.current = false
    accessTokenRef.current = null
    setStatus('unauthenticated')
    return generation
  }, [beginSessionTransition])

  const runRefresh = useCallback(async (): Promise<boolean> => {
    const generation = sessionGeneration.current
    try {
      const token = await runServerSessionCommand(refreshStoreOperatorToken)
      if (generation !== sessionGeneration.current) {
        return false
      }
      refreshEligible.current = true
      accessTokenRef.current = token.accessToken
      setStatus('authenticated')
      return true
    } catch {
      if (generation !== sessionGeneration.current) {
        return false
      }
      // 재발급 실패는 오류 화면이 아니라 비로그인 상태다.
      clearSession()
      return false
    }
  }, [clearSession, runServerSessionCommand])

  const refreshOnce = useCallback((): Promise<boolean> => {
    if (!refreshEligible.current) {
      return Promise.resolve(false)
    }
    if (refreshInFlight.current !== null) {
      return refreshInFlight.current
    }

    const attempt: Promise<boolean> = runRefresh().finally(() => {
      // 이전 세션의 늦은 완료가 현재 세션의 재발급 참조를 지우지 않는다.
      if (refreshInFlight.current === attempt) {
        refreshInFlight.current = null
      }
    })
    refreshInFlight.current = attempt
    return attempt
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
    // 로그인 시도도 새 세션 경계다. 실패해도 restoring에 남지 않는다.
    const generation = clearSession()

    let token: Awaited<ReturnType<typeof signInStoreOperator>>
    try {
      token = await runServerSessionCommand(() =>
        signInStoreOperator(credentials),
      )
    } catch (error) {
      // 로그인 대기 중 401이 뒤에 재발급을 큐잉했을 수 있다.
      // 더 최신의 세션 전환이 없을 때만 실패한 세대를 닫는다.
      if (generation === sessionGeneration.current) {
        beginSessionTransition()
      }
      throw error
    }
    // 로그인 응답을 기다리는 사이 로그아웃했다면 늦은 결과를 남기지 않는다.
    if (generation !== sessionGeneration.current) {
      return
    }
    refreshEligible.current = true
    accessTokenRef.current = token.accessToken
    setStatus('authenticated')
  }, [beginSessionTransition, clearSession, runServerSessionCommand])

  const signOut = useCallback(async () => {
    // 서버 정리를 기다리는 동안에도 이전 재발급이 세션을 되살리지 못하게 한다.
    const generation = clearSession()
    await runServerSessionCommand(async () => {
      try {
        // 서버가 쿠키를 내려주므로 값을 읽기 전에 준비를 먼저 요청한다.
        await prepareStoreOperatorCsrfToken()
        const csrfToken = readCookie(STORE_OPERATOR_CSRF_COOKIE)
        if (csrfToken !== null) {
          await signOutStoreOperator(csrfToken)
        }
      } catch (error) {
        // 서버 정리에 실패해도 클라이언트 메모리는 이미 비웠다.
        if (!isApiError(error)) {
          throw error
        }
      } finally {
        // 로그아웃 중 401이 시작한 재발급도 다음에 실행될 수 있다.
        // 더 최신의 로그인이 없을 때만 세대를 닫아 그 결과를 버린다.
        if (generation === sessionGeneration.current) {
          beginSessionTransition()
        }
      }
    })
  }, [beginSessionTransition, clearSession, runServerSessionCommand])

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
