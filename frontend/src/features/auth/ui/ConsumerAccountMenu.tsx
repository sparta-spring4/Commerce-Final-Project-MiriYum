import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { Button } from '../../../shared/ui/Button'
import { Icon } from '../../../shared/ui/Icon'
import { useConsumerAuth } from '../ConsumerAuthProvider'

/**
 * 헤더의 일반 사용자 계정 영역.
 *
 * 세션 복구 중에는 로그인·로그아웃 중 어느 쪽도 보여 주지 않는다. 복구 결과가
 * 나오기 전에 "로그인"을 띄우면 이미 로그인한 사용자에게 잘못된 상태를 보인다.
 */
export function ConsumerAccountMenu() {
  const { status, signOut, signOutNotice, dismissSignOutNotice } =
    useConsumerAuth()
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
      <>
        <div className="app-header__account">
          <Link className="mi-button mi-button--ghost mi-button--sm" to={ROUTES.consumerSignIn}>
            로그인
          </Link>
          <Link className="mi-button mi-button--primary mi-button--sm" to={ROUTES.consumerSignUp}>
            회원가입
          </Link>
        </div>

        {/*
          서버 폐기를 확인하지 못한 로그아웃을 완료로 보이게 두지 않는다.
          이 기기에서는 나갔지만 서버 세션은 남아 있을 수 있다.

          계정 영역 안이 아니라 그 옆에 둔다. 헤더 안쪽이 flex 행이라 안내를
          계정 영역에 넣으면 좁은 화면에서 브랜드와 버튼을 밀어낸다. 형제로
          두면 자기 줄을 통째로 차지한다.
        */}
        {signOutNotice === 'unconfirmed' && (
          <p className="app-header__notice" role="alert">
            <Icon name="alert" className="mi-icon--sm" />
            <span>
              이 기기에서는 로그아웃했지만 서버 세션 종료를 확인하지 못했습니다.
              공용 PC라면 다시 로그인해 로그아웃을 한 번 더 시도해 주세요.
            </span>
            <button
              type="button"
              className="app-header__notice-close"
              aria-label="안내 닫기"
              onClick={dismissSignOutNotice}
            >
              <Icon name="close" className="mi-icon--sm" />
            </button>
          </p>
        )}
      </>
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
