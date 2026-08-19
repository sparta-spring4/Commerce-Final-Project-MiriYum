import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import type { DisclosureStatusInput } from '../model/menuDraft'
import {
  ALLERGEN_CONTAINMENT_LABEL,
  ALLERGEN_LABEL,
  type AllergenContainmentStatus,
  type AllergenDisclosure,
  type AllergenIngredientCode,
  type OriginDisclosure,
} from '../model/types'

const ALLERGEN_CODES = Object.keys(ALLERGEN_LABEL) as AllergenIngredientCode[]

/**
 * 알레르기 표시.
 *
 * 계약은 알레르기에 `REGISTERED`와 초안의 `NOT_REGISTERED`만 허용한다.
 * `NOT_APPLICABLE`은 원산지 전용이므로 여기 선택지에 넣지 않는다.
 * 빈 배열을 "안전"으로 추론하지 않고 상태를 반드시 고르게 한다.
 */
export function AllergenDisclosureFields({
  status,
  disclosures,
  error,
  onStatusChange,
  onChange,
}: {
  status: DisclosureStatusInput
  disclosures: readonly AllergenDisclosure[]
  error?: { status?: string; disclosures?: string }
  onStatusChange: (status: DisclosureStatusInput) => void
  onChange: (disclosures: AllergenDisclosure[]) => void
}) {
  const [code, setCode] = useState<AllergenIngredientCode>(ALLERGEN_CODES[0])
  const [containment, setContainment] =
    useState<AllergenContainmentStatus>('CONTAINS')

  function add() {
    if (disclosures.some((entry) => entry.ingredientCode === code)) {
      return
    }
    onChange([...disclosures, { ingredientCode: code, status: containment }])
  }

  return (
    <fieldset>
      <legend className="op-day__group-title">알레르기 표시</legend>

      <SelectField
        label="알레르기 정보 등록 여부"
        required
        value={status}
        error={error?.status ?? null}
        onChange={(event) =>
          onStatusChange(event.target.value as DisclosureStatusInput)
        }
      >
        <option value="">선택해 주세요</option>
        <option value="REGISTERED">등록</option>
        <option value="NOT_REGISTERED">미등록</option>
      </SelectField>

      {status === 'REGISTERED' && (
        <>
          <div className="op-field-row">
            <div className="op-range__field">
              <SelectField
                label="원재료"
                value={code}
                onChange={(event) =>
                  setCode(event.target.value as AllergenIngredientCode)
                }
              >
                {ALLERGEN_CODES.map((value) => (
                  <option key={value} value={value}>
                    {ALLERGEN_LABEL[value]}
                  </option>
                ))}
              </SelectField>
            </div>
            <div className="op-range__field">
              <SelectField
                label="함유 형태"
                value={containment}
                onChange={(event) =>
                  setContainment(event.target.value as AllergenContainmentStatus)
                }
              >
                <option value="CONTAINS">함유</option>
                <option value="MAY_CONTAIN">혼입 가능</option>
              </SelectField>
            </div>
            <Button variant="ghost" size="sm" onClick={add}>
              알레르기 항목 추가
            </Button>
          </div>

          {disclosures.length === 0 ? (
            <p className="op-section__hint">추가한 항목이 없습니다.</p>
          ) : (
            <ul className="op-chip-set">
              {disclosures.map((entry) => (
                <li key={entry.ingredientCode}>
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`${ALLERGEN_LABEL[entry.ingredientCode]} 삭제`}
                    onClick={() =>
                      onChange(
                        disclosures.filter(
                          (other) =>
                            other.ingredientCode !== entry.ingredientCode,
                        ),
                      )
                    }
                  >
                    {`${ALLERGEN_LABEL[entry.ingredientCode]} · ${ALLERGEN_CONTAINMENT_LABEL[entry.status]} ✕`}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {error?.disclosures !== undefined && (
        <p className="mi-field__error">{error.disclosures}</p>
      )}
    </fieldset>
  )
}

/**
 * 원산지 표시.
 *
 * 원산지에는 `NOT_APPLICABLE`(해당 없음)이 있다. 다만 "해당 없음"을 기본값으로
 * 두지 않는다. 운영자가 고르지 않은 상태를 해당 없음으로 추론하면 표시 의무를
 * 클라이언트가 임의로 면제해 주는 셈이 된다.
 */
export function OriginDisclosureFields({
  status,
  disclosures,
  error,
  onStatusChange,
  onChange,
}: {
  status: DisclosureStatusInput
  disclosures: readonly OriginDisclosure[]
  error?: { status?: string; disclosures?: string }
  onStatusChange: (status: DisclosureStatusInput) => void
  onChange: (disclosures: OriginDisclosure[]) => void
}) {
  const [ingredient, setIngredient] = useState('')
  const [origin, setOrigin] = useState('')

  function add() {
    if (ingredient.trim().length === 0 || origin.trim().length === 0) {
      return
    }
    onChange([...disclosures, { ingredient, origin }])
    setIngredient('')
    setOrigin('')
  }

  return (
    <fieldset>
      <legend className="op-day__group-title">원산지 표시</legend>

      <SelectField
        label="원산지 정보 등록 여부"
        required
        value={status}
        error={error?.status ?? null}
        onChange={(event) =>
          onStatusChange(event.target.value as DisclosureStatusInput)
        }
      >
        <option value="">선택해 주세요</option>
        <option value="REGISTERED">등록</option>
        <option value="NOT_REGISTERED">미등록</option>
        <option value="NOT_APPLICABLE">해당 없음</option>
      </SelectField>

      {status === 'REGISTERED' && (
        <>
          <div className="op-field-row">
            <div className="op-range__field">
              <TextField
                label="원재료"
                value={ingredient}
                onChange={(event) => setIngredient(event.target.value)}
              />
            </div>
            <div className="op-range__field">
              <TextField
                label="원산지"
                value={origin}
                onChange={(event) => setOrigin(event.target.value)}
              />
            </div>
            <Button variant="ghost" size="sm" onClick={add}>
              원산지 항목 추가
            </Button>
          </div>

          {disclosures.length === 0 ? (
            <p className="op-section__hint">추가한 항목이 없습니다.</p>
          ) : (
            <ul className="op-chip-set">
              {disclosures.map((entry, index) => (
                <li key={`${entry.ingredient}-${entry.origin}`}>
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`${entry.ingredient} 원산지 삭제`}
                    onClick={() =>
                      onChange(
                        disclosures.filter((_, position) => position !== index),
                      )
                    }
                  >
                    {`${entry.ingredient} · ${entry.origin} ✕`}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {error?.disclosures !== undefined && (
        <p className="mi-field__error">{error.disclosures}</p>
      )}
    </fieldset>
  )
}
