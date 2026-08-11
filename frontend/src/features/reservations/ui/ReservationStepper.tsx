export type ReservationStep = 'schedule' | 'menus' | 'confirm'

const STEPS: readonly { key: ReservationStep; label: string }[] = [
  { key: 'schedule', label: '정보 입력' },
  { key: 'menus', label: '메뉴 선택' },
  { key: 'confirm', label: '예약 확인' },
]

/**
 * 예약 작성 단계 표시.
 *
 * 진행 상태를 색으로만 구분하지 않고 현재 단계를 문구로도 읽히게 한다.
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
            <span className="reservation-stepper__index">{index + 1}</span>
            {step.label}
            {state === 'done' && <span className="visually-hidden"> (완료)</span>}
          </li>
        )
      })}
    </ol>
  )
}
