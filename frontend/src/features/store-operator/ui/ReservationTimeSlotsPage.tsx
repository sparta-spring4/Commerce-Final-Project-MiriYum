import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../CurrentStoreProvider'
import { useManagedStore, usePublishedStoreDetail } from '../api/queries'
import {
  useCancelReservationTimeSlotsPublication,
  usePublishReservationTimeSlots,
  useSaveReservationTimeSlotsDraft,
} from '../api/scheduleMutations'
import { storeErrorMessage } from '../model/storeErrors'
import { formatStoreDateTime } from '../model/storeTime'
import {
  SCHEDULE_STATUS_LABEL,
  type ManagedStore,
  type ReservationTimeSlotsData,
} from '../model/types'
import {
  createTimeSlotsDraft,
  findTimeSlotConflicts,
  toWeeklyTimeSlots,
  validateTimeSlotsDraft,
  type DayTimeSlotsDraft,
} from '../model/weeklySchedule'
import { DayToggleRow, dayCardClass } from './DayToggleRow'
import { PageHeader, SectionCard, SummaryList } from './PageHeader'
import { PublicationControls } from './PublicationControls'
import { RangeListEditor } from './RangeListEditor'

/**
 * 예약 접수 시간대 초안 저장·게시.
 *
 * 영업시간과 같은 버전·게시 모델을 쓰지만 요청은 별개다. 수용량 필드를 이 요청에
 * 섞지 않는다. 수용량은 날짜 단위 계약이고 화면도 따로 있다(#193).
 *
 * 영업시간 밖·휴게시간 충돌은 **게시된 공개 영업시간**을 기준으로 경고만 한다.
 * 운영자용 영업시간 조회 계약이 없어 편집 중인 초안까지 알 수 없고, 최종 판정은
 * 서버가 `STORE_006`으로 한다.
 */
export function ReservationTimeSlotsPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const storeQuery = useManagedStore(storeId)

  if (storeQuery.isPending) {
    return <Loading label="매장 정보를 불러오는 중입니다." />
  }
  if (storeQuery.isError) {
    return (
      <ErrorState
        error={storeQuery.error}
        message={storeErrorMessage(storeQuery.error)}
        onRetry={() => void storeQuery.refetch()}
      />
    )
  }

  return <TimeSlotsEditor storeId={storeId} store={storeQuery.data} />
}

function TimeSlotsEditor({
  storeId,
  store,
}: {
  storeId: string
  store: ManagedStore
}) {
  const published = usePublishedStoreDetail(storeId)
  const saveDraft = useSaveReservationTimeSlotsDraft(storeId)
  const publish = usePublishReservationTimeSlots(storeId)
  const cancelPublication = useCancelReservationTimeSlotsPublication(storeId)

  const draftKeys = useMemo(createIdempotencyKeyCache, [])
  const publishKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])

  const [week, setWeek] = useState<DayTimeSlotsDraft[]>(createTimeSlotsDraft)
  const [errors, setErrors] = useState<Readonly<Record<string, string>>>({})
  const [saved, setSaved] = useState<ReservationTimeSlotsData | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const publishedHours = published.data?.operatingHours
  const warnings = useMemo(
    () =>
      publishedHours === undefined
        ? {}
        : findTimeSlotConflicts(week, publishedHours),
    [week, publishedHours],
  )

  function updateDay(index: number, next: DayTimeSlotsDraft) {
    setWeek((previous) =>
      previous.map((day, position) => (position === index ? next : day)),
    )
    setSaved(null)
  }

  async function handleSaveDraft() {
    const nextErrors = validateTimeSlotsDraft(week)
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    const body = { days: toWeeklyTimeSlots(week) }
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

  async function runPublication(
    action: () => Promise<ReservationTimeSlotsData>,
  ) {
    setFormError(null)
    try {
      setSaved(await action())
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  function businessHoursLabel(dayOfWeek: DayTimeSlotsDraft['dayOfWeek']) {
    const day = publishedHours?.find((entry) => entry.dayOfWeek === dayOfWeek)
    if (day === undefined) {
      return undefined
    }
    if (day.businessHours.length === 0) {
      return '게시된 영업시간 없음'
    }
    return day.businessHours
      .map((range) => `${range.startTime}–${range.endTime}`)
      .join(', ')
  }

  return (
    <>
      <PageHeader
        title="예약 접수 시간대"
        description="영업시간 안에서 예약을 받을 시간대를 설정합니다."
        actions={
          <Button
            variant="primary"
            loading={saveDraft.isPending}
            onClick={() => void handleSaveDraft()}
          >
            초안 저장
          </Button>
        }
      />

      <div className="op-grid op-grid--aside">
        <div className="op-stack">
          <Alert tone="info" title="저장하면 주간 전체가 대체됩니다.">
            <p>
              계약은 월~일 전체 접수 구간을 한 번에 받습니다. 아래 표시는 게시된
              영업시간 기준 참고 정보이며, 최종 판정은 저장 시 서버가 합니다.
            </p>
          </Alert>

          {published.isError && (
            <Alert tone="warning" title="게시된 영업시간을 불러오지 못했습니다.">
              <p>
                충돌 표시 없이 편집을 계속할 수 있습니다. 저장 시 서버가 영업시간
                충돌을 검사합니다.
              </p>
            </Alert>
          )}

          {formError !== null && <Alert tone="error" title={formError} />}

          {week.map((day, index) => (
            <div
              className={dayCardClass(day.dayOfWeek, day.open, errors)}
              key={day.dayOfWeek}
            >
              <DayToggleRow
                dayOfWeek={day.dayOfWeek}
                open={day.open}
                openLabel="예약 접수"
                closedLabel="접수하지 않음"
                detail={businessHoursLabel(day.dayOfWeek)}
                error={errors[day.dayOfWeek]}
                warning={warnings[day.dayOfWeek]}
                onToggle={(open) => updateDay(index, { ...day, open })}
              />

              {day.open && (
                <RangeListEditor
                  dayOfWeek={day.dayOfWeek}
                  section="slot"
                  legend="접수 시간대"
                  addLabel="시간대 추가"
                  ranges={day.slots}
                  errors={errors}
                  warnings={warnings}
                  onChange={(slots) => updateDay(index, { ...day, slots })}
                />
              )}
            </div>
          ))}
        </div>

        <div className="op-stack">
          <SectionCard title="게시" icon="calendar" hint="초안을 저장한 뒤 게시 시점을 정합니다.">
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
                        : formatStoreDateTime(
                            saved.timeZoneId,
                            saved.effectiveAt,
                          ),
                  },
                ]}
              />
            )}

            <PublicationControls
              version={saved?.version ?? null}
              timeZoneId={saved?.timeZoneId ?? store.timeZoneId}
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

          <SectionCard title="수용량은 별도입니다" icon="users">
            <p className="op-section__hint">
              접수 시간대는 "언제 받는지"만 정합니다. 인원·팀 수 제한은 예약
              수용량 화면에서 날짜 단위로 설정합니다.
            </p>
          </SectionCard>
        </div>
      </div>
    </>
  )
}
