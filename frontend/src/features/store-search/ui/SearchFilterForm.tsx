import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
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
  /**
   * 홈은 검색 한 줄과 접이식 상세 조건, 결과 화면은 항상 펼친 전체 필터를 쓴다.
   * 시안의 홈이 검색 진입점 하나로 정리돼 있고 전체 필터는 결과 화면이 소유한다.
   */
  layout: 'compact' | 'full'
}

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
  const compact = layout === 'compact'

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

  const conditions = (
    <>
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
    </>
  )

  return (
    <form
      className={`store-search-form store-search-form--${layout}`}
      onSubmit={handleSubmit}
      aria-label="매장 검색 조건"
    >
      <div className="store-search-form__keyword">
        {compact && (
          <span className="store-search-form__search-mark" aria-hidden="true">
            ⌕
          </span>
        )}
        <TextField
          label="검색어"
          // 필 형태에는 레이블 자리가 없다. 지우지 않고 숨겨 보조기술에는 남긴다.
          labelHidden={compact}
          name="keyword"
          value={draft.keyword}
          maxLength={100}
          placeholder="매장 이름이나 메뉴로 검색해 보세요"
          onChange={(event) => update({ keyword: event.target.value })}
        />
        {compact && (
          <Button type="submit" variant="primary">
            검색
          </Button>
        )}
      </div>

      {compact ? (
        <Disclosure summary="지역·카테고리·예약 조건 더보기">
          {conditions}
          <Button type="submit" variant="primary" block>
            이 조건으로 검색
          </Button>
        </Disclosure>
      ) : (
        <>
          {conditions}
          <Button type="submit" variant="primary" block>
            이 조건으로 검색
          </Button>
        </>
      )}
    </form>
  )
}

/**
 * 접이식 상세 조건.
 *
 * `details`/`summary`를 쓰면 키보드 조작과 펼침 상태 전달을 브라우저가 맡는다.
 * 직접 만든 토글은 aria-expanded를 빠뜨리기 쉽다.
 */
function Disclosure({
  summary,
  children,
}: {
  summary: string
  children: ReactNode
}) {
  return (
    <details className="store-search-form__advanced">
      <summary>{summary}</summary>
      <div className="store-search-form__advanced-body">{children}</div>
    </details>
  )
}
