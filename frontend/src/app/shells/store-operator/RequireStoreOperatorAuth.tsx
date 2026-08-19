import { STORE_OPERATOR_PATHS } from '../../routes/paths/storeOperatorPaths'
import { Navigate, Outlet, useLocation } from 'react-router'
import { withReturnTo } from '../../returnTo'
import { Loading } from '../../../shared/ui/Feedback'
import { useStoreOperatorAuth } from './StoreOperatorAuthProvider'

/**
 * 매장 운영자 보호 route 가드.
 *
 * 세션 복구가 끝나기 전에 판정하지 않는다. 일반 사용자 가드와 코드를 합치지
 * 않는 것은 한 가드가 두 namespace를 판정하면 한쪽 토큰으로 다른 쪽 화면이
 * 열릴 수 있기 때문이다.
 */
export function RequireStoreOperatorAuth() {
  const { status } = useStoreOperatorAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return <Loading label="로그인 상태를 확인하는 중입니다." />
  }

  if (status === 'unauthenticated') {
    const destination = `${location.pathname}${location.search}${location.hash}`
    return (
      <Navigate
        to={withReturnTo(STORE_OPERATOR_PATHS.signIn, destination)}
        replace
      />
    )
  }

  return <Outlet />
}
