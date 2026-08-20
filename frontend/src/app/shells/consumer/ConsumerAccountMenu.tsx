import { CONSUMER_PATHS } from '../../routes/paths/consumerPaths'
import { PUBLIC_PATHS } from '../../routes/paths/publicPaths'
import { CONSUMER_NAVIGATION } from './navigation'
import { useEffect, useId, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Icon, type IconName } from '../../../shared/ui/Icon'
import { useConsumerAuth } from './ConsumerAuthProvider'

/**
 * 계정 메뉴 항목의 아이콘.
 *
 * 경로로 찾는다. 레이블로 찾으면 문구를 다듬을 때마다 아이콘이 조용히 사라진다.
 * `AppLayout`의 하단 탭이 같은 방식을 쓰므로 두 곳의 규칙이 어긋나지 않는다.
 * 표에 없는 경로는 글자만 나오므로 항목이 깨지지는 않는다.
 */
const ITEM_ICON: Record<string, IconName> = {
  [CONSUMER_PATHS.myPage]: 'person',
  [CONSUMER_PATHS.myReservations]: 'calendar',
}

/**
 * 계정 메뉴에 넣을 항목.
 *
 * `CONSUMER_NAVIGATION`에서 가져온다. 여기서 따로 나열하면 route 표와 어긋나고
 * 나중에 화면이 늘 때 한쪽만 갱신된다. 매장 찾기는 로그인 여부와 무관한 공용
 * 항목이고 헤더 주 메뉴가 이미 들고 있어 중복이므로 뺀다.
 */
const LINKED_ITEMS = CONSUMER_NAVIGATION.filter(
  (item) => item.path !== PUBLIC_PATHS.stores,
)

/**
 * 마이페이지를 맨 위에 둔다.
 *
 * 하단 탭 바와 순서가 다르다. 탭 바는 프로필을 맨 끝에 두는 관례를 따르지만,
 * 계정 메뉴는 계정 자체가 주제라 마이페이지가 첫 항목이어야 한다. 나머지는
 * route 표의 순서를 그대로 유지해 새 화면이 붙어도 저절로 뒤에 붙는다.
 */
const MENU_ITEMS = [
  ...LINKED_ITEMS.filter((item) => item.path === CONSUMER_PATHS.myPage),
  ...LINKED_ITEMS.filter((item) => item.path !== CONSUMER_PATHS.myPage),
]

/**
 * 헤더의 일반 사용자 계정 영역.
 *
 * 세션 복구 중에는 로그인·로그아웃 중 어느 쪽도 보여 주지 않는다. 복구 결과가
 * 나오기 전에 "로그인"을 띄우면 이미 로그인한 사용자에게 잘못된 상태를 보인다.
 *
 * 로그인 뒤에는 시안의 원형 계정 버튼을 두고 눌러서 펼친다. 로그아웃 버튼만 두면
 * 마이페이지로 갈 수 있는 클릭 경로가 없다. `AppLayout`의 주 메뉴는 지금 경로가
 * 속한 route 그룹의 shell로 정해지므로, 공용 화면(예: `/`)에 있는 로그인 사용자는
 * 주 메뉴에서 마이페이지를 볼 수 없다. 이 메뉴는 어느 화면에서나 헤더에 있어
 * 그 구멍을 메운다.
 *
 * `role="menu"`를 쓰지 않고 disclosure로 만든다. `menu`를 선언하면 화살표 키로
 * 항목을 옮기는 동작까지 갖춰야 하는데, 여기 항목은 평범한 링크라서 Tab으로
 * 충분하다. 반쪽짜리 `menu`는 선언만 하고 약속을 지키지 않아 더 나쁘다.
 */
export function ConsumerAccountMenu() {
  const { status, signOut, signOutNotice, dismissSignOutNotice } =
    useConsumerAuth()
  const navigate = useNavigate()
  const [signingOut, setSigningOut] = useState(false)
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const wrapperRef = useRef<HTMLDivElement | null>(null)

  /*
   * 바깥을 누르면 닫는다.
   *
   * click이 아니라 pointerdown으로 듣는다. click은 누른 곳과 뗀 곳이 같은 요소여야
   * 발생하므로 패널 위에서 눌러 바깥에서 떼면 열린 채로 남는다. 열려 있을 때만
   * 등록해서 닫힌 동안 문서 리스너를 남기지 않는다.
   */
  useEffect(() => {
    if (!open) {
      return
    }

    function handlePointerDown(event: PointerEvent) {
      const wrapper = wrapperRef.current
      if (wrapper !== null && !wrapper.contains(event.target as Node)) {
        setOpen(false)
      }
    }

    document.addEventListener('pointerdown', handlePointerDown)
    return () => document.removeEventListener('pointerdown', handlePointerDown)
  }, [open])

  /*
   * 로그아웃되면 패널을 접는다.
   *
   * 열어 둔 상태로 로그아웃하면 트리거가 사라지면서 패널만 남아 떠 있는다.
   */
  useEffect(() => {
    if (status !== 'authenticated') {
      setOpen(false)
    }
  }, [status])

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
          <Link className="mi-button mi-button--ghost mi-button--sm" to={CONSUMER_PATHS.signIn}>
            로그인
          </Link>
          <Link className="mi-button mi-button--primary mi-button--sm" to={CONSUMER_PATHS.signUp}>
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

  function close() {
    setOpen(false)
  }

  /** Escape로 닫을 때 초점을 트리거로 돌린다. 초점이 사라지면 키보드 사용자가 길을 잃는다. */
  function closeAndRefocus() {
    setOpen(false)
    triggerRef.current?.focus()
  }

  async function handleSignOut() {
    setSigningOut(true)
    try {
      await signOut()
      setOpen(false)
      // 로그아웃 뒤 보호 화면에 머무르지 않는다.
      void navigate(PUBLIC_PATHS.home, { replace: true })
    } finally {
      setSigningOut(false)
    }
  }

  return (
    <div
      className="app-header__account"
      ref={wrapperRef}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          closeAndRefocus()
        }
      }}
    >
      <button
        type="button"
        ref={triggerRef}
        className="app-header__avatar"
        aria-label="내 계정"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((current) => !current)}
      >
        <Icon name="person" />
      </button>

      {open && (
        <div className="app-header__account-panel" id={panelId}>
          <nav aria-label="계정 메뉴">
            <ul className="app-header__account-list">
              {MENU_ITEMS.map((item) => {
                const icon = ITEM_ICON[item.path]
                return (
                  <li key={item.path}>
                    {/* 같은 경로를 다시 눌러도 패널은 닫아야 한다. 이동이 없으면 열린 채 남는다. */}
                    <Link
                      className="app-header__account-item"
                      to={item.path}
                      onClick={close}
                    >
                      {icon !== undefined && <Icon name={icon} className="mi-icon--sm" />}
                      <span>{item.label}</span>
                    </Link>
                  </li>
                )
              })}
            </ul>
          </nav>

          <button
            type="button"
            className="app-header__account-item app-header__account-item--action"
            disabled={signingOut}
            onClick={() => void handleSignOut()}
          >
            <Icon name="logout" className="mi-icon--sm" />
            <span>{signingOut ? '로그아웃 중' : '로그아웃'}</span>
          </button>
        </div>
      )}
    </div>
  )
}
