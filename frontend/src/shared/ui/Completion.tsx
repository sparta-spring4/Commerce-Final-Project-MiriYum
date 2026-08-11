import type { ReactNode } from 'react'

/**
 * 거래 완료 화면의 공통 축하 영역과 티켓 카드.
 *
 * 시안 `_12`의 구성을 따른다. 배경 광원 → 체크 아이콘(동심원) → 제목 →
 * 안내 문구 → 티켓 카드(예약 번호 + 요약) → 다음 행동 버튼.
 *
 * 예약과 픽업이 같은 형태를 쓰므로 shared에 둔다. 각 기능은 요약 행만 채운다.
 */
export function CompletionScreen({
  title,
  description,
  referenceLabel,
  referenceValue,
  children,
  actions,
}: {
  title: string
  description: string
  /** "예약 번호"처럼 식별자의 이름. */
  referenceLabel: string
  /**
   * 서버가 준 식별자를 그대로 보여 준다.
   * 시안의 `#MY20231027-01` 같은 사람이 읽기 쉬운 번호 체계는 계약에 없으므로
   * 만들어 내지 않는다.
   */
  referenceValue: string
  /** 요약 행들. `CompletionRow`를 쓴다. */
  children: ReactNode
  actions: ReactNode
}) {
  return (
    <div className="mi-completion">
      <div className="mi-completion__ambient" aria-hidden="true" />

      <div className="mi-completion__inner">
        <div className="mi-completion__mark" aria-hidden="true">
          <span className="mi-completion__ring" />
          <span className="mi-completion__ring mi-completion__ring--outer" />
          <span className="mi-completion__check">✓</span>
        </div>

        <h1 className="mi-completion__title">{title}</h1>
        <p className="mi-completion__description">{description}</p>

        <div className="mi-completion__ticket">
          {/* 티켓 옆면 노치와 점선. 순수 장식이다. */}
          <span className="mi-completion__notch mi-completion__notch--left" aria-hidden="true" />
          <span className="mi-completion__notch mi-completion__notch--right" aria-hidden="true" />

          <div className="mi-completion__reference">
            <span className="mi-completion__reference-label">{referenceLabel}</span>
            {/* 식별자는 줄바꿈이 가능해야 좁은 화면에서 넘치지 않는다. */}
            <span className="mi-completion__reference-value">{referenceValue}</span>
          </div>

          <span className="mi-completion__dashed" aria-hidden="true" />

          <dl className="mi-completion__summary">{children}</dl>
        </div>

        <div className="mi-completion__actions">{actions}</div>
      </div>
    </div>
  )
}

export function CompletionRow({
  label,
  children,
}: {
  label: string
  children: ReactNode
}) {
  return (
    <div className="mi-completion__row">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
