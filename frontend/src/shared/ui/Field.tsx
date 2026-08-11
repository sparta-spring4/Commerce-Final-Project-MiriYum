import { useId } from 'react'
import type {
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
} from 'react'

interface FieldShellProps {
  label: string
  required?: boolean
  /**
   * 레이블을 시각적으로만 숨긴다.
   *
   * 홈의 검색 필처럼 형태가 레이블 자리를 허용하지 않을 때 쓴다. 레이블을
   * 제거하지 않고 숨기기만 하므로 보조기술은 그대로 읽는다. placeholder는
   * 레이블 대체물이 아니다.
   */
  labelHidden?: boolean
  help?: string
  /** 서버·클라이언트 검증 오류 문구. 입력 바로 아래에 붙는다. */
  error?: string | null
  children: (ids: {
    controlId: string
    describedBy: string | undefined
    invalid: boolean
  }) => ReactNode
}

/**
 * 레이블·도움말·오류를 입력과 프로그램적으로 연결한다.
 *
 * 오류를 시각적으로만 붙이면 보조기술이 입력과 오류를 잇지 못한다.
 * aria-describedby와 aria-invalid를 함께 걸어 둔다.
 */
export function FieldShell({
  label,
  required = false,
  labelHidden = false,
  help,
  error,
  children,
}: FieldShellProps) {
  const controlId = useId()
  const helpId = `${controlId}-help`
  const errorId = `${controlId}-error`
  const invalid = Boolean(error)

  const describedBy =
    [help ? helpId : null, invalid ? errorId : null]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <div className="mi-field">
      {/*
        필수 표시는 CSS ::after의 별표로만 그린다. 레이블 텍스트에 마커를 넣으면
        보조기술이 "이메일 별표"처럼 읽고, 레이블로 요소를 찾는 코드도 흔들린다.
        필수 여부 자체는 입력의 required 속성이 보조기술에 전달한다.
      */}
      <label
        className={[
          labelHidden ? 'visually-hidden' : 'mi-field__label',
          required && !labelHidden ? 'mi-field__label--required' : null,
        ]
          .filter(Boolean)
          .join(' ')}
        htmlFor={controlId}
      >
        {label}
      </label>
      {children({ controlId, describedBy, invalid })}
      {help && (
        <p className="mi-field__help" id={helpId}>
          {help}
        </p>
      )}
      {invalid && (
        <p className="mi-field__error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  )
}

type TextFieldProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'id' | 'aria-invalid' | 'aria-describedby'
> & {
  label: string
  labelHidden?: boolean
  help?: string
  error?: string | null
}

export function TextField({
  label,
  labelHidden,
  help,
  error,
  required,
  className,
  ...rest
}: TextFieldProps) {
  return (
    <FieldShell
      label={label}
      required={required}
      labelHidden={labelHidden}
      help={help}
      error={error}
    >
      {({ controlId, describedBy, invalid }) => (
        <input
          {...rest}
          id={controlId}
          required={required}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          className={['mi-field__control', className].filter(Boolean).join(' ')}
        />
      )}
    </FieldShell>
  )
}

type SelectFieldProps = Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  'id' | 'aria-invalid' | 'aria-describedby'
> & {
  label: string
  help?: string
  error?: string | null
  children: ReactNode
}

export function SelectField({
  label,
  help,
  error,
  required,
  className,
  children,
  ...rest
}: SelectFieldProps) {
  return (
    <FieldShell label={label} required={required} help={help} error={error}>
      {({ controlId, describedBy, invalid }) => (
        <select
          {...rest}
          id={controlId}
          required={required}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          className={['mi-field__control', className].filter(Boolean).join(' ')}
        >
          {children}
        </select>
      )}
    </FieldShell>
  )
}
