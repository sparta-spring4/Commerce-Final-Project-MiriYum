import { useMemo, useState } from 'react'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { SelectField, TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  useCancelTemporaryClosure,
  useChangeTemporaryClosureEndAt,
  useCreateTemporaryClosure,
} from '../api/scheduleMutations'
import { storeErrorMessage } from '../model/storeErrors'
import { formatStoreDateTime, toStoreInstant } from '../model/storeTime'
import { validateChangeReason } from '../model/storeValidation'
import {
  TEMPORARY_CLOSURE_REASON_LABEL,
  type TemporaryClosureData,
  type TemporaryClosureReason,
} from '../model/types'
import { SectionCard, SummaryList } from './PageHeader'

const REASONS: readonly TemporaryClosureReason[] = [
  'MAINTENANCE',
  'STAFFING',
  'PRIVATE_EVENT',
  'OTHER',
]

const TEMPORARY_CLOSURE_STATUS_LABEL: Record<
  TemporaryClosureData['status'],
  string
> = {
  SCHEDULED: '예정',
  ACTIVE: '휴점 중',
  ENDED: '종료됨',
  CANCELLED: '취소됨',
}

/**
 * 임시 휴점.
 *
 * 등록 후에는 서버가 준 `closureId`와 상태만 가지고 종료 시각 변경·취소를
 * 이어 간다. 운영자용 휴점 목록 조회 계약이 없으므로 임의 조회 API를 만들지 않고,
 * 이 화면에서 방금 만든 항목만 다룬다.
 */
