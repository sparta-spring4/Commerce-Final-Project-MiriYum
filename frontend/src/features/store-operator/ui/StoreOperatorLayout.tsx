import { useState } from 'react'
import { Link, NavLink, Outlet, useMatch, useNavigate } from 'react-router'
import {
  ROUTES,
  storeOperatorNavigation,
  type NavigationItem,
} from '../../../app/routes'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { useCurrentStore } from '../CurrentStoreProvider'
import { useStoreOperatorAuth } from '../StoreOperatorAuthProvider'
import { useManagedStore } from '../api/queries'
import { OPERATION_STATUS_LABEL } from '../model/types'

/**
 * 매장 운영자 보호 화면의 공통 셸.
 *
 * 소비자 셸(`AppLayout`)과 컴포넌트를 공유하지 않는다. 내비게이션 항목·계정
 * 영역·브랜드 링크가 모두 다르고, 무엇보다 두 셸의 인증 상태를 한 곳에서 읽지
 * 않아야 한다.
 *
 * 시안의 매장 선택 드롭다운은 만들지 않는다. 운영자가 소유한 매장 목록을
 * 조회하는 계약이 없어 선택 후보를 채울 방법이 없다.
 */
export function StoreOperatorLayout() {
  const { storeId: currentStoreId } = useCurrentStore()

  /**
   * 경로에서 매장 ID를 읽는다.
   *
   * 이 레이아웃 route에는 경로가 없어 `useParams`가 자식의 `:storeId`를 주지
   * 않는다. 그래서 하위 경로까지 포함해 직접 대조한다. `/stores/new`는 등록
   * 화면이지 매장 ID가 아니므로 제외한다.
   */
  const match = useMatch({ path: ROUTES.storeOperatorStore, end: false })
  const matchedStoreId = match?.params.storeId
  const routeStoreId =
    matchedStoreId !== undefined && matchedStoreId !== 'new'
      ? matchedStoreId
      : undefined

  const storeId = routeStoreId ?? currentStoreId
  const items: NavigationItem[] =
    storeId === null || storeId === undefined
      ? []
      : storeOperatorNavigation(storeId)

  return (
    <div className="op-shell">
      <a className="app-skip-link" href="#main">
        본문으로 건너뛰기
      </a>

      <div className="op-shell__frame">
        <div className="op-sidebar">
          <Link className="op-sidebar__brand" to={ROUTES.storeOperatorHome}>
            MiriYum Partner
          </Link>

          {items.length > 0 && (
            <nav aria-label="매장 관리 메뉴">
              <ul className="op-sidebar__nav">
                {items.map((item) => (
                  <li key={item.path}>
                    <NavLink
                      to={item.path}
                      end
                      className={({ isActive }) =>
                        isActive
                          ? 'op-sidebar__link op-sidebar__link--active'
                          : 'op-sidebar__link'
                      }
                    >
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          )}

          <div className="op-sidebar__footer">
            <StoreOperatorAccountMenu />
          </div>
        </div>

        <div>
          <div className="op-topbar">
            <CurrentStoreSummary storeId={storeId ?? null} />
            <span className="op-topbar__spacer" />
          </div>

          <main className="op-content" id="main" tabIndex={-1}>
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  )
}

/**
 * 상단의 현재 매장 표시.
 *
 * 매장 ID를 알 때만 단건 조회한다. 조회 실패는 이 영역에서 오류 화면을 띄우지
 * 않는다. 본문이 같은 실패를 이미 설명하고, 머리말이 두 번 알릴 필요는 없다.
 */
function CurrentStoreSummary({ storeId }: { storeId: string | null }) {
  const query = useManagedStore(storeId ?? '', storeId !== null)
  const store = storeId === null ? undefined : query.data

  if (storeId === null) {
    return <p className="op-topbar__store">관리 중인 매장을 선택하지 않았습니다</p>
  }

  return (
    <p className="op-topbar__store">
      <span>{store?.name ?? `매장 #${storeId}`}</span>
      {store !== undefined && (
        <Badge tone={store.operationStatus === 'OPEN' ? 'positive' : 'neutral'}>
          {OPERATION_STATUS_LABEL[store.operationStatus]}
        </Badge>
      )}
    </p>
  )
}

/**
 * 계정 영역.
 *
 * 로그아웃은 서버 정리 실패와 무관하게 클라이언트 세션을 비우고, 현재 매장
 * 선택도 함께 지운다. 매장 ID는 토큰이 아니지만 계정에 묶인 값이다. 남겨 두면
 * 같은 브라우저에서 다른 대표자가 로그인했을 때 이전 계정의 매장이 현재 매장으로
 * 잡히고, 그 매장 조회가 `STORE_003`으로 거절되는 화면을 처음 보게 된다.
 */
export function StoreOperatorAccountMenu() {
  const { status, signOut } = useStoreOperatorAuth()
  const { clearStore } = useCurrentStore()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)

  if (status !== 'authenticated') {
    return null
  }

  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
    } catch {
      /*
       * 서버 정리 실패는 사용자가 할 수 있는 일이 없다.
       *
       * provider는 `finally`에서 이미 세션을 비웠고, 남은 것은 서버 쪽 Refresh
       * 토큰뿐이다. 여기서 예외를 흘리면 아래 `finally`가 실행되더라도 호출부의
       * `void handleSignOut()`이 처리되지 않은 거부로 남는다.
       */
    } finally {
      // 서버 응답과 무관하게 클라이언트 상태를 비우고 로그인으로 보낸다.
      // 네트워크 실패로 이 단계를 건너뛰면 매장 ID가 sessionStorage에 남는다.
      clearStore()
      setSigningOut(false)
      void navigate(ROUTES.storeOperatorSignIn, { replace: true })
    }
  }

  return (
    <Button
      variant="ghost"
      size="sm"
      loading={signingOut}
      onClick={() => void handleSignOut()}
    >
      로그아웃
    </Button>
  )
}
