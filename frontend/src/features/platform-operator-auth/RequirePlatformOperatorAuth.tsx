import { Navigate, Outlet, useLocation } from 'react-router'
import { ROUTES, SIGN_IN_PATH } from '../../app/routes'
import { withReturnTo } from '../../app/returnTo'
import { Loading } from '../../shared/ui/Feedback'
import { usePlatformOperatorAuth } from './PlatformOperatorAuthProvider'

/**
 * 플랫폼 운영자 보호 route 가드.
 *
 * 일반 사용자 가드와 갈라지는 지점이 하나 있다. `restricted`(최초 비밀번호
 * 변경 전) 상태를 통과시키면 안 된다. 자격은 확인됐지만 업무 endpoint가
 * 전부 `AUTH_012`로 막혀 있어서, 콘솔이 열린 채 모든 조회가 실패한다.
 *
 * 이 가드는 화면 접근만 판정한다. 실제 인가는 서버가 권한·사건 배정·재인증으로
 * 다시 판정하며, 그쪽이 최종 결정권을 갖는다.
 */
export function RequirePlatformOperatorAuth() {
  const { status } = usePlatformOperatorAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return <Loading label="운영자 세션을 확인하는 중입니다." />
  }

  if (status === 'unauthenticated') {
    const destination = `${location.pathname}${location.search}${location.hash}`
    return (
      <Navigate
        to={withReturnTo(SIGN_IN_PATH.platformOperator, destination)}
        replace
      />
    )
  }

  if (status === 'restricted') {
    // 돌아올 곳을 남기지 않는다. 비밀번호를 바꾸면 운영 홈에서 다시 시작한다.
    return <Navigate to={ROUTES.platformOperatorInitialPassword} replace />
  }

  return <Outlet />
}
