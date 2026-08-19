import { PLATFORM_OPERATOR_PATHS } from '../../routes/paths/platformOperatorPaths'
import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { decideAnyCapability } from '../../../domains/account/platform-operator/auth/model/capabilities'
import { usePlatformOperatorAuth } from './PlatformOperatorAuthProvider'
import { CONSOLE_NAVIGATION } from './navigation'
import { OPERATOR_ROLE_LABEL } from '../../../domains/platform-operation/platform-operator/model/operatorLabels'
import './console.css'

/**
 * 운영 콘솔 셸.
 *
 * 시안의 좌측 내비·상단 바 구조를 따른다. 다만 시안이 그린 9개 항목 중
 * 서버 계약이 있는 3개만 둔다. 전역 검색 입력과 알림 종은 계약이 없어서
 * 넣지 않는다. 누를 수 있게 그려 두면 동작하지 않는 컨트롤이 된다.
 *
 * `/me`는 개인정보 없이 현재 역할과 최종 권한만 반환한다.
 */
export function ConsoleLayout() {
  const {
    capabilities,
    currentCapabilities,
    retryCapabilities,
    sessionDeadlines,
    signOut,
  } = usePlatformOperatorAuth()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)

  /**
   * 로그아웃.
   *
   * 서버 폐기를 확인하지 못한 경우의 경고를 여기서 렌더링하지 않는다.
   * `signOut()`은 결과와 무관하게 `clearSession()`을 부르고, status가
   * `unauthenticated`가 되는 즉시 가드가 로그인으로 redirect해 이 레이아웃을
   * unmount한다. 여기에 상태를 두면 경고가 한 번도 그려지지 않는다.
   *
   * 그래서 provider가 `signOutNotice`를 들고 있고, 가드 바깥의 로그인 화면이
   * 그것을 표시한다. 공용 PC에서 세션이 남았을 수 있다는 안내는 사용자가
   * 실제로 볼 수 있는 자리에 있어야 한다.
   */
  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
      void navigate(PLATFORM_OPERATOR_PATHS.signIn, { replace: true })
    } finally {
      setSigningOut(false)
    }
  }

  return (
    <div className="po-console">
      <aside className="po-console__nav" aria-label="운영 콘솔 메뉴">
        <div className="po-console__brand">
          <span className="po-console__brand-mark">MiriYum</span>
          <span className="po-console__brand-suffix">HQ</span>
        </div>

        <nav>
          <ul className="po-console__nav-list">
            {capabilities.status === 'loaded' && CONSOLE_NAVIGATION.map((item) => {
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
          {currentCapabilities !== null &&
            currentCapabilities.roles.length > 0 && (
              <p className="po-console__session">
                <span className="po-console__session-label">현재 역할</span>
                <strong>
                  {currentCapabilities.roles
                    .map((role) => OPERATOR_ROLE_LABEL[role])
                    .join(', ')}
                </strong>
              </p>
            )}
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
          {capabilities.status === 'unknown' ? (
            capabilities.reason === 'loading' ? (
              <Loading label="현재 운영자 권한을 확인하는 중입니다." />
            ) : (
              <ErrorState
                error={capabilities.error}
                message="현재 운영자 권한을 불러오지 못했습니다."
                onRetry={retryCapabilities}
              />
            )
          ) : (
            <Outlet />
          )}
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
