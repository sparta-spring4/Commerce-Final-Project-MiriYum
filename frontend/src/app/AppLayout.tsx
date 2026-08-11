import { Link, NavLink, Outlet } from 'react-router'
import { NAVIGATION, ROUTES, type Shell } from './routes'

interface Props {
  shell: Shell
}

/**
 * 공통 레이아웃. 네비게이션 항목은 계정 shell별로 분리한다.
 * 두 shell의 인증 상태나 권한을 UI 편의로 합치지 않는다.
 */
export function AppLayout({ shell }: Props) {
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
          {items.length > 0 && (
            <nav aria-label="주 메뉴">
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
        </div>
      </header>

      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>

      <footer className="app-footer">
        <div className="mi-container">
          <p className="app-footer__brand">MiriYum</p>
          <p>맛있는 기다림을 줄여 주는 예약·픽업 서비스입니다.</p>
        </div>
      </footer>
    </>
  )
}
