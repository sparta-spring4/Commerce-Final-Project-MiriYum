export type ReservationStep = 'schedule' | 'menus' | 'confirm'

const STEP_LABEL: Record<ReservationStep, string> = {
  schedule: '정보 입력',
  menus: '메뉴 선택',
  confirm: '예약 확인',
}

/** 메뉴를 받지 않는 매장은 메뉴 단계 없이 두 칸으로 진행한다. */
export const DEFAULT_STEPS: readonly ReservationStep[] = [
  'schedule',
  'menus',
  'confirm',
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
export function ReservationStepper({
  current,
  steps = DEFAULT_STEPS,
}: {
  current: ReservationStep
  steps?: readonly ReservationStep[]
}) {
  const currentIndex = steps.indexOf(current)

  return (
    <ol className="reservation-stepper" aria-label="예약 진행 단계">
      {steps.map((key, index) => {
        const step = { key, label: STEP_LABEL[key] }
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
