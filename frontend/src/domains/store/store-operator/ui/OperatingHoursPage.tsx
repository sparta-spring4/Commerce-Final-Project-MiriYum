import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { useManagedStore } from '../api/queries'
import {
  useCancelOperatingHoursPublication,
  usePublishOperatingHours,
  useSaveOperatingHoursDraft,
} from '../api/scheduleMutations'
import { storeErrorMessage } from '../model/storeErrors'
import { formatStoreDateTime } from '../model/storeTime'
import {
  SCHEDULE_STATUS_LABEL,
  type ManagedStore,
  type OperatingHoursData,
} from '../model/types'
import {
  createScheduleDraft,
  toWeeklyOperatingHours,
  validateScheduleDraft,
  type DayScheduleDraft,
} from '../model/weeklySchedule'
import { PageHeader, SectionCard, SummaryList } from '../../../../app/shells/store-operator/OperatorPage'
import { PublicationControls } from '../../../../app/shells/store-operator/PublicationControls'
import { RangeListEditor } from './RangeListEditor'
import { DayToggleRow, dayCardClass } from './DayToggleRow'

/**
 * 영업시간 초안 저장·게시.
 *
 * `PUT`은 월~일 **전체 주간 초안**을 만든다. 일부 요일만 보내 서버 값과 병합할 수
 * 없다. 그리고 운영자용 조회 계약이 없어 기존 설정을 읽어 편집할 수 없다. 그래서
 * 이 화면은 빈 주간에서 시작하며, 저장이 기존 설정을 대체한다는 사실을 먼저 알린다.
 */
export function OperatingHoursPage() {
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

  return <OperatingHoursEditor storeId={storeId} store={storeQuery.data} />
}

function OperatingHoursEditor({
  storeId,
  store,
}: {
  storeId: string
  store: ManagedStore
}) {
  const saveDraft = useSaveOperatingHoursDraft(storeId)
  const publish = usePublishOperatingHours(storeId)
  const cancelPublication = useCancelOperatingHoursPublication(storeId)

  /*
   * 명령마다 멱등 키 캐시를 따로 둔다.
   *
   * 초안 저장을 다시 시도하면 같은 키가 유지되고, 초안을 고쳐 저장하면 새 키를
   * 받는다. 게시·게시 취소도 각자의 내용으로 같은 규칙을 따른다. 하나를 공유하면
   * 초안 재시도가 게시 요청의 키를 밀어낸다.
   */
  const draftKeys = useMemo(createIdempotencyKeyCache, [])
  const publishKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])

  const [week, setWeek] = useState<DayScheduleDraft[]>(createScheduleDraft)
  const [errors, setErrors] = useState<Readonly<Record<string, string>>>({})
  const [saved, setSaved] = useState<OperatingHoursData | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  function updateDay(index: number, next: DayScheduleDraft) {
    setWeek((previous) =>
      previous.map((day, position) => (position === index ? next : day)),
    )
    setSaved(null)
  }

  async function handleSaveDraft() {
    const nextErrors = validateScheduleDraft(week)
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    const body = { days: toWeeklyOperatingHours(week) }
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

  async function runPublication(action: () => Promise<OperatingHoursData>) {
    setFormError(null)
    try {
      setSaved(await action())
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title="영업시간"
        description="정규 주간 일정과 휴게시간을 설정합니다."
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
              계약은 월~일 전체를 한 번에 받습니다. 현재 설정을 불러오는 조회
              계약이 없어 이 화면은 빈 주간에서 시작하며, 저장한 내용이 기존
              설정을 대신합니다.
            </p>
          </Alert>

          {formError !== null && <Alert tone="error" title={formError} />}

          {week.map((day, index) => (
            <div
              className={dayCardClass(day.dayOfWeek, day.open, errors)}
              key={day.dayOfWeek}
            >
              <DayToggleRow
                dayOfWeek={day.dayOfWeek}
                open={day.open}
                openLabel="영업"
                closedLabel="휴무"
                error={errors[day.dayOfWeek]}
                onToggle={(open) => updateDay(index, { ...day, open })}
              />

              {day.open && (
                <>
                  <RangeListEditor
                    dayOfWeek={day.dayOfWeek}
                    section="business"
                    legend="영업 구간"
                    addLabel="영업 구간 추가"
                    ranges={day.businessHours}
                    errors={errors}
                    onChange={(businessHours) =>
                      updateDay(index, { ...day, businessHours })
                    }
                  />
                  <RangeListEditor
                    dayOfWeek={day.dayOfWeek}
                    section="break"
                    legend="휴게시간"
                    addLabel="휴게시간 추가"
                    ranges={day.breakTimes}
                    errors={errors}
                    onChange={(breakTimes) =>
                      updateDay(index, { ...day, breakTimes })
                    }
                  />
                </>
              )}
            </div>
          ))}
        </div>

        <div className="op-stack">
          <SectionCard title="게시" icon="calendar" hint="초안을 저장한 뒤 게시 시점을 정합니다.">
            {saved !== null && (
              <>
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
                {saved.conflictCheckStatus === 'NOT_EVALUATED' && (
                  <p className="op-section__hint">
                    변경 영향 거래는 아직 평가되지 않았습니다.
                  </p>
                )}
              </>
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

          <SectionCard title="적용 범위" icon="users">
            <p className="op-section__hint">
              영업시간을 바꿔도 이미 접수된 예약을 자동으로 옮기거나 취소하지
              않습니다. 필요한 조치는 예약 목록에서 직접 처리해 주세요.
            </p>
          </SectionCard>
        </div>
      </div>
    </>
  )
}
