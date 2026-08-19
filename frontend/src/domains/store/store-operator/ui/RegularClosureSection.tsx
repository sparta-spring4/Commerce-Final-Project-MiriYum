import { useMemo, useState } from 'react'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  useCancelRegularClosurePublication,
  usePublishRegularClosure,
  useSaveRegularClosureDraft,
} from '../api/scheduleMutations'
import { storeErrorMessage } from '../model/storeErrors'
import { formatStoreDateTime } from '../model/storeTime'
import {
  DAY_LABEL,
  DAY_OF_WEEK,
  SCHEDULE_STATUS_LABEL,
  type DayOfWeek,
  type RegularClosureData,
} from '../model/types'
import { PageHeader, SectionCard, SummaryList } from './PageHeader'
import { PublicationControls } from './PublicationControls'

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const MAX_DATES = 366

/**
 * 정기 휴무.
 *
 * `PUT`은 주간 요일과 특정 날짜 목록 **전체**를 한 번에 받는다. 일부만 보내
 * 병합하지 않는다. 조회 계약이 없으므로 이 화면도 빈 초안에서 시작한다.
 */
export function RegularClosureSection({
  storeId,
  timeZoneId,
}: {
  storeId: string
  timeZoneId: string
}) {
  const saveDraft = useSaveRegularClosureDraft(storeId)
  const publish = usePublishRegularClosure(storeId)
  const cancelPublication = useCancelRegularClosurePublication(storeId)

  const draftKeys = useMemo(createIdempotencyKeyCache, [])
  const publishKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])

  const [weeklyDays, setWeeklyDays] = useState<DayOfWeek[]>([])
  const [dates, setDates] = useState<string[]>([])
  const [dateInput, setDateInput] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState<RegularClosureData | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  function toggleDay(day: DayOfWeek) {
    setWeeklyDays((previous) =>
      previous.includes(day)
        ? previous.filter((entry) => entry !== day)
        : [...previous, day],
    )
    setSaved(null)
  }

  function addDate() {
    if (!DATE_PATTERN.test(dateInput)) {
      setErrors({ dateInput: '휴무 날짜를 선택해 주세요.' })
      return
    }
    if (dates.includes(dateInput)) {
      setErrors({ dateInput: '이미 추가한 날짜입니다.' })
      return
    }
    if (dates.length >= MAX_DATES) {
      setErrors({ dateInput: `날짜는 최대 ${MAX_DATES}개까지 등록합니다.` })
      return
    }
    setErrors({})
    setDates((previous) => [...previous, dateInput].sort())
    setDateInput('')
    setSaved(null)
  }

  async function handleSaveDraft() {
    const body = { weeklyDays, dates }
    setFormError(null)
    setErrors({})
    try {
      setSaved(
        await saveDraft.mutateAsync({
          body,
          idempotencyKey: draftKeys.keyFor(JSON.stringify(body)),
        }),
      )
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  async function runPublication(action: () => Promise<RegularClosureData>) {
    setFormError(null)
    try {
      setSaved(await action())
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  return (
    <div className="op-grid op-grid--aside">
      <div className="op-stack">
        <SectionCard
          title="정기 휴무"
          hint="매주 반복하는 휴무 요일과 특정 날짜를 함께 등록합니다."
        >
          <Alert tone="info" title="저장하면 정기 휴무 전체가 대체됩니다.">
            <p>
              요일과 날짜 목록을 한 요청으로 보냅니다. 지금 화면에 없는 항목은
              저장 후 남지 않습니다.
            </p>
          </Alert>

          {formError !== null && <Alert tone="error" title={formError} />}

          <fieldset>
            <legend className="op-day__group-title">매주 휴무 요일</legend>
            <div className="op-chip-set">
              {DAY_OF_WEEK.map((day) => (
                <button
                  type="button"
                  key={day}
                  className="mi-chip"
                  aria-pressed={weeklyDays.includes(day)}
                  onClick={() => toggleDay(day)}
                >
                  {DAY_LABEL[day]}
                </button>
              ))}
            </div>
          </fieldset>

          <div className="op-day__group">
            <p className="op-day__group-title">휴무 날짜</p>
            <div className="op-field-row">
              <div className="op-range__field">
                <TextField
                  label="추가할 날짜"
                  type="date"
                  value={dateInput}
                  error={errors.dateInput ?? null}
                  onChange={(event) => setDateInput(event.target.value)}
                />
              </div>
              <Button variant="ghost" size="sm" onClick={addDate}>
                날짜 추가
              </Button>
            </div>

            {dates.length === 0 ? (
              <p className="op-section__hint">등록한 날짜가 없습니다.</p>
            ) : (
              <ul className="op-chip-set">
                {dates.map((date) => (
                  <li key={date}>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`${date} 삭제`}
                      onClick={() => {
                        setDates((previous) =>
                          previous.filter((entry) => entry !== date),
                        )
                        setSaved(null)
                      }}
                    >
                      {`${date} ✕`}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="op-actions">
            <Button
              variant="primary"
              loading={saveDraft.isPending}
              onClick={() => void handleSaveDraft()}
            >
              정기 휴무 초안 저장
            </Button>
          </div>
        </SectionCard>
      </div>

      <div className="op-stack">
        <SectionCard title="정기 휴무 게시" icon="calendar">
          {saved !== null && (
            <SummaryList
              items={[
                { term: '초안 버전', value: saved.version },
                {
                  term: '상태',
                  value: (
                    <Badge
                      tone={saved.status === 'ACTIVE' ? 'positive' : 'neutral'}
                    >
                      {SCHEDULE_STATUS_LABEL[saved.status]}
                    </Badge>
                  ),
                },
                {
                  term: '적용 시각',
                  value:
                    saved.effectiveAt == null
                      ? '즉시'
                      : formatStoreDateTime(saved.timeZoneId, saved.effectiveAt),
                },
              ]}
            />
          )}

          <PublicationControls
            version={saved?.version ?? null}
            timeZoneId={saved?.timeZoneId ?? timeZoneId}
            canCancelPublication={saved?.status === 'SCHEDULED'}
            publishing={publish.isPending}
            cancelling={cancelPublication.isPending}
            onPublish={(submission) => {
              const version = saved?.version
              if (version === undefined) {
                return
              }
              void runPublication(() =>
                publish.mutateAsync({
                  version,
                  body: submission,
                  idempotencyKey: publishKeys.keyFor(
                    JSON.stringify({ version, submission }),
                  ),
                }),
              )
            }}
            onCancelPublication={(changeReason) => {
              const version = saved?.version
              if (version === undefined) {
                return
              }
              void runPublication(() =>
                cancelPublication.mutateAsync({
                  version,
                  body: { changeReason },
                  idempotencyKey: cancelKeys.keyFor(
                    JSON.stringify({ version, changeReason }),
                  ),
                }),
              )
            }}
          />
        </SectionCard>
      </div>
    </div>
  )
}

/** 휴무 화면의 머리말. 두 섹션이 같은 제목 아래 놓인다. */
export function ClosuresHeader() {
  return (
    <PageHeader
      title="휴무·휴점"
      description="정기 휴무와 임시 휴점을 구분해 관리합니다."
    />
  )
}
