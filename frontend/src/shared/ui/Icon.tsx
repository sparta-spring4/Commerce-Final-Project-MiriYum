/**
 * 화면 아이콘.
 *
 * 시안은 Google Material Symbols 웹폰트를 쓰지만, theme.css가 정한 대로 외부
 * 폰트 호스트에 의존하지 않는다. 같은 자리에 들어갈 글리프만 inline SVG로
 * 직접 그려 둔다. 선 굵기와 둥근 끝 처리는 시안의 둥근 형태 언어에 맞췄다.
 *
 * 기본값은 장식이다(`aria-hidden`). 아이콘만으로 뜻을 전달해야 하는 자리에서는
 * `label`을 주면 `role="img"`와 접근 가능한 이름이 붙는다. 색은 언제나
 * `currentColor`를 따르므로 부모의 color만 정하면 된다.
 */

export type IconName =
  | 'search'
  | 'mail'
  | 'lock'
  | 'eye'
  | 'eyeOff'
  | 'alert'
  | 'info'
  | 'check'
  | 'checkCircle'
  | 'calendar'
  | 'clock'
  | 'group'
  | 'person'
  | 'pin'
  | 'menu'
  | 'arrowRight'
  | 'arrowLeft'
  | 'plus'
  | 'minus'
  | 'close'
  | 'chevronLeft'
  | 'chevronRight'
  | 'store'
  | 'edit'
  | 'phone'
  | 'bag'

/**
 * 24×24 좌표계의 선 그림.
 *
 * 채우기가 필요한 글리프가 아직 없어 전부 stroke로 그린다. 채우기를 쓰는
 * 글리프를 추가할 때는 해당 항목에만 `fill` 속성을 준다.
 */
const PATHS: Record<IconName, React.ReactNode> = {
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M16.5 16.5 21 21" />
    </>
  ),
  mail: (
    <>
      <rect x="2.5" y="4.5" width="19" height="15" rx="2.5" />
      <path d="m3.5 7 8.5 5.5L20.5 7" />
    </>
  ),
  lock: (
    <>
      <rect x="4" y="10" width="16" height="10.5" rx="2.5" />
      <path d="M7.75 10V7.25a4.25 4.25 0 0 1 8.5 0V10" />
    </>
  ),
  eye: (
    <>
      <path d="M2.5 12S6.3 5.75 12 5.75 21.5 12 21.5 12 17.7 18.25 12 18.25 2.5 12 2.5 12Z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  eyeOff: (
    <>
      <path d="M9.9 5.98A9.3 9.3 0 0 1 12 5.75c5.7 0 9.5 6.25 9.5 6.25a17.6 17.6 0 0 1-3.2 3.83M6.4 7.9A17.5 17.5 0 0 0 2.5 12S6.3 18.25 12 18.25c1.6 0 3-.5 4.2-1.2" />
      <path d="M10 10a2.9 2.9 0 0 0 4 4" />
      <path d="m3.5 3.5 17 17" />
    </>
  ),
  alert: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.25" />
      <path d="M12 16.25h.01" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11.25v5" />
      <path d="M12 7.75h.01" />
    </>
  ),
  check: <path d="m4.5 12.5 5 5 10-11" />,
  checkCircle: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8.25 12.25 2.6 2.6 4.9-5.4" />
    </>
  ),
  calendar: (
    <>
      <rect x="3.5" y="5" width="17" height="15.5" rx="2.5" />
      <path d="M3.5 9.75h17" />
      <path d="M8.25 3.5v3M15.75 3.5v3" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.25V12l3.25 1.9" />
    </>
  ),
  group: (
    <>
      <circle cx="9.5" cy="8.75" r="3.25" />
      <path d="M3.25 19.5c0-3.2 2.8-5 6.25-5s6.25 1.8 6.25 5" />
      <path d="M16 5.9a3.25 3.25 0 0 1 0 6.2" />
      <path d="M17.75 14.9c1.85.6 3 1.98 3 4.6" />
    </>
  ),
  person: (
    <>
      <circle cx="12" cy="8.5" r="3.75" />
      <path d="M4.75 20.25c0-4 3.25-6.25 7.25-6.25s7.25 2.25 7.25 6.25" />
    </>
  ),
  pin: (
    <>
      <path d="M12 21.25s7-6.35 7-11.25a7 7 0 1 0-14 0c0 4.9 7 11.25 7 11.25Z" />
      <circle cx="12" cy="9.75" r="2.6" />
    </>
  ),
  menu: (
    <>
      <path d="M6 3v6.25a2.25 2.25 0 0 0 4.5 0V3" />
      <path d="M8.25 11.5V21" />
      <path d="M18 3c-1.6 1.2-2.25 3.1-2.25 5.25S16.4 11.5 18 11.5V21" />
    </>
  ),
  arrowRight: (
    <>
      <path d="M4.5 12h14" />
      <path d="m13 6.5 5.5 5.5-5.5 5.5" />
    </>
  ),
  arrowLeft: (
    <>
      <path d="M19.5 12h-14" />
      <path d="m11 6.5-5.5 5.5L11 17.5" />
    </>
  ),
  plus: <path d="M12 5.5v13M5.5 12h13" />,
  minus: <path d="M5.5 12h13" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  chevronLeft: <path d="m14.5 5.5-6 6.5 6 6.5" />,
  chevronRight: <path d="m9.5 5.5 6 6.5-6 6.5" />,
  store: (
    <>
      <path d="M4.25 10.25V20a.75.75 0 0 0 .75.75h14a.75.75 0 0 0 .75-.75v-9.75" />
      <path d="M3 6.5 4.6 3.25h14.8L21 6.5a3 3 0 0 1-5.25 2.4 3 3 0 0 1-3.75.9 3 3 0 0 1-3.75-.9A3 3 0 0 1 3 6.5Z" />
      <path d="M9.75 20.75v-5.5h4.5v5.5" />
    </>
  ),
  edit: (
    <>
      <path d="M16.4 3.85a2.3 2.3 0 0 1 3.25 3.25L8.5 18.25l-4.25 1 1-4.25Z" />
      <path d="m14.5 5.75 3.25 3.25" />
    </>
  ),
  phone: (
    <path d="M7.4 3.5 9.6 8 7.9 9.9a13 13 0 0 0 5.7 5.7l1.9-1.7 4.5 2.2v3a2 2 0 0 1-2.2 2A17.5 17.5 0 0 1 3.3 5.7 2 2 0 0 1 5.3 3.5Z" />
  ),
  bag: (
    <>
      <path d="M4.75 7.75h14.5l-1.1 12a1.5 1.5 0 0 1-1.5 1.35H7.35a1.5 1.5 0 0 1-1.5-1.35Z" />
      <path d="M8.75 10.5v-3a3.25 3.25 0 0 1 6.5 0v3" />
    </>
  ),
}

interface Props {
  name: IconName
  /** 아이콘만으로 뜻을 전달하는 자리에서 접근 가능한 이름을 준다. */
  label?: string
  className?: string
}

export function Icon({ name, label, className }: Props) {
  const decorative = label === undefined

  return (
    <svg
      className={['mi-icon', className].filter(Boolean).join(' ')}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      // 장식 아이콘은 접근성 트리에서 지운다. focusable은 구형 IE/Edge에서
      // SVG가 탭 순서에 끼어드는 것을 막는다.
      aria-hidden={decorative || undefined}
      focusable="false"
      role={decorative ? undefined : 'img'}
      aria-label={label}
    >
      {PATHS[name]}
    </svg>
  )
}
