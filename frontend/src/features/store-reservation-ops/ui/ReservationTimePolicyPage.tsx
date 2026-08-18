import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../shared/api/idempotencyKey'
import { Badge } from '../../../shared/ui/Badge'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert, ErrorState, Loading } from '../../../shared/ui/Feedback'
import {
  PageHeader,
  PublicationControls,
  SectionCard,
  SummaryList,
  useAdoptStoreFromRoute,
  useManagedStore,
  storeErrorMessage,
} from '../../store-operator'
import {
  useCancelTimePolicyPublication,
  usePublishTimePolicy,
  useSaveTimePolicyDraft,
} from '../api/queries'
import { reservationOpsErrorMessage } from '../model/errors'
import {
  POLICY_STATUS_LABEL,
  type ReservationTimePolicyResponse,
} from '../model/types'

const MAX_MINUTES = 1440

/**
 * 예약 시간 정책.
 *
 * 슬롯 간격·서비스 시간·전환 시간은 각각 다른 값이다. 하나로 합쳐 "예약 단위"로
 * 뭉개지 않는다. 초안 저장과 게시는 분리된 두 단계이며, 게시 전 초안을 게시된
 * 상태로 표시하지 않는다.
 */
export function ReservationTimePolicyPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const storeQuery = useManagedStore(storeId)
  const saveDraft = useSaveTimePolicyDraft(storeId)
  const publish = usePublishTimePolicy(storeId)
  const cancelPublication = useCancelTimePolicyPublication(storeId)

  const draftKeys = useMemo(createIdempotencyKeyCache, [])
  const publishKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])

  const [slotInterval, setSlotInterval] = useState('30')
  const [serviceDuration, setServiceDuration] = useState('90')
  const [turnoverDuration, setTurnoverDuration] = useState('15')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState<ReservationTimePolicyResponse | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

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

  function parseMinutes(value: string, minimum: number): number | null {
    const parsed = Number(value)
    if (
      value.trim().length === 0 ||
      !Number.isInteger(parsed) ||
      parsed < minimum ||
      parsed > MAX_MINUTES
    ) {
      return null
    }
    return parsed
  }

  async function handleSaveDraft() {
    const slot = parseMinutes(slotInterval, 1)
    const service = parseMinutes(serviceDuration, 1)
    const turnover = parseMinutes(turnoverDuration, 0)
    const nextErrors: Record<string, string> = {}

    if (slot === null) {
      nextErrors.slotInterval = `1분 이상 ${MAX_MINUTES}분 이하 정수로 입력해 주세요.`
    }
    if (service === null) {
      nextErrors.serviceDuration = `1분 이상 ${MAX_MINUTES}분 이하 정수로 입력해 주세요.`
    }
    if (turnover === null) {
      nextErrors.turnoverDuration = `0분 이상 ${MAX_MINUTES}분 이하 정수로 입력해 주세요.`
    }
    if (service !== null && turnover !== null && service + turnover > MAX_MINUTES) {
      nextErrors.serviceDuration = `서비스 시간과 전환 시간의 합은 ${MAX_MINUTES}분을 넘을 수 없습니다.`
    }

    setErrors(nextErrors)
    setFormError(null)
    if (
      Object.keys(nextErrors).length > 0 ||
      slot === null ||
      service === null ||
      turnover === null
    ) {
      return
    }

    const body = {
      slotInterval: slot,
      serviceDuration: service,
      turnoverDuration: turnover,
    }
    try {
      setSaved(
        await saveDraft.mutateAsync({
          body,
          idempotencyKey: draftKeys.keyFor(JSON.stringify(body)),
        }),
      )
    } catch (error) {
      setFormError(reservationOpsErrorMessage(error))
    }
  }

  async function runPublication(
    action: () => Promise<ReservationTimePolicyResponse>,
  ) {
    setFormError(null)
    try {
      setSaved(await action())
    } catch (error) {
      setFormError(reservationOpsErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title="예약 시간 정책"
        description="예약 시작 간격과 이용 시간을 정합니다."
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
          {formError !== null && <Alert tone="error" title={formError} />}

          <SectionCard
            title="시간 값"
            hint="세 값은 각각 다른 의미입니다. 하나로 합쳐 계산하지 않습니다."
          >
            <div className="op-form-grid">
              <TextField
                label="예약 시작 간격(분)"
                required
                inputMode="numeric"
                value={slotInterval}
                help="예약 시작 시각이 놓일 수 있는 간격입니다."
                error={errors.slotInterval ?? null}
                onChange={(event) => {
                  setSlotInterval(event.target.value)
                  setSaved(null)
                }}
              />
              <TextField
                label="서비스 소요시간(분)"
                required
                inputMode="numeric"
                value={serviceDuration}
                help="고객이 실제로 이용하는 시간입니다."
                error={errors.serviceDuration ?? null}
                onChange={(event) => {
                  setServiceDuration(event.target.value)
                  setSaved(null)
                }}
              />
              <TextField
                label="자원 전환 시간(분)"
                required
                inputMode="numeric"
                value={turnoverDuration}
                help="이용 후 정리에 필요한 시간입니다. 0도 허용합니다."
                error={errors.turnoverDuration ?? null}
                onChange={(event) => {
                  setTurnoverDuration(event.target.value)
                  setSaved(null)
                }}
              />
            </div>
          </SectionCard>
        </div>

        <div className="op-stack">
          <SectionCard title="게시" icon="calendar" hint="초안을 저장한 뒤 게시 시점을 정합니다.">
            {saved !== null && (
              <SummaryList
                items={[
                  { term: '정책 버전', value: saved.version },
                  {
                    term: '상태',
                    value: (
                      <Badge
                        tone={saved.status === 'ACTIVE' ? 'positive' : 'neutral'}
                      >
                        {POLICY_STATUS_LABEL[saved.status]}
                      </Badge>
                    ),
                  },
                  {
                    term: '적용 시각',
                    value: saved.effectiveAt ?? '즉시',
                  },
                ]}
              />
            )}

            <PublicationControls
              version={saved?.version ?? null}
              timeZoneId={storeQuery.data.timeZoneId}
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
              정책을 게시해도 이미 접수된 예약의 시각은 바뀌지 않습니다. 게시 후
              새로 들어오는 예약에 적용됩니다.
            </p>
          </SectionCard>
        </div>
      </div>
    </>
  )
}
