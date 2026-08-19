import { DAY_LABEL, type DayOfWeek } from '../model/types'

/**
 * 요일 카드의 상태 클래스.
 *
 * 오류 열쇠는 요일 자체(`MONDAY`)와 구간(`MONDAY.slot.0`)의 두 모양이다. 앞이
 * 그 요일인 열쇠가 하나라도 있으면 카드를 오류 상태로 표시한다. 색만으로 알리지
 * 않으며, 카드 안의 오류 문구가 같은 사실을 글로 전한다.
 */
export function dayCardClass(
  dayOfWeek: DayOfWeek,
  open: boolean,
  errors: Readonly<Record<string, string>>,
): string {
  const hasError = Object.keys(errors).some(
    (key) => key === dayOfWeek || key.startsWith(`${dayOfWeek}.`),
  )
  return [
    'op-day',
    open ? 'op-day--open' : null,
    hasError ? 'op-day--error' : null,
  ]
    .filter(Boolean)
    .join(' ')
}

/**
 * 요일 머리줄. 영업·접수 여부를 켜고 끈다.
 *
 * 상태를 색이나 스위치 모양으로만 전달하지 않는다. 체크박스에 요일 이름을 담아
 * 보조기술이 "월요일 영업"으로 읽게 하고, 옆에 현재 상태 문구를 남긴다.
 */
export function DayToggleRow({
  dayOfWeek,
  open,
  openLabel,
  closedLabel,
  error,
  warning,
  detail,
  onToggle,
}: {
  dayOfWeek: DayOfWeek
  open: boolean
  openLabel: string
  closedLabel: string
  error?: string
  warning?: string
  detail?: string
  onToggle: (open: boolean) => void
}) {
  return (
    <div className="op-day__head">
      <span className="op-day__name">{DAY_LABEL[dayOfWeek]}</span>
      {/*
        상태 문구는 레이블 안에 넣지 않는다. label 안의 모든 텍스트가 접근 가능
        이름에 들어가 "월요일 영업 휴무"처럼 읽히고, 레이블로 요소를 찾는 코드도
        흔들린다. 현재 상태는 옆의 별도 문구로 남긴다.
      */}
      <label className="op-check">
        <input
          type="checkbox"
          checked={open}
          onChange={(event) => onToggle(event.target.checked)}
        />
        <span className="op-check__text">{`${DAY_LABEL[dayOfWeek]} ${openLabel}`}</span>
      </label>
      <span className="op-check__hint">{open ? openLabel : closedLabel}</span>
      {detail !== undefined && <span className="op-section__hint">{detail}</span>}
      {error !== undefined && (
        <p className="mi-field__error" role="alert">
          {error}
        </p>
      )}
      {error === undefined && warning !== undefined && (
        <p className="op-range__warning">{warning}</p>
      )}
    </div>
  )
}
