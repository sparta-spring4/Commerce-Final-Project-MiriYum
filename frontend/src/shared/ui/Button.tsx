import type { ButtonHTMLAttributes, ReactNode } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'md' | 'sm'
  block?: boolean
  /**
   * 진행 중 표시. 버튼을 비활성화하고 aria-busy로 보조기술에 알린다.
   * 로딩 문구를 색이나 스피너로만 전달하지 않도록 children은 그대로 남긴다.
   */
  loading?: boolean
  children: ReactNode
}

export function Button({
  variant = 'primary',
  size = 'md',
  block = false,
  loading = false,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: Props) {
  const classes = [
    'mi-button',
    `mi-button--${variant}`,
    size === 'sm' ? 'mi-button--sm' : null,
    block ? 'mi-button--block' : null,
    className,
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <button
      {...rest}
      type={type}
      className={classes}
      disabled={disabled === true || loading}
      aria-busy={loading || undefined}
    >
      {loading && <span className="mi-spinner" aria-hidden="true" />}
      {children}
    </button>
  )
}
