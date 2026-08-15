export type ReservationStep = 'schedule' | 'menus' | 'confirm'

const STEPS: readonly { key: ReservationStep; label: string }[] = [
  { key: 'schedule', label: '정보 입력' },
  { key: 'menus', label: '메뉴 선택' },
  { key: 'confirm', label: '예약 확인' },
]

/**
 * 예약 작성 단계 표시.
 *
 * 시안 `_7`·`_10`의 큰 원형 번호와 연결선을 따른다. 진행 상태를 색으로만
 * 구분하지 않고 현재 단계를 문구와 `aria-current`로도 읽히게 한다.
 *
 * 연결선은 순수 장식이라 별도 목록 항목으로 만들지 않는다. `li`로 두면
 * 보조기술이 빈 항목을 사이사이 읽는다.
 */
export function ReservationStepper({ current }: { current: ReservationStep }) {
  const currentIndex = STEPS.findIndex((step) => step.key === current)

  return (
    <ol className="reservation-stepper" aria-label="예약 진행 단계">
      {STEPS.map((step, index) => {
        const state =
          index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'upcoming'
        return (
          <li
            key={step.key}
            className={`reservation-stepper__item reservation-stepper__item--${state}`}
            aria-current={state === 'current' ? 'step' : undefined}
          >
            {index > 0 && (
              <span className="reservation-stepper__line" aria-hidden="true" />
            )}
            <span className="reservation-stepper__index">{index + 1}</span>
            <span className="reservation-stepper__label">{step.label}</span>
            {state === 'done' && <span className="visually-hidden"> (완료)</span>}
          </li>
        )
      })}
    </ol>
  )
}
