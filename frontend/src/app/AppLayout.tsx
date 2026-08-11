import { Link, Outlet } from 'react-router'
import { NAVIGATION, type Shell } from './routes'

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
      <header>
        <Link to="/">MiriYum</Link>
        {items.length > 0 && (
          <nav aria-label="주 메뉴">
            <ul>
              {items.map((item) => (
                <li key={item.path}>
                  <Link to={item.path}>{item.label}</Link>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </header>
      <main>
        <Outlet />
      </main>
    </>
  )
}
