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
import { clearConsumerProtectedQueries } from '../../shared/api/consumerSession'
import {
  prepareConsumerCsrfToken,
  refreshConsumerToken,
  signInConsumer,
  signOutConsumer,
  type LoginRequest,
} from './api/consumerAuthApi'
import { AuthErrorCode, isRefreshableAuthError } from './model/authErrors'
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

/**
 * 로그아웃에서 서버 세션 폐기가 확인됐는지.
 *
 * 로컬 자격은 어느 쪽이든 무조건 비운다. 두 값의 차이는 사용자에게 무엇을
 * 알리느냐다. `unconfirmed`는 이 기기에서는 나갔지만 서버 Refresh family가
 * 살아 있을 수 있다는 뜻이라, 공용 PC에서는 사용자가 조치를 더 해야 한다.
 */
export type SignOutOutcome = 'revoked' | 'unconfirmed'

export interface ConsumerAuthContextValue {
  status: ConsumerAuthStatus
  /** 보호 API 호출용 client. Access Token과 401 재발급이 이미 걸려 있다. */
  apiClient: ApiClient
  signIn: (credentials: LoginRequest) => Promise<void>
  completeKakaoSignIn: (accessToken: string) => Promise<void>
  signOut: () => Promise<SignOutOutcome>
  /**
   * 마지막 로그아웃에서 서버 폐기를 확인하지 못했을 때만 채워진다.
   *
   * provider가 들고 있는 이유는 로그아웃 직후 화면이 이동하기 때문이다.
   * 버튼이 있던 컴포넌트에 두면 이동하면서 안내가 함께 사라진다.
   */
  signOutNotice: SignOutOutcome | null
  dismissSignOutNotice: () => void
}

/**
 * 서버에 폐기할 세션이 애초에 없다는 뜻의 코드다. 폐기 실패가 아니다.
 *
 * CSRF 거절(`AUTH_009`)과 Origin 거절(`AUTH_010`)은 여기 없다. 계약이 이
 * endpoint의 403을 `CsrfRejected`로 정의하는데, 그건 서버가 요청을 처리하지
 * 않았다는 뜻이라 세션이 그대로 살아 있다. status가 아니라 code로 갈라야
 * 이 둘을 구분할 수 있다.
 */
const SESSION_ALREADY_GONE = new Set<string>([
  AuthErrorCode.REFRESH_TOKEN_REQUIRED,
  AuthErrorCode.REFRESH_TOKEN_INVALID,
])

/**
 * 서버 세션을 폐기하고 결과만 판정한다. 로컬 정리는 호출자가 무조건 한다.
 *
 * CSRF 쿠키를 읽지 못해도 요청을 건너뛰지 않는다. 건너뛰면 화면만 로그아웃되고
 * 서버 Refresh family는 살아 있는데 아무 신호도 남지 않는다. 준비 응답이 주는
 * token은 쿠키에 담긴 것과 같은 값이므로 그 값으로 헤더를 채워 실제로 보낸다.
 * 쿠키가 브라우저에서도 빠져 있었다면 서버가 `AUTH_009`로 거절하고, 그 거절은
 * "조용한 생략"과 달리 미확인으로 집계된다.
 */
