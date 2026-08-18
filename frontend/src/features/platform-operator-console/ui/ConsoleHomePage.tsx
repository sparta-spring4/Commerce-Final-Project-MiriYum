import { Link, Navigate } from 'react-router'
import { Alert, EmptyState } from '../../../shared/ui/Feedback'
import {
  decideAnyCapability,
  usePlatformOperatorAuth,
} from '../../platform-operator-auth'
import { CONSOLE_NAVIGATION } from '../model/navigation'
import './page.css'

/**
 * 콘솔 진입 화면.
 *
 * 예전에는 `/admin`을 회원 관리로 곧바로 redirect했다. 그러면 콘솔에 들어오는
 * 것만으로 회원 조회 query가 나가고, 조회 권한이 없는 운영자에게는 403과
 * 감사 기록이 남는다. 운영자가 아무것도 고르지 않았는데 시스템이 대신 업무
 * 요청을 보낸 셈이다.
 *
 * 그래서 자동 이동을 없애고 중립 화면을 둔다. 여기서는 어떤 업무 query도
 * 실행하지 않는다. 운영자가 항목을 직접 고를 때 비로소 조회가 시작된다.
 *
 * 권한 계약(#403)이 들어오면 서버가 알려 준 권한으로 판정이 바뀐다.
 * 갈 수 있는 곳이 하나뿐이면 그때는 자동 이동이 안전해진다.
 */
export function ConsoleHomePage() {
  const { capabilities } = usePlatformOperatorAuth()

  const visible = CONSOLE_NAVIGATION.filter(
    (item) => decideAnyCapability(capabilities, item.permissions) !== 'denied',
  )

  // 권한을 알고, 갈 수 있는 곳이 정확히 하나면 곧바로 보낸다.
  // 이 판정은 `loaded` 상태에서만 성립한다.
  if (capabilities.status === 'loaded' && visible.length === 1) {
    return <Navigate to={visible[0].path} replace />
  }

  if (capabilities.status === 'loaded' && visible.length === 0) {
    return (
      <EmptyState
        title="접근 권한이 있는 업무가 없습니다."
        description="담당 업무 권한이 필요하면 슈퍼관리자에게 문의해 주세요."
      />
    )
  }

  return (
    <section aria-labelledby="console-home-heading">
      <header className="po-page__header">
        <div>
          <h1 className="po-page__title" id="console-home-heading">
            운영 콘솔
          </h1>
          <p className="po-page__subtitle">
            수행할 업무를 선택해 주세요.
          </p>
        </div>
      </header>

      {capabilities.status === 'unknown' && (
        <Alert tone="info" title="권한 확인 기능이 아직 연결되지 않았습니다.">
          현재 운영자의 권한을 조회하는 API가 준비되면 권한 없는 업무가 목록에서
          자동으로 빠집니다. 지금은 모든 업무가 보이며, 권한이 없는 업무를
          선택하면 서버가 거절합니다. 콘솔에 들어오는 것만으로는 어떤 조회도
          실행되지 않습니다.
        </Alert>
      )}

      <ul className="po-home__list">
        {visible.map((item) => (
          <li key={item.path}>
            <Link to={item.path} className="po-home__card">
              <span className="po-home__card-label">{item.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
