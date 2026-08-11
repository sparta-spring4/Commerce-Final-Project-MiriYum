import { useEffect, useState } from 'react'
import { Button } from '../../../shared/ui/Button'
import { SelectField, TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { REGION_LABEL } from '../model/labels'
import {
  MAX_PARTY_SIZE,
  MIN_PARTY_SIZE,
  REGIONS,
  canRequestAvailableOnly,
  isRegion,
  reservationConditionState,
  type CatalogItem,
  type StoreSearchFilters,
} from '../model/searchParams'

interface Props {
  value: StoreSearchFilters
  storeCategories: CatalogItem[]
  onSubmit: (next: StoreSearchFilters) => void
  /** 홈은 단일 검색줄, 결과 화면은 전체 필터를 보여 준다. */
  layout: 'compact' | 'full'
}

/**
 * 매장 검색 조건 입력.
 *
 * 제출 전까지는 로컬 draft를 편집하고, 제출 시점에만 URL로 올린다.
 * 타이핑마다 주소가 바뀌면 뒤로가기 기록이 글자 수만큼 쌓인다.
 */
export function SearchFilterForm({
  value,
  storeCategories,
  onSubmit,
  layout,
}: Props) {
  const [draft, setDraft] = useState(value)

  // 뒤로가기나 링크 진입으로 URL이 바뀌면 폼도 그 조건을 따라간다.
  useEffect(() => setDraft(value), [value])

  const conditionState = reservationConditionState(draft)
  const availableOnlyAllowed = canRequestAvailableOnly(draft)

  function update(patch: Partial<StoreSearchFilters>) {
    setDraft((current) => ({ ...current, ...patch }))
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    // 조건이 바뀌면 첫 페이지부터 다시 본다.
    onSubmit({
      ...draft,
      page: 0,
      availableOnly: availableOnlyAllowed ? draft.availableOnly : false,
    })
  }

  return (
    <form
      className={`store-search-form store-search-form--${layout}`}
      onSubmit={handleSubmit}
      aria-label="매장 검색 조건"
    >
      <div className="store-search-form__keyword">
        <TextField
          label="검색어"
          name="keyword"
          value={draft.keyword}
          maxLength={100}
          placeholder="매장 이름이나 메뉴로 검색"
          onChange={(event) => update({ keyword: event.target.value })}
        />
        {layout === 'compact' && (
          <Button type="submit" variant="primary">
            검색
          </Button>
        )}
      </div>

      <div className="store-search-form__grid">
        <SelectField
          label="지역"
          name="region"
          value={draft.region ?? ''}
          onChange={(event) => {
            const next = event.target.value
            update({ region: isRegion(next) ? next : null })
          }}
        >
          <option value="">전체 지역</option>
          {REGIONS.map((region) => (
            <option key={region} value={region}>
              {REGION_LABEL[region]}
            </option>
          ))}
        </SelectField>

        <SelectField
          label="카테고리"
          name="storeCategoryCode"
          value={draft.storeCategoryCode ?? ''}
          onChange={(event) =>
            update({ storeCategoryCode: event.target.value || null })
          }
        >
          <option value="">전체 카테고리</option>
          {storeCategories.map((item) => (
            <option key={item.code} value={item.code}>
              {item.displayName}
            </option>
          ))}
        </SelectField>
      </div>

      <fieldset className="store-search-form__conditions">
        <legend>예약 조건</legend>
        <p className="store-search-form__hint">
          날짜·시간·인원을 모두 입력해야 예약 가능 여부를 확인할 수 있습니다.
        </p>

        <div className="store-search-form__grid">
          <TextField
            label="방문 날짜"
            type="date"
            name="serviceDate"
            value={draft.serviceDate}
            onChange={(event) => update({ serviceDate: event.target.value })}
          />
          <TextField
            label="방문 시간"
            type="time"
            name="startTime"
            value={draft.startTime}
            onChange={(event) => update({ startTime: event.target.value })}
          />
          <TextField
            label="인원"
            type="number"
            name="partySize"
            inputMode="numeric"
            min={MIN_PARTY_SIZE}
            max={MAX_PARTY_SIZE}
            value={draft.partySize}
            onChange={(event) => update({ partySize: event.target.value })}
          />
        </div>

        <label className="store-search-form__check">
          <input
            type="checkbox"
            name="includesInfants"
            checked={draft.includesInfants}
            onChange={(event) => update({ includesInfants: event.target.checked })}
          />
          영유아가 함께 방문합니다
        </label>

        <label className="store-search-form__check">
          <input
            type="checkbox"
            name="availableOnly"
            checked={draft.availableOnly && availableOnlyAllowed}
            disabled={!availableOnlyAllowed}
            onChange={(event) => update({ availableOnly: event.target.checked })}
          />
          예약 가능한 매장만 보기
        </label>

        {conditionState === 'partial' && (
          <Alert tone="warning" title="예약 조건이 완전하지 않습니다.">
            <p>
              날짜·시간·인원 가운데 일부만 입력했습니다. 세 값을 모두 채우기 전까지
              예약 가능 여부를 확인하지 않고 검색합니다.
            </p>
          </Alert>
        )}
      </fieldset>

      {layout === 'full' && (
        <Button type="submit" variant="primary" block>
          이 조건으로 검색
        </Button>
      )}
    </form>
  )
}
