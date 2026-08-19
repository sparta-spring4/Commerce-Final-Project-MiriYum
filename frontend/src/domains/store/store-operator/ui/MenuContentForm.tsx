import { useState } from 'react'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import type { MenuDraftForm } from '../model/menuDraft'
import type { CatalogItem } from '../model/types'
import { SectionCard } from './PageHeader'
import {
  AllergenDisclosureFields,
  OriginDisclosureFields,
} from './MenuDisclosureFields'
import { CatalogSelectField, CatalogTagPicker, toggleCode } from './StoreFormFields'

interface Props {
  form: MenuDraftForm
  errors: Readonly<Record<string, string>>
  categories: readonly CatalogItem[] | undefined
  categoriesLoading: boolean
  onChange: (next: MenuDraftForm) => void
}

/**
 * 메뉴 내용 초안 입력.
 *
 * 등록과 수정이 같은 계약(`MenuWriteRequest`)을 쓰므로 폼을 공유한다.
 * 판매 가능 여부(`AVAILABLE`/`SOLD_OUT`)는 제공 구간 재고의 값이며 이 폼에
 * 저장하지 않는다.
 */
export function MenuContentForm({
  form,
  errors,
  categories,
  categoriesLoading,
  onChange,
}: Props) {
  const [tagInput, setTagInput] = useState('')

  function patch(next: Partial<MenuDraftForm>) {
    onChange({ ...form, ...next })
  }

  function addLocalTag() {
    const tag = tagInput.trim()
    if (tag.length === 0 || form.localTags.includes(tag)) {
      return
    }
    patch({ localTags: [...form.localTags, tag] })
    setTagInput('')
  }

  return (
    <div className="op-stack">
      <SectionCard title="메뉴 기본 정보" icon="menu-book">
        <div className="op-form-grid">
          <TextField
            label="메뉴명"
            required
            value={form.name}
            error={errors.name ?? null}
            onChange={(event) => patch({ name: event.target.value })}
          />
          <TextField
            label="메뉴 소개"
            value={form.description}
            error={errors.description ?? null}
            onChange={(event) => patch({ description: event.target.value })}
          />
          <div className="op-form-grid op-form-grid--two">
            <TextField
              label="가격"
              required
              inputMode="numeric"
              value={form.price}
              help="원 단위 정수"
              error={errors.price ?? null}
              onChange={(event) => patch({ price: event.target.value })}
            />
            <div>
              {/* 설명은 label 밖에 둔다. 안에 넣으면 접근 가능 이름에 섞인다. */}
              <label className="op-check">
                <input
                  type="checkbox"
                  checked={form.representative}
                  onChange={(event) =>
                    patch({ representative: event.target.checked })
                  }
                />
                <span className="op-check__text">대표 메뉴</span>
              </label>
              <p className="op-check__hint">
                매장 상세의 대표 메뉴로 노출됩니다.
              </p>
            </div>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="분류" icon="list">
        <div className="op-form-grid">
          <CatalogSelectField
            label="주 카테고리"
            required
            value={form.primaryCategoryCode}
            items={categories}
            loading={categoriesLoading}
            error={errors.primaryCategoryCode ?? null}
            onChange={(code) => patch({ primaryCategoryCode: code })}
          />
          <CatalogTagPicker
            label="보조 카테고리"
            hint="최대 5개. 주 카테고리와 같은 항목은 선택할 수 없습니다."
            selected={form.secondaryCategoryCodes}
            items={categories}
            loading={categoriesLoading}
            error={errors.secondaryCategoryCodes ?? null}
            onToggle={(code) =>
              patch({
                secondaryCategoryCodes: toggleCode(
                  form.secondaryCategoryCodes,
                  code,
                ),
              })
            }
          />

          <div>
            <p className="op-day__group-title">자유 태그</p>
            <div className="op-field-row">
              <div className="op-range__field">
                <TextField
                  label="추가할 태그"
                  value={tagInput}
                  help="최대 10개, 각 30자"
                  error={errors.localTags ?? null}
                  onChange={(event) => setTagInput(event.target.value)}
                />
              </div>
              <Button variant="ghost" size="sm" onClick={addLocalTag}>
                태그 추가
              </Button>
            </div>
            {form.localTags.length > 0 && (
              <ul className="op-chip-set">
                {form.localTags.map((tag) => (
                  <li key={tag}>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`${tag} 삭제`}
                      onClick={() =>
                        patch({
                          localTags: form.localTags.filter(
                            (entry) => entry !== tag,
                          ),
                        })
                      }
                    >
                      {`${tag} ✕`}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </SectionCard>

      <SectionCard
        title="거래별 선택 허용"
        hint="메뉴 미리 선택과 픽업에서 이 메뉴를 고를 수 있는지 각각 정합니다."
      >
        <label className="op-check">
          <input
            type="checkbox"
            checked={form.holdSelectionAllowed}
            onChange={(event) =>
              patch({ holdSelectionAllowed: event.target.checked })
            }
          />
          <span className="op-check__text">예약 시 메뉴 미리 선택 허용</span>
        </label>
        <label className="op-check">
          <input
            type="checkbox"
            checked={form.pickupSelectionAllowed}
            onChange={(event) =>
              patch({ pickupSelectionAllowed: event.target.checked })
            }
          />
          <span className="op-check__text">픽업 주문 선택 허용</span>
        </label>
      </SectionCard>

      <SectionCard
        title="표시 정보"
        hint="계약이 매 저장마다 알레르기·원산지·주류 여부를 모두 요구합니다."
      >
        <AllergenDisclosureFields
          status={form.allergenInformationStatus}
          disclosures={form.allergenDisclosures}
          error={{
            status: errors.allergenInformationStatus,
            disclosures: errors.allergenDisclosures,
          }}
          onStatusChange={(allergenInformationStatus) =>
            patch({ allergenInformationStatus })
          }
          onChange={(allergenDisclosures) => patch({ allergenDisclosures })}
        />

        <OriginDisclosureFields
          status={form.originInformationStatus}
          disclosures={form.originDisclosures}
          error={{
            status: errors.originInformationStatus,
            disclosures: errors.originDisclosures,
          }}
          onStatusChange={(originInformationStatus) =>
            patch({ originInformationStatus })
          }
          onChange={(originDisclosures) => patch({ originDisclosures })}
        />

        <label className="op-check">
          <input
            type="checkbox"
            checked={form.alcoholic}
            onChange={(event) => patch({ alcoholic: event.target.checked })}
          />
          <span className="op-check__text">주류 메뉴</span>
        </label>
        <p className="op-check__hint">연령 확인이 필요한 메뉴인지 표시합니다.</p>
      </SectionCard>
    </div>
  )
}
