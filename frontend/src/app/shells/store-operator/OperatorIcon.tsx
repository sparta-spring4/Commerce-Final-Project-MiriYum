/**
 * 운영자 화면 아이콘.
 *
 * 시안은 Material Symbols를 Google Fonts에서 불러 쓴다. 이 저장소는 외부 폰트
 * 호스트에 의존하지 않기로 했으므로(`theme.css`의 타이포그래피 주석) 같은 자리에
 * 인라인 SVG를 둔다. 번들 밖 요청이 없고 CSP를 넓히지 않는다.
 *
 * 아이콘은 언제나 장식이다. 의미는 옆의 문구가 전달하고, 여기서는
 * `aria-hidden`으로 보조기술에서 감춘다. 아이콘만 있는 버튼은 호출부가
 * `aria-label`을 직접 붙인다.
 */

export type OperatorIconName =
  | 'home'
  | 'store'
  | 'clock'
  | 'calendar'
  | 'calendar-off'
  | 'menu-book'
  | 'users'
  | 'timer'
  | 'list'
  | 'user'
  | 'logout'
  | 'plus'
  | 'search'
  | 'edit'
  | 'check'
  | 'close'
  | 'lock'
  | 'arrow-right'
  | 'bars'
  | 'save'
  | 'pin'

/**
 * 24×24 격자, 획 기반. 색은 `currentColor`를 따르므로 부모의 색 규칙 하나가
 * 문구와 아이콘에 함께 적용된다.
 */
const PATHS: Record<OperatorIconName, string> = {
  home: 'M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z',
  store:
    'M4 9V6.8a1 1 0 0 1 .3-.7L6 4.4a1 1 0 0 1 .7-.4h10.6a1 1 0 0 1 .7.4l1.7 1.7a1 1 0 0 1 .3.7V9m-16 0a2.5 2.5 0 0 0 4 0 2.5 2.5 0 0 0 4 0 2.5 2.5 0 0 0 4 0 2.5 2.5 0 0 0 4 0m-14 2.5V20h12v-8.5',
  clock: 'M12 7v5l3.5 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  calendar:
    'M8 3v3m8-3v3M4 9h16M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z',
  'calendar-off':
    'M8 3v3m8-3v3M4 9h16M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Zm4.5 8.5 5 5m0-5-5 5',
  'menu-book':
    'M12 7.5C10.5 6 8.5 5.5 5 5.5v12c3.5 0 5.5.5 7 2 1.5-1.5 3.5-2 7-2v-12c-3.5 0-5.5.5-7 2Zm0 0V19',
  users:
    'M16 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 18.5V20m6.5-9.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM20 20v-1.5a3.5 3.5 0 0 0-2.6-3.4M15.5 4.6a3 3 0 0 1 0 5.8',
  timer:
    'M12 9.5V13l2 1.5M9.5 3h5M12 21a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15Zm6.5-13 1.5-1.5',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
  user: 'M19 20v-1.5a4.5 4.5 0 0 0-4.5-4.5h-5A4.5 4.5 0 0 0 5 18.5V20M12 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z',
  logout: 'M15 17l5-5-5-5m5 5H9m1-8H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h5',
  plus: 'M12 5v14M5 12h14',
  search: 'M20 20l-3.6-3.6M18.5 11a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z',
  edit: 'M4 20h4l10-10a2.1 2.1 0 0 0-3-3L5 17v3Zm10.5-12.5 3 3',
  check: 'M5 12.5l4.5 4.5L19 7',
  close: 'M6 6l12 12M18 6L6 18',
  lock: 'M8 10V7.5a4 4 0 0 1 8 0V10M6.5 10h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1Z',
  'arrow-right': 'M5 12h14m-6-6 6 6-6 6',
  bars: 'M4 7h16M4 12h16M4 17h16',
  save: 'M6 4h9l3 3v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Zm2 0v5h7V4m-7 16v-5h8v5',
  pin: 'M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11Zm0-8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
}

/**
 * 관리 화면 이름별 아이콘.
 *
 * 사이드바와 운영 홈의 바로가기가 같은 그림을 써야 두 자리가 같은 화면을
 * 가리킨다는 것이 보인다. 경로가 아니라 라벨을 열쇠로 둔 이유는 `routes.ts`가
 * 항목을 바꿔도 조용히 어긋나지 않게 하려는 것이다. 모르는 라벨은 호출부가
 * 기본값으로 떨어뜨린다.
 */
export const OPERATOR_NAV_ICONS: Record<string, OperatorIconName> = {
  '매장 정보': 'store',
  영업시간: 'clock',
  '예약 접수 시간대': 'calendar',
  '휴무·휴점': 'calendar-off',
  '메뉴 관리': 'menu-book',
  '예약 수용량': 'users',
  '예약 시간 정책': 'timer',
  '예약 목록': 'list',
}

export function OperatorIcon({
  name,
  className,
}: {
  name: OperatorIconName
  className?: string
}) {
  return (
    <svg
      className={['op-icon', className].filter(Boolean).join(' ')}
      viewBox="0 0 24 24"
      width="20"
      height="20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  )
}
