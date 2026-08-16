import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
import { REGION_LABEL } from '../model/labels'
import {
  MAX_PARTY_SIZE,
  MIN_PARTY_SIZE,
  REGIONS,
  canRequestAvailableOnly,
  reservationConditionState,
  type CatalogItem,
  type Region,
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
      {/*
        시안의 지역·카테고리는 알약 칩이다. 계약이 지역과 카테고리를 각각
        하나씩만 받으므로 여러 개를 고를 수 있어 보이는 체크박스 대신
        누름 상태를 가진 칩으로 둔다. 같은 칩을 다시 누르면 해제된다.
      */}
      <ChipGroup label="지역">
        {REGIONS.map((region) => (
          <FilterChip
            key={region}
            pressed={draft.region === region}
            onToggle={() =>
              update({ region: draft.region === region ? null : region })
            }
          >
            {REGION_LABEL[region as Region]}
          </FilterChip>
        ))}
      </ChipGroup>

      {storeCategories.length > 0 && (
        <ChipGroup label="카테고리">
          {storeCategories.map((item) => (
            <FilterChip
              key={item.code}
              pressed={draft.storeCategoryCode === item.code}
              onToggle={() =>
                update({
                  storeCategoryCode:
                    draft.storeCategoryCode === item.code ? null : item.code,
                })
              }
            >
              {item.displayName}
            </FilterChip>
          ))}
        </ChipGroup>
      )}

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

        <label className="mi-checkbox">
          <input
            type="checkbox"
            className="mi-checkbox__control"
            name="includesInfants"
            checked={draft.includesInfants}
            onChange={(event) => update({ includesInfants: event.target.checked })}
          />
          영유아가 함께 방문합니다
        </label>

        {/* 시안 `_4`의 "예약 가능만 보기" 스위치. */}
        <label className="mi-switch">
          <span>예약 가능한 매장만 보기</span>
          <input
            type="checkbox"
            className="mi-switch__control"
            name="availableOnly"
            checked={draft.availableOnly && availableOnlyAllowed}
            disabled={!availableOnlyAllowed}
            onChange={(event) => update({ availableOnly: event.target.checked })}
          />
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
        <TextField
          label="검색어"
          // 필 형태에는 레이블 자리가 없다. 지우지 않고 숨겨 보조기술에는 남긴다.
          labelHidden={compact}
          name="keyword"
          value={draft.keyword}
          maxLength={100}
          placeholder="매장 이름이나 메뉴로 검색해 보세요"
          leadingIcon={<Icon name="search" />}
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
 * 칩 묶음.
 *
 * 제목과 칩들을 `group`으로 묶어 보조기술이 "지역 그룹 안의 서울 버튼"처럼
 * 읽게 한다. 제목만 위에 두면 어느 묶음의 칩인지 전달되지 않는다.
 */
function ChipGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="store-search-form__chip-group">
      <h3 className="store-search-form__chip-label">{label}</h3>
      <div className="store-search-form__chips" role="group" aria-label={label}>
        {children}
      </div>
    </div>
  )
}

function FilterChip({
  pressed,
  onToggle,
  children,
}: {
  pressed: boolean
  onToggle: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      className="mi-chip"
      aria-pressed={pressed}
      onClick={onToggle}
    >
      {children}
    </button>
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
