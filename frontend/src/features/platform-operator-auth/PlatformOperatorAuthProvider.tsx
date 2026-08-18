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
import { useQueryClient } from '@tanstack/react-query'
import { createApiClient, type ApiClient } from '../../shared/api/client'
import { isApiError, isNetworkError } from '../../shared/api/apiError'
import { clearPlatformOperatorProtectedQueries } from '../../shared/api/platformOperatorSession'
import { AuthErrorCode } from '../auth/model/authErrors'
import { readCookie } from '../auth/model/csrfCookie'
import {
  preparePlatformOperatorCsrfToken,
  refreshPlatformOperatorToken,
  replacePlatformOperatorInitialPassword,
  signInPlatformOperator,
  signOutPlatformOperator,
  type InitialPasswordChangeRequest,
  type PlatformOperatorLoginRequest,
  type PlatformOperatorTokenData,
} from './api/platformOperatorAuthApi'
import { PLATFORM_OPERATOR_CSRF_COOKIE } from './model/csrfCookie'
import {
  isRefreshablePlatformOperatorAuthError,
  isInitialPasswordChangeRequired,
} from './model/platformOperatorErrors'
import {
  resolveCapabilities,
  type CapabilityState,
} from './model/capabilities'

/**
 * 플랫폼 운영자 shell의 인증 상태.
 *
 * `restricted`가 일반 사용자 shell에 없는 상태다. 자격은 확인됐지만 최초
 * 비밀번호를 바꾸기 전이라 업무 endpoint를 쓸 수 없는 구간이다.
 * `authenticated`와 합치면 콘솔이 열렸다가 모든 조회가 403으로 실패한다.
 */
export type PlatformOperatorAuthStatus =
  | 'restoring'
  | 'authenticated'
  | 'restricted'
  | 'unauthenticated'

export type SignOutOutcome = 'revoked' | 'unconfirmed'

/**
 * 서버가 알려 준 세션 만료 시각.
 *
 * 수치를 화면에 하드코딩하지 않는다. 시안에는 "15분간 활동이 없으면"이라고
 * 적혀 있지만 실제 값은 배포 설정에 따라 달라지고, 계약도 과거 정책 수치를
 * 선반영하지 말라고 못박고 있다. 서버가 준 시각만 표시한다.
 */
export interface PlatformOperatorSessionDeadlines {
  idleExpiresAt: string
  absoluteExpiresAt: string
}

export interface PlatformOperatorAuthContextValue {
  status: PlatformOperatorAuthStatus
  /** 보호 API 호출용 client. Access Token과 401 재발급이 이미 걸려 있다. */
  apiClient: ApiClient
  /** 현재 운영자의 권한. 계약(#403) 전에는 `unknown`이다. */
  capabilities: CapabilityState
  sessionDeadlines: PlatformOperatorSessionDeadlines | null
  signIn: (credentials: PlatformOperatorLoginRequest) => Promise<void>
  changeInitialPassword: (
    request: InitialPasswordChangeRequest,
  ) => Promise<void>
  signOut: () => Promise<SignOutOutcome>
  signOutNotice: SignOutOutcome | null
  dismissSignOutNotice: () => void
}

/**
 * 서버에 폐기할 세션이 애초에 없다는 뜻의 코드다. 폐기 실패가 아니다.
 *
 * `AUTH_015`가 여기 함께 있는 이유는 중앙 세션이 이미 회수됐다는 응답이라
 * 로그아웃 목적이 달성된 상태이기 때문이다. CSRF·Origin 거절은 서버가 요청을
 * 처리하지 않았다는 뜻이라 여기 없다.
 */
const SESSION_ALREADY_GONE = new Set<string>([
  AuthErrorCode.REFRESH_TOKEN_REQUIRED,
  AuthErrorCode.REFRESH_TOKEN_INVALID,
  AuthErrorCode.PLATFORM_OPERATOR_SESSION_INVALID,
])