async function revokeConsumerServerSession(): Promise<SignOutOutcome> {
  try {
    const prepared = await prepareConsumerCsrfToken()
    await signOutConsumer(readCookie(CONSUMER_CSRF_COOKIE) ?? prepared)
    return 'revoked'
  } catch (error) {
    if (isApiError(error)) {
      return SESSION_ALREADY_GONE.has(error.code) ? 'revoked' : 'unconfirmed'
    }
    // 서버에 닿지 못했으면 폐기됐는지 알 수 없다.
    if (isNetworkError(error)) {
      return 'unconfirmed'
    }
    // 우리 쪽 결함이다. 로그아웃 결과로 뭉뚱그리지 않는다.
    throw error
  }
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
  const [signOutNotice, setSignOutNotice] = useState<SignOutOutcome | null>(
    null,
  )

  /**
   * 진행 중인 재발급을 공유한다.
   *
   * 보호 API 여러 개가 동시에 401을 받으면 각자 재발급을 부른다. 서버가
   * Refresh 토큰을 회전시키는 구성에서는 뒤늦은 요청이 이미 쓰인 토큰을 보내
   * 세션이 끊긴다. 한 번만 부르고 결과를 함께 기다린다.
   */
  const refreshInFlight = useRef<Promise<boolean> | null>(null)

  /**
   * 세션 세대.
   *
   * 세션이 끝나거나 새로 시작할 때마다 올라간다. 재발급은 시작 시점의 세대를
   * 붙잡아 두었다가, 응답이 도착했을 때 세대가 달라졌으면 결과를 버린다.
   *
   * 이 장치가 없으면 재발급이 떠 있는 동안 로그아웃한 사용자의 세션이
   * 되살아난다. 늦게 도착한 응답이 Access Token과 `authenticated`를 다시
   * 써 버리기 때문이다. 공용 PC에서 로그아웃하고 자리를 뜬 뒤에 벌어진다.
   */
  const sessionGeneration = useRef(0)

  const queryClient = useQueryClient()

  /**
   * 세션 종료의 유일한 경계.
   *
   * 로그아웃, 복구 실패, 재발급할 수 없는 401이 모두 여기로 모인다. 토큰만
   * 비우면 보호 데이터가 전역 캐시에 남아, 같은 탭에서 다음 사용자가 로그인할 때
   * 이전 사용자의 프로필·예약이 먼저 그려진다. 토큰과 캐시를 한자리에서 함께 비운다.
   *
   * 정리는 기다리지 않는다. 상태를 즉시 `unauthenticated`로 바꿔야 보호 화면이
   * 곧바로 물러난다. 취소·삭제는 동기적으로 캐시에서 데이터를 걷어낸다.
   */
  const clearSession = useCallback(() => {
    // 세대를 먼저 올린다. 지금 떠 있는 재발급의 결과는 이 세션의 것이 아니다.
    sessionGeneration.current += 1
    // 진행 중인 재발급도 이 세션의 것이다. 새 세션이 물려받으면, 세대 확인에
    // 걸려 버려질 promise를 기다리다가 새 세션의 401이 재발급을 시도해 보지도
    // 못하고 실패한다.
    refreshInFlight.current = null
    accessTokenRef.current = null
    setStatus('unauthenticated')
    void clearConsumerProtectedQueries(queryClient)
  }, [queryClient])

  const runRefresh = useCallback(async (): Promise<boolean> => {
    const generation = sessionGeneration.current
    try {
      const token = await refreshConsumerToken()
      // 재발급이 오가는 사이 로그아웃했거나 다른 세션이 시작됐다.
      if (generation !== sessionGeneration.current) {
        return false
      }
      accessTokenRef.current = token.accessToken
      setStatus('authenticated')
      return true
    } catch {
      // 이미 끝난 세션의 실패는 지금 상태를 건드릴 이유가 없다.
      if (generation !== sessionGeneration.current) {
        return false
      }
      // 재발급 실패는 오류 화면이 아니라 비로그인 상태다.
      clearSession()
      return false
    }
  }, [clearSession])

  const refreshOnce = useCallback((): Promise<boolean> => {
    if (refreshInFlight.current !== null) {
      return refreshInFlight.current
    }

    /*
     * 정리는 자기 것만 한다.
     *
     * `finally`가 무조건 비우면, 세션이 바뀌어 새 재발급(R2)이 자리에 앉은 뒤
     * 늦게 끝난 이전 재발급(R1)이 R2의 참조까지 지운다. 그러면 다음 401이 R3를
     * R2와 나란히 띄우고, Refresh 토큰을 회전시키는 구성에서는 둘 중 하나가
     * 토큰을 먼저 써 버려 나머지가 실패하면서 살아 있는 세션이 끊긴다.
     */
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

  /**
   * 세션 시작 경계.
   *
   * 로그인 직전에도 캐시를 비운다. 정상 흐름에서는 로그아웃이 이미 비웠지만,
   * 세션이 끊기지 않은 채 다른 계정으로 들어오는 경로가 생기면 그때도
   * 이전 계정의 데이터가 남지 않아야 한다.
   */
  const signIn = useCallback(
    async (credentials: LoginRequest) => {
      // 새 세션의 시작이다. 이전 세션에서 떠난 재발급 결과가 이 세션의 토큰을
      // 덮어쓰지 못하게 세대를 먼저 올린다.
      sessionGeneration.current += 1
      // 세션 종료와 같은 이유로 진행 중인 재발급도 넘겨받지 않는다.
      refreshInFlight.current = null
      const generation = sessionGeneration.current

      await clearConsumerProtectedQueries(queryClient)
      const token = await signInConsumer(credentials)

      // 로그인 응답을 기다리는 사이 로그아웃했다면 이 결과도 남기지 않는다.
      if (generation !== sessionGeneration.current) {
        return
      }
      accessTokenRef.current = token.accessToken
      setStatus('authenticated')
      // 새 세션이 열렸으므로 지난 로그아웃 안내는 더 이상 보여 줄 것이 아니다.
      setSignOutNotice(null)
    },
    [queryClient],
  )

  /**
   * 카카오 세션 교환·가입 완료 응답의 Access Token을 현재 consumer shell에만 둔다.
   * Refresh Token은 서버가 HttpOnly 쿠키로 설정하므로 이 함수에서 받거나 저장하지 않는다.
   */
  const completeKakaoSignIn = useCallback(
    async (accessToken: string) => {
      sessionGeneration.current += 1
      // 이메일 로그인과 같은 세션 시작 경계다. 이전 세션의 재발급을
      // 물려받으면 새 세션의 401이 재발급을 시도해 보지도 못하고 실패한다.
      refreshInFlight.current = null

      const generation = sessionGeneration.current
      await clearConsumerProtectedQueries(queryClient)

      // 캐시 정리 중 로그아웃했다면 늦게 도착한 카카오 결과를 남기지 않는다.
      if (generation !== sessionGeneration.current) {
        return
      }

      accessTokenRef.current = accessToken
      setStatus('authenticated')
      setSignOutNotice(null)
    },
    [queryClient],
  )

  /**
   * 로그아웃.
   *
   * 서버 폐기 성공 여부와 무관하게 로컬 자격은 반드시 비운다. 다만 폐기하지
   * 못한 것을 성공과 같은 화면으로 끝내지 않는다. 서버가 "폐기하지 못했다"고
   * 답했는데 화면이 로그아웃 완료로 보이면 공용 PC에서 세션이 살아남는다.
   */
  const signOut = useCallback(async (): Promise<SignOutOutcome> => {
    try {
      const outcome = await revokeConsumerServerSession()
      setSignOutNotice(outcome === 'unconfirmed' ? outcome : null)
      return outcome
    } finally {
      clearSession()
    }
  }, [clearSession])

  const dismissSignOutNotice = useCallback(() => setSignOutNotice(null), [])

  const value = useMemo(
    () => ({
      status,
      apiClient,
      signIn,
      completeKakaoSignIn,
      signOut,
      signOutNotice,
      dismissSignOutNotice,
    }),
    [
      status,
      apiClient,
      signIn,
      completeKakaoSignIn,
      signOut,
      signOutNotice,
      dismissSignOutNotice,
    ],
  )

  return (
    <ConsumerAuthContext.Provider value={value}>
      {children}
    </ConsumerAuthContext.Provider>
  )
}
