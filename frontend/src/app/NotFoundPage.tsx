import { Link } from 'react-router'
import { Icon } from '../shared/ui/Icon'
import { ROUTES } from './routes'

/**
 * 존재하지 않는 경로. 권한 없음(ForbiddenPage)과 구분한다.
 *
 * 시안에 404 화면은 없다. 다른 화면과 같은 여백·형태 언어를 쓰되 새 요소를
 * 만들지 않고 공용 프리미티브만 조합한다.
 */
export function NotFoundPage() {
  return (
    <div className="mi-container mi-container--narrow app-status">
      <span className="app-status__mark" aria-hidden="true">
        <Icon name="search" />
      </span>
      <h1>페이지를 찾을 수 없습니다</h1>
      <p>주소를 확인해 주세요.</p>
      <div className="app-status__actions">
        <Link className="mi-button mi-button--primary" to={ROUTES.home}>
          홈으로 이동
        </Link>
        <Link className="mi-button mi-button--ghost" to={ROUTES.stores}>
          매장 찾기
        </Link>
      </div>
    </div>
  )
}