async function revokePlatformOperatorServerSession(): Promise<SignOutOutcome> {
  try {
    const prepared = await preparePlatformOperatorCsrfToken()
    await signOutPlatformOperator(
      readCookie(PLATFORM_OPERATOR_CSRF_COOKIE) ?? prepared,
    )
    return 'revoked'
  } catch (error) {
    if (isApiError(error)) {
      return SESSION_ALREADY_GONE.has(error.code) ? 'revoked' : 'unconfirmed'
    }
    if (isNetworkError(error)) {
      return 'unconfirmed'
    }
    throw error
  }
}

const PlatformOperatorAuthContext =
  createContext<PlatformOperatorAuthContextValue | null>(null)

export function usePlatformOperatorAuth(): PlatformOperatorAuthContextValue {
  const value = useContext(PlatformOperatorAuthContext)
  if (value === null) {
    throw new Error(
      'usePlatformOperatorAuth는 PlatformOperatorAuthProvider 안에서만 사용할 수 있습니다.',
    )
  }
  return value
}

/**
 * 플랫폼 운영자 인증 shell.
 *
 * 일반 사용자·매장 운영자 shell과 토큰·쿠키·캐시를 공유하지 않는다. Access JWT는
 * ref에만 두고 Web Storage에 넣지 않는다. Refresh JWT는 HttpOnly 쿠키라 읽지 않는다.
 *
 * 세대·단일 재발급 장치는 일반 사용자 shell과 같은 이유로 필요하다. 다만 이쪽은
 * 서버가 Refresh Token을 매번 회전시키므로, 재발급이 겹치면 한쪽이 이미 소비된
 * 토큰을 보내 살아 있는 세션이 끊긴다.
 */
