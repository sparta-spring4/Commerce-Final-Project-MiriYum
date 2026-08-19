import type { ReactNode } from 'react'
import { OperatorIcon, type OperatorIconName } from './OperatorIcon'

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

/**
 * 카드 한 장. 제목은 h2로 두어 화면 안 제목 단계를 건너뛰지 않는다.
 *
 * 시안은 카드 제목 아래에 옅은 구분선을 두어 제목과 내용을 나눈다. 그 선은
 * `.op-section__head`가 그리므로 제목·설명·행동이 항상 같은 블록에 모인다.
 */
export function SectionCard({
  title,
  hint,
  icon,
  actions,
  children,
}: {
  title: string
  hint?: string
  /** 제목 앞의 장식 아이콘. 의미는 제목 문구가 전달한다. */
  icon?: OperatorIconName
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="mi-card">
      <div className="mi-card__body">
        <div className="op-section__head">
          <div className="op-section__heading">
            <h2 className="op-section__title">
              {icon !== undefined && (
                <OperatorIcon name={icon} className="op-section__title-icon" />
              )}
              {title}
            </h2>
            {hint !== undefined && <p className="op-section__hint">{hint}</p>}
          </div>
          {actions !== undefined && (
            <div className="op-section__actions">{actions}</div>
          )}
        </div>
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
