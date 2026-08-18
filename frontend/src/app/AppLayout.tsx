import type { ReactNode } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { Icon, type IconName } from '../shared/ui/Icon'
import { NAVIGATION, ROUTES, type Shell } from './routes'

interface Props {
  shell: Shell
  /**
   * 계정 영역 슬롯. 레이아웃이 특정 shell의 인증 상태를 직접 읽지 않게 한다.
   * 두 shell이 각자의 계정 메뉴를 넣고 서로의 상태를 보지 않는다.
   */
  accountSlot?: ReactNode
}

/**
 * 하단 탭의 아이콘.
 *
 * 경로로 찾는다. 레이블 문자열로 찾으면 문구를 다듬을 때마다 아이콘이 조용히
 * 사라진다. 표에 없는 경로는 글자만 나오므로 탭이 깨지지는 않는다.
 */
const TAB_ICON: Record<string, IconName> = {
  [ROUTES.home]: 'store',
  [ROUTES.stores]: 'search',
  [ROUTES.myReservations]: 'calendar',
  [ROUTES.myPage]: 'person',
}

/**
 * 공통 레이아웃. 네비게이션 항목은 계정 shell별로 분리한다.
 * 두 shell의 인증 상태나 권한을 UI 편의로 합치지 않는다.
 */
export function AppLayout({ shell, accountSlot }: Props) {
  const items = NAVIGATION[shell]

  return (
    <>
      {/* 키보드 사용자가 반복되는 네비게이션을 건너뛸 수 있게 한다. */}
      <a className="app-skip-link" href="#main">
        본문으로 건너뛰기
      </a>

      <header className="app-header">
        <div className="mi-container app-header__inner">
          <Link className="app-header__brand" to={ROUTES.home}>
            MiriYum
          </Link>
          {/*
            시안은 좁은 화면에서 상단 메뉴를 감추고 하단 탭 바로 옮긴다.
            같은 항목을 두 곳에서 읽히게 하지 않으려고 상단은 aria-hidden이
            아니라 CSS로 숨긴다. 하단 탭이 같은 역할을 대신한다.
          */}
          {items.length > 0 && (
            <nav aria-label="주 메뉴" className="app-header__menu">
              <ul className="app-header__nav">
                {items.map((item) => (
                  <li key={item.path}>
                    <NavLink
                      to={item.path}
                      className={({ isActive }) =>
                        isActive
                          ? 'app-header__link app-header__link--active'
                          : 'app-header__link'
                      }
                    >
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          )}
          {accountSlot}
        </div>
      </header>

      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>

      {/*
        모바일 하단 탭. 시안의 "Bottom Navigation for ergonomic one-handed use".
        상단 메뉴와 같은 항목 표를 쓰므로 두 곳이 어긋나지 않는다.
      */}
      {items.length > 0 && (
        <nav className="app-tabbar" aria-label="바로가기">
          <ul className="app-tabbar__list">
            {items.map((item) => {
              const icon = TAB_ICON[item.path]
              return (
                <li key={item.path} className="app-tabbar__item">
                  <NavLink
                    to={item.path}
                    className={({ isActive }) =>
                      isActive
                        ? 'app-tabbar__link app-tabbar__link--active'
                        : 'app-tabbar__link'
                    }
                  >
                    {icon !== undefined && <Icon name={icon} />}
                    <span>{item.label}</span>
                  </NavLink>
                </li>
              )
            })}
          </ul>
        </nav>
      )}

      <footer className="app-footer">
        <div className="mi-container">
          <div className="app-footer__columns">
            <div>
              <p className="app-footer__brand">MiriYum</p>
              <p className="app-footer__tagline">
                줄 서지 않고 즐기는 예약·메뉴 미리 선택·픽업 서비스입니다.
              </p>
            </div>

            {/*
              실제로 존재하는 route만 건다. 준비 중 링크나 빈 페이지로 가는
              항목을 만들지 않는다.
            */}
            <nav aria-label="서비스 메뉴">
              <p className="app-footer__heading">서비스</p>
              <div className="app-footer__links">
                <Link to={ROUTES.stores}>매장 찾기</Link>
                {shell === 'consumer' && (
                  <Link to={ROUTES.myReservations}>내 예약</Link>
                )}
              </div>
            </nav>

            {/*
              로그인한 사용자에게 로그인·회원가입을 계속 보이지 않는다. 이미 끝난
              일을 남겨 두면 지금 상태가 아닌 화면을 읽게 된다. 로그아웃은 헤더
              계정 메뉴가 단독으로 가지므로 푸터에 두 번 두지 않는다.
            */}
            <nav aria-label="계정 메뉴">
              <p className="app-footer__heading">계정</p>
              <div className="app-footer__links">
                {shell === 'consumer' ? (
                  <Link to={ROUTES.myPage}>마이페이지</Link>
                ) : (
                  <>
                    <Link to={ROUTES.consumerSignIn}>로그인</Link>
                    <Link to={ROUTES.consumerSignUp}>회원가입</Link>
                  </>
                )}
              </div>
            </nav>
          </div>

          <p className="app-footer__note">
            표시되는 예약 가능 여부는 조회 시점 기준이며 최종 확정은 예약 시점에
            서버가 판정합니다.
          </p>
        </div>
      </footer>
    </>
  )
}
