import type { ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'positive' | 'negative' | 'attention'

/**
 * 상태 뱃지. 색과 함께 반드시 문구를 담는다.
 * 색만으로 의미를 전달하지 않는다는 접근성 규칙을 이 컴포넌트가 강제한다.
 */
export function Badge({
  tone = 'neutral',
  children,
}: {
  tone?: BadgeTone
  children: ReactNode
}) {
  return <span className={`mi-badge mi-badge--${tone}`}>{children}</span>
}
