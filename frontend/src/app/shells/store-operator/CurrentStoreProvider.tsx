import { useQueryClient } from '@tanstack/react-query'
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
import { useStoreOperatorAuth } from './StoreOperatorAuthProvider'
import { storeOperatorKeys } from './queryKeys'

/**
 * 현재 관리 중인 매장 ID를 shell 수준에서 붙들고 있다.
 *
 * 운영자가 소유한 매장 목록 조회 계약이 없다. 그래서 "매장 있음/없음"을 추측하지
 * 않고, 등록 응답이나 이미 열려 있는 매장 경로에서 얻은 ID만 보존한다.
 * 보존 위치는 sessionStorage다. 토큰이 아니라 화면 선택 상태이며, 새로고침 뒤
 * 매장을 다시 고르게 만들지 않기 위해 필요하다.
 */
const STORAGE_KEY = 'MIRIYUM_STORE_OPERATOR_CURRENT_STORE'

export interface CurrentStoreContextValue {
  /** 아직 아는 매장이 없으면 null. 목록 조회로 채우지 않는다. */
  storeId: string | null
  /** 매장을 현재 매장으로 채택한다. 바뀌면 이전 매장 캐시를 격리한다. */
  selectStore: (storeId: string) => void
  clearStore: () => void
}

const CurrentStoreContext = createContext<CurrentStoreContextValue | null>(null)

export function useCurrentStore(): CurrentStoreContextValue {
  const value = useContext(CurrentStoreContext)
  if (value === null) {
    throw new Error(
      'useCurrentStore는 CurrentStoreProvider 안에서만 사용할 수 있습니다.',
    )
  }
  return value
}

function readStoredStoreId(): string | null {
  try {
    return window.sessionStorage.getItem(STORAGE_KEY)
  } catch {
    // 저장소 접근이 막힌 환경에서도 화면은 동작해야 한다. 이번 세션만 유지한다.
    return null
  }
}

function writeStoredStoreId(storeId: string | null): void {
  try {
    if (storeId === null) {
      window.sessionStorage.removeItem(STORAGE_KEY)
    } else {
      window.sessionStorage.setItem(STORAGE_KEY, storeId)
    }
  } catch {
    // 저장 실패는 기능 실패가 아니다. 메모리 상태만으로 계속 진행한다.
  }
}

export function CurrentStoreProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const { status: authStatus } = useStoreOperatorAuth()
  const [storeId, setStoreId] = useState<string | null>(readStoredStoreId)

  /**
   * 매장이 바뀌면 이전 매장의 진행 중 요청을 취소하고 캐시를 지운다.
   *
   * 지우지 않으면 A 매장 데이터가 B 매장 화면에 잠깐 보인다. 모든 운영자 query
   * 키가 매장 ID를 접두사로 가지므로 접두사 하나로 정확히 격리된다.
   */
  const discardStore = useCallback(
    (previousStoreId: string) => {
      const queryKey = storeOperatorKeys.store(previousStoreId)
      void queryClient.cancelQueries({ queryKey })
      queryClient.removeQueries({ queryKey })
    },
    [queryClient],
  )

  /*
   * 현재 값을 ref로도 들고 있는다.
   *
   * setState 갱신 함수 안에서 캐시를 지우면 React가 그 함수를 두 번 부를 때
   * 부수효과도 두 번 일어난다. 갱신 함수는 순수하게 두고, 부수효과는 이벤트
   * 처리기 본문에서 한 번만 실행한다.
   */
  const storeIdRef = useRef<string | null>(storeId)

  const selectStore = useCallback(
    (nextStoreId: string) => {
      const previous = storeIdRef.current
      if (previous === nextStoreId) {
        return
      }
      if (previous !== null) {
        discardStore(previous)
      }
      storeIdRef.current = nextStoreId
      writeStoredStoreId(nextStoreId)
      setStoreId(nextStoreId)
    },
    [discardStore],
  )

  const clearStore = useCallback(() => {
    const previous = storeIdRef.current
    if (previous !== null) {
      discardStore(previous)
    }
    storeIdRef.current = null
    writeStoredStoreId(null)
    setStoreId(null)
  }, [discardStore])

  useEffect(() => {
    if (authStatus !== 'unauthenticated') {
      return
    }

    storeIdRef.current = null
    writeStoredStoreId(null)
    setStoreId(null)

    /*
     * 현재 매장만 지우면 이전 계정의 내 정보·카탈로그처럼 매장 밖 query가
     * 남는다. 인증 경계가 닫힐 때는 운영자 namespace 전체를 취소한 뒤 제거해,
     * 늦게 끝난 이전 세션 요청도 다음 계정 캐시에 들어오지 못하게 한다.
     */
    void queryClient
      .cancelQueries({ queryKey: storeOperatorKeys.all })
      .then(() => queryClient.removeQueries({ queryKey: storeOperatorKeys.all }))
  }, [authStatus, queryClient])

  const value = useMemo(
    () => ({ storeId, selectStore, clearStore }),
    [storeId, selectStore, clearStore],
  )

  return (
    <CurrentStoreContext.Provider value={value}>
      {children}
    </CurrentStoreContext.Provider>
  )
}

/**
 * 경로의 매장 ID를 현재 매장으로 채택한다.
 *
 * 경로가 진실의 출처다. 운영자가 다른 매장 경로를 직접 열면 그 매장이 현재
 * 매장이 되고, 이전 매장의 query·폼 draft는 provider가 격리한다.
 */
export function useAdoptStoreFromRoute(storeId: string | undefined): void {
  const { selectStore } = useCurrentStore()

  useEffect(() => {
    if (storeId !== undefined && storeId.length > 0) {
      selectStore(storeId)
    }
  }, [storeId, selectStore])
}