export function TemporaryClosureSection({
  storeId,
  timeZoneId,
}: {
  storeId: string
  timeZoneId: string
}) {
  const createClosure = useCreateTemporaryClosure(storeId)
  const changeEndAt = useChangeTemporaryClosureEndAt(storeId)
  const cancelClosure = useCancelTemporaryClosure(storeId)

  const createKeys = useMemo(createIdempotencyKeyCache, [])
  const endAtKeys = useMemo(createIdempotencyKeyCache, [])
  const cancelKeys = useMemo(createIdempotencyKeyCache, [])

  const [startAtLocal, setStartAtLocal] = useState('')
  const [endAtLocal, setEndAtLocal] = useState('')
  const [reason, setReason] = useState<TemporaryClosureReason>('MAINTENANCE')
  const [publicMessage, setPublicMessage] = useState('')
  const [nextEndAtLocal, setNextEndAtLocal] = useState('')
  const [endAtReason, setEndAtReason] = useState('')
  const [cancelReason, setCancelReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [closure, setClosure] = useState<TemporaryClosureData | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  async function handleCreate() {
    const start = toStoreInstant(timeZoneId, startAtLocal)
    const end = toStoreInstant(timeZoneId, endAtLocal)
    const nextErrors: Record<string, string> = {}

    if (start === null) {
      nextErrors.startAt = '휴점 시작 시각을 입력해 주세요.'
    }
    if (end === null) {
      nextErrors.endAt = '휴점 종료 시각을 입력해 주세요.'
    }
    if (start !== null && end !== null && end.epochMs <= start.epochMs) {
      nextErrors.endAt = '종료 시각이 시작 시각보다 뒤여야 합니다.'
    }

    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0 || start === null || end === null) {
      return
    }

    const trimmedMessage = publicMessage.trim()
    const body = {
      startAt: start.iso,
      endAt: end.iso,
      reason,
      // 선택 필드다. 빈 문자열을 보내 공개 안내를 지우지 않는다.
      ...(trimmedMessage.length > 0 ? { publicMessage: trimmedMessage } : {}),
    }

    try {
      setClosure(
        await createClosure.mutateAsync({
          body,
          idempotencyKey: createKeys.keyFor(JSON.stringify(body)),
        }),
      )
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  async function handleChangeEndAt() {
    if (closure === null) {
      return
    }
    const end = toStoreInstant(timeZoneId, nextEndAtLocal)
    const reasonError = validateChangeReason(endAtReason)
    const nextErrors: Record<string, string> = {}

    if (end === null) {
      nextErrors.nextEndAt = '변경할 종료 시각을 입력해 주세요.'
    }
    if (reasonError !== null) {
      nextErrors.endAtReason = reasonError
    }
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0 || end === null) {
      return
    }

    const body = { endAt: end.iso, changeReason: endAtReason }
    try {
      setClosure(
        await changeEndAt.mutateAsync({
          closureId: closure.closureId,
          body,
          idempotencyKey: endAtKeys.keyFor(
            JSON.stringify({ closureId: closure.closureId, body }),
          ),
        }),
      )
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  async function handleCancel() {
    if (closure === null) {
      return
    }
    const reasonError = validateChangeReason(cancelReason)
    if (reasonError !== null) {
      setErrors({ cancelReason: reasonError })
      return
    }
    setErrors({})
    setFormError(null)

    const body = { changeReason: cancelReason }
    try {
      setClosure(
        await cancelClosure.mutateAsync({
          closureId: closure.closureId,
          body,
          idempotencyKey: cancelKeys.keyFor(
            JSON.stringify({ closureId: closure.closureId, body }),
          ),
        }),
      )
    } catch (error) {
      setFormError(storeErrorMessage(error))
    }
  }

  /** 취소·종료된 휴점에는 변경 명령을 열지 않는다. */
  const changeable =
    closure !== null &&
    (closure.status === 'SCHEDULED' || closure.status === 'ACTIVE')

  return (
    <div className="op-grid op-grid--aside">
      <div className="op-stack">
        <SectionCard
          title="임시 휴점 등록"
          hint={`시각은 매장 시간대(${timeZoneId}) 기준으로 전송합니다.`}
        >
          {formError !== null && <Alert tone="error" title={formError} />}

          <div className="op-form-grid op-form-grid--two">
            <TextField
              label="휴점 시작"
              type="datetime-local"
              required
              value={startAtLocal}
              error={errors.startAt ?? null}
              onChange={(event) => setStartAtLocal(event.target.value)}
            />
            <TextField
              label="휴점 종료"
              type="datetime-local"
              required
              value={endAtLocal}
              error={errors.endAt ?? null}
              onChange={(event) => setEndAtLocal(event.target.value)}
            />
          </div>

          <div className="op-form-grid">
            <SelectField
              label="휴점 사유"
              required
              value={reason}
              onChange={(event) =>
                setReason(event.target.value as TemporaryClosureReason)
              }
            >
              {REASONS.map((value) => (
                <option key={value} value={value}>
                  {TEMPORARY_CLOSURE_REASON_LABEL[value]}
                </option>
              ))}
            </SelectField>

            <TextField
              label="고객 안내 문구"
              value={publicMessage}
              help="선택 입력입니다. 비워 두면 안내를 표시하지 않습니다."
              onChange={(event) => setPublicMessage(event.target.value)}
            />
          </div>

          <div className="op-actions">
            <Button
              variant="primary"
              loading={createClosure.isPending}
              onClick={() => void handleCreate()}
            >
              임시 휴점 등록
            </Button>
          </div>
        </SectionCard>
      </div>

      <div className="op-stack">
        <SectionCard title="등록한 임시 휴점" icon="calendar-off">
          {closure === null ? (
            <p className="op-section__hint">
              이 화면에서 등록한 휴점이 표시됩니다. 지난 휴점을 조회하는 계약은
              아직 없습니다.
            </p>
          ) : (
            <>
              <SummaryList
                items={[
                  { term: '휴점 ID', value: closure.closureId },
                  {
                    term: '상태',
                    value: (
                      <Badge
                        tone={
                          closure.status === 'CANCELLED' ? 'neutral' : 'attention'
                        }
                      >
                        {TEMPORARY_CLOSURE_STATUS_LABEL[closure.status]}
                      </Badge>
                    ),
                  },
                  {
                    term: '시작',
                    value: formatStoreDateTime(
                      closure.timeZoneId,
                      closure.startAt,
                    ),
                  },
                  {
                    term: '종료',
                    value: formatStoreDateTime(closure.timeZoneId, closure.endAt),
                  },
                  {
                    term: '사유',
                    value: TEMPORARY_CLOSURE_REASON_LABEL[closure.reason],
                  },
                  { term: '변경 버전', value: closure.changeVersion },
                ]}
              />

              {changeable && (
                <>
                  <div className="op-day__group">
                    <p className="op-day__group-title">종료 시각 변경</p>
                    <TextField
                      label="새 종료 시각"
                      type="datetime-local"
                      value={nextEndAtLocal}
                      error={errors.nextEndAt ?? null}
                      onChange={(event) => setNextEndAtLocal(event.target.value)}
                    />
                    <TextField
                      label="종료 시각 변경 사유"
                      value={endAtReason}
                      error={errors.endAtReason ?? null}
                      onChange={(event) => setEndAtReason(event.target.value)}
                    />
                    <div className="op-actions">
                      <Button
                        variant="ghost"
                        loading={changeEndAt.isPending}
                        onClick={() => void handleChangeEndAt()}
                      >
                        종료 시각 변경
                      </Button>
                    </div>
                  </div>

                  <div className="op-day__group">
                    <p className="op-day__group-title">임시 휴점 취소</p>
                    <TextField
                      label="휴점 취소 사유"
                      value={cancelReason}
                      error={errors.cancelReason ?? null}
                      onChange={(event) => setCancelReason(event.target.value)}
                    />
                    <div className="op-actions">
                      <Button
                        variant="ghost"
                        loading={cancelClosure.isPending}
                        onClick={() => void handleCancel()}
                      >
                        임시 휴점 취소
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </>
          )}
        </SectionCard>
      </div>
    </div>
  )
}
