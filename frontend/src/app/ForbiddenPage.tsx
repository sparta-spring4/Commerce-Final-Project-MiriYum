import { PUBLIC_PATHS } from './routes/paths/publicPaths'
import { Link } from 'react-router'
import { Icon } from '../shared/ui/Icon'
/**
 * 권한 없는 접근. 없는 경로(NotFoundPage)와 구분한다.
 *
 * 어떤 계정이면 볼 수 있는지, 대상 자원이 존재하는지 알려 주지 않는다.
 * 권한·개인정보·다른 사용자의 상태를 노출하지 않는다.
 *
 * 다음 행동도 같은 이유로 홈과 매장 찾기만 둔다. "로그인하면 볼 수 있다"고
 * 안내하면 그 자원이 존재한다는 사실을 알려 주는 셈이 된다.
 */
export function ForbiddenPage() {
  return (
    <div className="mi-container mi-container--narrow app-status">
      <span className="app-status__mark" aria-hidden="true">
        <Icon name="lock" />
      </span>
      <h1>접근할 수 없습니다</h1>
      <p>이 화면을 볼 수 있는 권한이 없습니다.</p>
      <div className="app-status__actions">
        <Link className="mi-button mi-button--primary" to={PUBLIC_PATHS.home}>
          홈으로 이동
        </Link>
        <Link className="mi-button mi-button--ghost" to={PUBLIC_PATHS.stores}>
          매장 찾기
        </Link>
      </div>
    </div>
  )
}
