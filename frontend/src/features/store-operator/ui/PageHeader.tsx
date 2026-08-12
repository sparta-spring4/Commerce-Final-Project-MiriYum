import type { ReactNode } from 'react'

/**
 * 운영자 화면 머리말.
 *
 * 제목·설명·주요 행동을 한 자리에 모아 화면마다 배치가 흔들리지 않게 한다.
 * 시안의 통계 위젯처럼 계약이 없는 지표는 여기서 만들지 않는다.
 */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <header className="op-page-header">
      <div className="op-page-header__text">
        <h1 className="op-page-header__title">{title}</h1>
        {description !== undefined && (
          <p className="op-page-header__description">{description}</p>
        )}
      </div>
      {actions !== undefined && (
        <div className="op-page-header__actions">{actions}</div>
      )}
    </header>
  )
}

/** 카드 한 장. 제목은 h2로 두어 화면 안 제목 단계를 건너뛰지 않는다. */
export function SectionCard({
  title,
  hint,
  children,
}: {
  title: string
  hint?: string
  children: ReactNode
}) {
  return (
    <section className="mi-card">
      <div className="mi-card__body">
        <h2 className="op-section__title">{title}</h2>
        {hint !== undefined && <p className="op-section__hint">{hint}</p>}
        {children}
      </div>
    </section>
  )
}

/** 이름·값 요약. 서버가 준 값을 그대로 보여 주는 자리다. */
export function SummaryList({
  items,
}: {
  items: readonly { term: string; value: ReactNode }[]
}) {
  return (
    <dl className="op-summary">
      {items.map((item) => (
        <div key={item.term}>
          <dt className="op-summary__term">{item.term}</dt>
          <dd className="op-summary__value">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
