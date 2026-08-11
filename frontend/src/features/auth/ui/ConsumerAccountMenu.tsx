import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { Button } from '../../../shared/ui/Button'
import { useConsumerAuth } from '../ConsumerAuthProvider'

/**
 * 헤더의 일반 사용자 계정 영역.
 *
 * 세션 복구 중에는 로그인·로그아웃 중 어느 쪽도 보여 주지 않는다. 복구 결과가
 * 나오기 전에 "로그인"을 띄우면 이미 로그인한 사용자에게 잘못된 상태를 보인다.
 */
export function ConsumerAccountMenu() {
  const { status, signOut } = useConsumerAuth()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)

  if (status === 'restoring') {
    return (
      <p className="app-header__account" role="status">
        로그인 상태 확인 중
      </p>
    )
  }

  if (status === 'unauthenticated') {
    return (
      <div className="app-header__account">
        <Link className="mi-button mi-button--ghost mi-button--sm" to={ROUTES.consumerSignIn}>
          로그인
        </Link>
        <Link className="mi-button mi-button--primary mi-button--sm" to={ROUTES.consumerSignUp}>
          회원가입
        </Link>
      </div>
    )
  }

  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
      // 로그아웃 뒤 보호 화면에 머무르지 않는다.
      void navigate(ROUTES.home, { replace: true })
    } finally {
      setSigningOut(false)
    }
  }

  return (
    <div className="app-header__account">
      <Button
        variant="ghost"
        size="sm"
        loading={signingOut}
        onClick={() => void handleSignOut()}
      >
        로그아웃
      </Button>
    </div>
  )
}
