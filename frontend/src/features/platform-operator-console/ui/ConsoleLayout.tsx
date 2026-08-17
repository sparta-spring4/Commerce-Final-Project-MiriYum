import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { Alert } from '../../../shared/ui/Feedback'
import {
  decideAnyCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import { CONSOLE_NAVIGATION } from '../model/navigation'
import './console.css'

/**
 * 운영 콘솔 셸.
 *
 * 시안의 좌측 내비·상단 바 구조를 따른다. 다만 시안이 그린 9개 항목 중
 * 서버 계약이 있는 3개만 둔다. 전역 검색 입력과 알림 종은 계약이 없어서
 * 넣지 않는다. 누를 수 있게 그려 두면 동작하지 않는 컨트롤이 된다.
 *
 * 운영자 식별 배지도 시안에는 "운영자-092 / 최고 관리자"로 그려져 있지만,
 * 현재 계약에는 자기 자신을 조회하는 endpoint가 없어 이름도 역할도 알 수 없다.
 * 모르는 값을 그럴듯하게 채우지 않는다. #403이 들어오면 이 자리에 넣는다.
 */
export function ConsoleLayout() {
  const { capabilities, sessionDeadlines, signOut } = usePlatformOperatorAuth()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)
  const [signOutError, setSignOutError] = useState<string | null>(null)

  async function handleSignOut() {
    setSigningOut(true)
    setSignOutError(null)
    try {
      const outcome = await signOut()
      if (outcome === 'unconfirmed') {
        // 로컬 자격은 비웠지만 서버 세션이 살아 있을 수 있다. 공용 PC에서는
        // 사용자가 조치를 더 해야 하므로 조용히 로그인 화면으로 보내지 않는다.
        setSignOutError(
          '로그아웃은 됐지만 서버 세션 종료를 확인하지 못했습니다. 공용 PC라면 슈퍼관리자에게 알려 주세요.',
        )
        return
      }
      void navigate(ROUTES.platformOperatorSignIn, { replace: true })
    } finally {
      setSigningOut(false)
    }
  }

  const capabilitiesUnknown = capabilities.status === 'unknown'

  return (
    <div className="po-console">
      <aside className="po-console__nav" aria-label="운영 콘솔 메뉴">
        <div className="po-console__brand">
          <span className="po-console__brand-mark">MiriYum</span>
          <span className="po-console__brand-suffix">HQ</span>
        </div>

        <nav>
          <ul className="po-console__nav-list">
            {CONSOLE_NAVIGATION.map((item) => {
              const decision = decideAnyCapability(
                capabilities,
                item.permissions,
              )
              // 권한이 없다고 서버가 확인해 준 항목만 감춘다. 아직 모르는
              // 상태에서 감추면 계약이 생기기 전까지 콘솔이 빈 화면이 된다.
              if (decision === 'denied') {
                return null
              }
              return (
                <li key={item.path}>
                  <NavLink
                    to={item.path}
                    className={({ isActive }) =>
                      isActive
                        ? 'po-console__nav-link po-console__nav-link--active'
                        : 'po-console__nav-link'
                    }
                  >
                    {item.label}
                  </NavLink>
                </li>
              )
            })}
          </ul>
        </nav>

        <button
          type="button"
          className="po-console__signout"
          onClick={handleSignOut}
          disabled={signingOut}
        >
          {signingOut ? '로그아웃하는 중…' : '로그아웃'}
        </button>
      </aside>

      <div className="po-console__main">
        <header className="po-console__header">
          {sessionDeadlines !== null && (
            <p className="po-console__session">
              <span className="po-console__session-label">세션 만료</span>
              <time dateTime={sessionDeadlines.absoluteExpiresAt}>
                {formatDeadline(sessionDeadlines.absoluteExpiresAt)}
              </time>
            </p>
          )}
        </header>

        <div className="po-console__content">
          {signOutError !== null && (
            <Alert tone="warning" title={signOutError} />
          )}
          {capabilitiesUnknown && (
            <Alert
              tone="info"
              title="권한별 메뉴 표시가 아직 적용되지 않았습니다."
            >
              현재 운영자의 권한을 조회하는 API가 준비되면 권한 없는 메뉴가
              자동으로 숨겨집니다. 지금은 모든 메뉴가 보이며, 권한이 없는 업무는
              서버가 거절합니다.
            </Alert>
          )}
          <Outlet />
        </div>
      </div>
    </div>
  )
}

/**
 * 서버가 준 만료 시각만 보여 준다.
 *
 * "15분 뒤"처럼 남은 시간을 계산해 적지 않는다. 유휴 만료는 활동할 때마다
 * 밀리므로 계산값이 곧 틀리고, 절대 만료 수치는 배포 설정에 따라 다르다.
 */
function formatDeadline(isoTimestamp: string): string {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp
  }
  return parsed.toLocaleString('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
  })
}
