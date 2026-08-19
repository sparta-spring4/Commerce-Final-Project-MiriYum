import { SelectField } from '../../../../shared/ui/Field'
import type { CatalogItem, StoreModes } from '../model/types'

/**
 * 매장 등록·수정 폼이 함께 쓰는 입력 묶음.
 * 두 화면이 같은 계약 규칙을 각자 다르게 구현하지 않게 한다.
 */

interface ModesProps {
  value: StoreModes
  onChange: (next: StoreModes) => void
  error?: string | null
}

/**
 * 거래 방식.
 *
 * 등록 업종은 픽업 가능 여부의 근거가 아니다. 그래서 업종을 보고 픽업 선택을
 * 잠그거나 값을 자동으로 되돌리지 않는다. 조합을 서버가 거절하면 그 오류를
 * 그대로 보여 주고 운영자가 직접 고치게 한다.
 */
export function ModesFieldset({ value, onChange, error }: ModesProps) {
  const entries: readonly {
    key: keyof StoreModes
    label: string
    hint: string
  }[] = [
    {
      key: 'reservationEnabled',
      label: '예약 접수',
      hint: '시간대별 예약을 받습니다.',
    },
    {
      key: 'menuHoldEnabled',
      label: '메뉴 미리 선택',
      hint: '예약에 메뉴를 함께 담습니다.',
    },
    {
      key: 'pickupEnabled',
      label: '픽업',
      hint: '방문 포장 주문을 받습니다.',
    },
  ]

  return (
    <fieldset>
      <legend className="op-day__group-title">거래 방식</legend>
      <p className="op-section__hint">
        활성화한 거래만 고객 화면에 노출됩니다. 태그와는 다른 축입니다.
      </p>
      {/*
        설명은 label 밖에 둔다. label 안 텍스트가 모두 접근 가능 이름이 되므로
        안에 넣으면 "픽업 방문 포장 주문을 받습니다."처럼 한 덩어리로 읽힌다.
      */}
      {entries.map((entry) => (
        <div key={entry.key}>
          <label className="op-check">
            <input
              type="checkbox"
              checked={value[entry.key]}
              onChange={(event) =>
                onChange({ ...value, [entry.key]: event.target.checked })
              }
            />
            <span className="op-check__text">{entry.label}</span>
          </label>
          <p className="op-check__hint">{entry.hint}</p>
        </div>
      ))}
      {error != null && <p className="mi-field__error">{error}</p>}
    </fieldset>
  )
}

interface CatalogSelectProps {
  label: string
  value: string
  items: readonly CatalogItem[] | undefined
  loading: boolean
  required?: boolean
  error?: string | null
  help?: string
  onChange: (code: string) => void
}

/**
 * catalog 단일 선택.
 *
 * 표시명은 서버가 소유한다. code로 이름을 만들어 내지 않으므로 조회가 끝나기
 * 전에는 선택지를 열지 않는다.
 */
export function CatalogSelectField({
  label,
  value,
  items,
  loading,
  required,
  error,
  help,
  onChange,
}: CatalogSelectProps) {
  return (
    <SelectField
      label={label}
      required={required}
      value={value}
      help={help}
      error={error ?? null}
      disabled={loading || items === undefined}
      onChange={(event) => onChange(event.target.value)}
    >
      <option value="">
        {loading ? '불러오는 중입니다…' : '선택해 주세요'}
      </option>
      {(items ?? []).map((item) => (
        <option key={item.code} value={item.code}>
          {item.displayName}
        </option>
      ))}
    </SelectField>
  )
}

interface TagPickerProps {
  label: string
  selected: readonly string[]
  items: readonly CatalogItem[] | undefined
  loading: boolean
  error?: string | null
  hint?: string
  onToggle: (code: string) => void
}

/**
 * catalog 다중 선택.
 *
 * 임의 태그를 만들 수 없다. 승인된 catalog code만 토글한다.
 * 선택 여부는 색이 아니라 `aria-pressed`로 전달한다.
 */
export function CatalogTagPicker({
  label,
  selected,
  items,
  loading,
  error,
  hint,
  onToggle,
}: TagPickerProps) {
  return (
    <fieldset>
      <legend className="op-day__group-title">{label}</legend>
      {hint !== undefined && <p className="op-section__hint">{hint}</p>}
      {loading && <p className="op-section__hint">불러오는 중입니다…</p>}
      <div className="op-chip-set">
        {(items ?? []).map((item) => {
          const pressed = selected.includes(item.code)
          return (
            <button
              type="button"
              key={item.code}
              className="mi-chip"
              aria-pressed={pressed}
              onClick={() => onToggle(item.code)}
            >
              {item.displayName}
            </button>
          )
        })}
      </div>
      {error != null && <p className="mi-field__error">{error}</p>}
    </fieldset>
  )
}

/** 토글 결과를 새 배열로 만든다. 원본 배열을 제자리에서 바꾸지 않는다. */
export function toggleCode(codes: readonly string[], code: string): string[] {
  return codes.includes(code)
    ? codes.filter((entry) => entry !== code)
    : [...codes, code]
}
