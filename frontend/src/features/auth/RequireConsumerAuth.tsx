import { Navigate, Outlet, useLocation } from 'react-router'
import { SIGN_IN_PATH } from '../../app/routes'
import { withReturnTo } from '../../app/returnTo'
import { Loading } from '../../shared/ui/Feedback'
import { useConsumerAuth } from './ConsumerAuthProvider'

/**
 * 일반 사용자 보호 route 가드.
 *
 * 세션 복구가 끝나기 전에 판정하지 않는다. `restoring`을 미인증으로 취급하면
 * 새로고침마다 로그인 화면이 잠깐 보였다가 되돌아온다.
 *
 * 매장 운영자 shell은 자기 가드를 따로 쓴다. 한 가드가 두 namespace를
 * 판정하면 한쪽 토큰으로 다른 쪽 화면이 열릴 수 있다.
 */
export function RequireConsumerAuth() {
  const { status } = useConsumerAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return <Loading label="로그인 상태를 확인하는 중입니다." />
  }

  if (status === 'unauthenticated') {
    const destination = `${location.pathname}${location.search}${location.hash}`
    return (
      <Navigate
        to={withReturnTo(SIGN_IN_PATH.consumer, destination)}
        replace
      />
    )
  }

  return <Outlet />
}