export function PlatformOperatorAuthProvider({
  children,
}: {
  children: ReactNode
}) {
  const accessTokenRef = useRef<string | null>(null)
  const [status, setStatus] = useState<PlatformOperatorAuthStatus>('restoring')
  const [sessionDeadlines, setSessionDeadlines] =
    useState<PlatformOperatorSessionDeadlines | null>(null)
  const [signOutNotice, setSignOutNotice] = useState<SignOutOutcome | null>(
    null,
  )

  const refreshInFlight = useRef<Promise<boolean> | null>(null)
  const sessionGeneration = useRef(0)
  const queryClient = useQueryClient()

  const clearSession = useCallback(() => {
    sessionGeneration.current += 1
    refreshInFlight.current = null
    accessTokenRef.current = null
    setStatus('unauthenticated')
    setSessionDeadlines(null)
    void clearPlatformOperatorProtectedQueries(queryClient)
  }, [queryClient])

  /**
   * 토큰 응답을 shell 상태로 반영한다.
   *
   * `passwordChangeRequired`가 곧 제한 세션이다. 이 판정을 화면마다 반복하면
   * 한 곳만 빠져도 제한 세션이 정상 세션처럼 열린다.
   */
  const applyToken = useCallback((token: PlatformOperatorTokenData) => {
    accessTokenRef.current = token.accessToken
    setSessionDeadlines({
      idleExpiresAt: token.idleExpiresAt,
      absoluteExpiresAt: token.absoluteExpiresAt,
    })
    setStatus(token.passwordChangeRequired ? 'restricted' : 'authenticated')
  }, [])

  const runRefresh = useCallback(async (): Promise<boolean> => {
    const generation = sessionGeneration.current
    try {
      const token = await refreshPlatformOperatorToken()
      if (generation !== sessionGeneration.current) {
        return false
      }
      applyToken(token)
      // 제한 세션은 업무 요청을 이어서 보낼 수 없다. 재시도를 성공으로
      // 돌려주면 호출자가 같은 요청을 다시 보내고 403을 한 번 더 받는다.
      return !token.passwordChangeRequired
    } catch {
      if (generation !== sessionGeneration.current) {
        return false
      }
      clearSession()
      return false
    }
  }, [applyToken, clearSession])

  const refreshOnce = useCallback((): Promise<boolean> => {
    if (refreshInFlight.current !== null) {
      return refreshInFlight.current
    }
    const attempt: Promise<boolean> = runRefresh().finally(() => {
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
          if (!isRefreshablePlatformOperatorAuthError(error.code)) {
            clearSession()
            return false
          }
          return refreshOnce()
        },
        /**
         * 제한 세션이 업무 API를 불렀다는 신호다.
         *
         * 로그인·회전 응답의 `passwordChangeRequired`만 보면 이 상태를 놓친다.
         * 다른 탭에서 열어 둔 화면이나, 비밀번호 변경 전에 북마크로 바로 들어온
         * 경우가 그렇다. 서버가 `AUTH_012`로 알려 주므로 그때 shell 상태를
         * `restricted`로 돌려 가드가 변경 화면으로 보내게 한다.
         *
         * 세션을 비우지 않는다. 자격은 유효하고 비밀번호만 바꾸면 되기 때문이다.
         */
        onForbidden: (error) => {
          if (isInitialPasswordChangeRequired(error.code)) {
            setStatus('restricted')
          }
        },
      }),
    [clearSession, refreshOnce],
  )

  // 새로고침 직후 같은 shell의 재발급으로 세션을 복구한다.
  useEffect(() => {
    void refreshOnce()
  }, [refreshOnce])

  const signIn = useCallback(
    async (credentials: PlatformOperatorLoginRequest) => {
      sessionGeneration.current += 1
      refreshInFlight.current = null
      const generation = sessionGeneration.current

      await clearPlatformOperatorProtectedQueries(queryClient)
      const token = await signInPlatformOperator(credentials)

      if (generation !== sessionGeneration.current) {
        return
      }
      applyToken(token)
      setSignOutNotice(null)
    },
    [applyToken, queryClient],
  )

  /**
   * 최초 비밀번호 변경.
   *
   * 제한 세션의 Access Token으로만 부를 수 있다. 성공 응답이 새 토큰을 주므로
   * 그대로 반영하면 사용자가 다시 로그인하지 않는다.
   */
  const changeInitialPassword = useCallback(
    async (request: InitialPasswordChangeRequest) => {
      const accessToken = accessTokenRef.current
      if (accessToken === null) {
        throw new Error('제한 세션이 없어 최초 비밀번호를 변경할 수 없습니다.')
      }
      const generation = sessionGeneration.current
      const token = await replacePlatformOperatorInitialPassword(
        accessToken,
        request,
      )
      if (generation !== sessionGeneration.current) {
        return
      }
      applyToken(token)
    },
    [applyToken],
  )

  const signOut = useCallback(async (): Promise<SignOutOutcome> => {
    try {
      const outcome = await revokePlatformOperatorServerSession()
      setSignOutNotice(outcome === 'unconfirmed' ? outcome : null)
      return outcome
    } finally {
      clearSession()
    }
  }, [clearSession])

  const dismissSignOutNotice = useCallback(() => setSignOutNotice(null), [])

  /**
   * 권한은 아직 서버에서 읽지 못한다. 판정 자체를 이 모듈이 소유하므로
   * #403이 들어오면 여기만 바뀐다.
   */
  const capabilities = useMemo(() => resolveCapabilities(), [])

  const value = useMemo(
    () => ({
      status,
      apiClient,
      capabilities,
      sessionDeadlines,
      signIn,
      changeInitialPassword,
      signOut,
      signOutNotice,
      dismissSignOutNotice,
    }),
    [
      status,
      apiClient,
      capabilities,
      sessionDeadlines,
      signIn,
      changeInitialPassword,
      signOut,
      signOutNotice,
      dismissSignOutNotice,
    ],
  )

  return (
    <PlatformOperatorAuthContext.Provider value={value}>
      {children}
    </PlatformOperatorAuthContext.Provider>
  )
}

export { isInitialPasswordChangeRequired }
