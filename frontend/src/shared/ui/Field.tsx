import { useId, useState } from 'react'
import type {
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
} from 'react'
import { Icon } from './Icon'

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
  /**
   * 입력 안 왼쪽에 겹치는 아이콘. 시안의 메일·자물쇠·돋보기 자리다.
   * 장식이므로 `Icon`을 label 없이 넘긴다. 뜻은 레이블이 전달한다.
   */
  leadingIcon?: ReactNode
  /**
   * 입력 안 오른쪽 버튼. 비밀번호 표시 토글처럼 눌리는 것만 둔다.
   * 누를 수 없는 표시는 `help`에 쓴다.
   */
  trailing?: ReactNode
}

export function TextField({
  label,
  labelHidden,
  help,
  error,
  required,
  className,
  leadingIcon,
  trailing,
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
      {({ controlId, describedBy, invalid }) => {
        const input = (
          <input
            {...rest}
            id={controlId}
            required={required}
            aria-invalid={invalid || undefined}
            aria-describedby={describedBy}
            className={['mi-field__control', className]
              .filter(Boolean)
              .join(' ')}
          />
        )

        // 겹칠 것이 없으면 감싸지 않는다. 빈 래퍼는 레이아웃만 복잡하게 한다.
        if (leadingIcon === undefined && trailing === undefined) {
          return input
        }

        return (
          <div
            className={[
              'mi-field__affix',
              leadingIcon !== undefined ? 'mi-field__affix--leading' : null,
              trailing !== undefined ? 'mi-field__affix--trailing' : null,
            ]
              .filter(Boolean)
              .join(' ')}
          >
            {leadingIcon !== undefined && (
              <span className="mi-field__lead">{leadingIcon}</span>
            )}
            {input}
            {trailing}
          </div>
        )
      }}
    </FieldShell>
  )
}

/**
 * 비밀번호 입력. 시안의 자물쇠 아이콘과 표시 토글을 함께 둔다.
 *
 * 토글은 입력의 `type`만 바꾼다. 값을 다른 곳에 옮겨 담지 않으므로 브라우저
 * 비밀번호 관리자가 계속 같은 입력을 인식한다. 버튼 이름은 현재 상태가 아니라
 * 누르면 일어날 일을 말한다.
 */
export function PasswordField({
  label,
  ...rest
}: Omit<TextFieldProps, 'type' | 'leadingIcon' | 'trailing'>) {
  const [revealed, setRevealed] = useState(false)

  return (
    <TextField
      {...rest}
      label={label}
      type={revealed ? 'text' : 'password'}
      leadingIcon={<Icon name="lock" />}
      trailing={
        <button
          type="button"
          className="mi-field__trail"
          // aria-pressed 대신 이름 자체를 바꾼다. 토글 버튼의 상태를 이름과
          // 눌림 상태 양쪽으로 전달하면 보조기술이 서로 반대로 읽는다.
          aria-label={revealed ? '비밀번호 숨기기' : '비밀번호 표시'}
          onClick={() => setRevealed((current) => !current)}
        >
          <Icon name={revealed ? 'eyeOff' : 'eye'} />
        </button>
      }
    />
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
