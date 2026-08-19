import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { createIdempotencyKeyCache } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert } from '../../../../shared/ui/Feedback'
import {
  PageHeader,
  SectionCard,
  useAdoptStoreFromRoute,
} from '../../../store/store-operator'
import { useReplaceReservationCapacities } from '../api/queries'
import {
  bucketErrorKey,
  createBucketDraft,
  toCapacityBuckets,
  validateCapacityDraft,
  type CapacityBucketDraft,
} from '../model/capacityDraft'
import { reservationOpsErrorMessage } from '../model/errors'
import type { ReservationCapacitiesData } from '../model/types'

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

/**
 * 날짜별 예약 수용량.
 *
 * 이 계약은 **날짜 단위 전체 교체**다. 부분 수정처럼 보이면 운영자가 기존
 * 수용량을 의도치 않게 지운다. 그래서 저장 버튼 문구까지 "전체 교체"로 두고,
 * 현재 설정을 불러오는 조회 계약이 없다는 사실을 먼저 알린다.
 */
export function ReservationCapacityPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const replaceCapacities = useReplaceReservationCapacities(storeId)
  const capacityKeys = useMemo(createIdempotencyKeyCache, [])

  const [serviceDate, setServiceDate] = useState('')
  const [buckets, setBuckets] = useState<CapacityBucketDraft[]>([
    createBucketDraft(),
  ])
  const [errors, setErrors] = useState<Readonly<Record<string, string>>>({})
  const [saved, setSaved] = useState<ReservationCapacitiesData | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  function updateBucket(index: number, next: CapacityBucketDraft) {
    setBuckets((previous) =>
      previous.map((bucket, position) => (position === index ? next : bucket)),
    )
    setSaved(null)
  }

  async function handleSave() {
    const nextErrors: Record<string, string> = {
      ...validateCapacityDraft(buckets),
    }
    if (!DATE_PATTERN.test(serviceDate)) {
      nextErrors.serviceDate = '적용할 날짜를 선택해 주세요.'
    }
    setErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    const payload = {
      serviceDate,
      buckets: toCapacityBuckets(buckets),
    }
    try {
      setSaved(
        await replaceCapacities.mutateAsync({
          ...payload,
          idempotencyKey: capacityKeys.keyFor(JSON.stringify(payload)),
        }),
      )
    } catch (error) {
      setFormError(reservationOpsErrorMessage(error))
    }
  }

  return (
    <>
      <PageHeader
        title="예약 수용량"
        description="선택한 날짜의 시간 구간별 수용량을 설정합니다."
        actions={
          <Button
            variant="primary"
            loading={replaceCapacities.isPending}
            onClick={() => void handleSave()}
          >
            이 날짜 수용량 전체 교체
          </Button>
        }
      />

      <div className="op-grid op-grid--aside">
        <div className="op-stack">
          <Alert tone="warning" title="저장하면 그 날짜의 수용량이 전부 대체됩니다.">
            <p>
              계약은 날짜 단위로 구간 목록 전체를 받습니다. 지금 화면에 없는
              구간은 저장 후 남지 않습니다. 현재 설정을 불러오는 조회 계약이 없어
              이 화면은 빈 목록에서 시작합니다.
            </p>
          </Alert>

          {formError !== null && <Alert tone="error" title={formError} />}

          <SectionCard title="적용 날짜" icon="calendar">
            <TextField
              label="서비스 날짜"
              type="date"
              required
              value={serviceDate}
              error={errors.serviceDate ?? null}
              onChange={(event) => {
                setServiceDate(event.target.value)
                setSaved(null)
              }}
            />
          </SectionCard>

          {errors.buckets !== undefined && (
            <Alert tone="error" title={errors.buckets} />
          )}

          {buckets.map((bucket, index) => (
            <BucketEditor
              key={index}
              index={index}
              bucket={bucket}
              errors={errors}
              removable={buckets.length > 1}
              onChange={(next) => updateBucket(index, next)}
              onRemove={() => {
                setBuckets((previous) =>
                  previous.filter((_, position) => position !== index),
                )
                setSaved(null)
              }}
            />
          ))}

          <div className="op-actions">
            <Button
              variant="ghost"
              onClick={() => {
                setBuckets((previous) => [...previous, createBucketDraft()])
                setSaved(null)
              }}
            >
              구간 추가
            </Button>
          </div>
        </div>

        <div className="op-stack">
          <SectionCard title="저장 결과" icon="check">
            {saved === null ? (
              <p className="op-section__hint">
                저장하면 서버가 계산한 점유·잔여가 여기 표시됩니다.
              </p>
            ) : (
              <>
                <p className="op-section__hint">
                  {`${saved.serviceDate} · 정책 버전 ${saved.policyVersion}`}
                </p>
                <div className="op-table-scroll">
                  <table className="op-table">
                    <caption className="visually-hidden">
                      저장 직후 서버가 계산한 구간별 점유와 잔여
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">구간</th>
                        <th scope="col" className="op-table__numeric">
                          점유 인원
                        </th>
                        <th scope="col" className="op-table__numeric">
                          잔여 인원
                        </th>
                        <th scope="col" className="op-table__numeric">
                          점유 팀
                        </th>
                        <th scope="col" className="op-table__numeric">
                          잔여 팀
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {saved.buckets.map((bucket) => (
                        <tr key={bucket.capacityBucketId}>
                          <th scope="row">
                            {`${bucket.startTime}–${bucket.endTime}`}
                          </th>
                          <td className="op-table__numeric">
                            {bucket.occupiedPeople}
                          </td>
                          <td className="op-table__numeric">
                            {bucket.availablePeople}
                          </td>
                          <td className="op-table__numeric">
                            {bucket.occupiedTeams}
                          </td>
                          <td className="op-table__numeric">
                            {bucket.availableTeams}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="op-section__hint">
                  조회 시점 기준 값입니다. 새 예약이 들어오면 달라집니다.
                </p>
              </>
            )}
          </SectionCard>
        </div>
      </div>
    </>
  )
}

function BucketEditor({
  index,
  bucket,
  errors,
  removable,
  onChange,
  onRemove,
}: {
  index: number
  bucket: CapacityBucketDraft
  errors: Readonly<Record<string, string>>
  removable: boolean
  onChange: (next: CapacityBucketDraft) => void
  onRemove: () => void
}) {
  const position = `${index + 1}번 구간`

  return (
    <section className="op-day" aria-label={position}>
      <div className="op-day__head">
        <span className="op-day__name">{position}</span>
        {removable && (
          <Button
            variant="ghost"
            size="sm"
            aria-label={`${position} 삭제`}
            onClick={onRemove}
          >
            삭제
          </Button>
        )}
      </div>

      <div className="op-form-grid op-form-grid--two">
        <TextField
          label="시작"
          type="time"
          value={bucket.startTime}
          error={errors[bucketErrorKey(index, 'time')] ?? null}
          onChange={(event) =>
            onChange({ ...bucket, startTime: event.target.value })
          }
        />
        <TextField
          label="종료"
          type="time"
          value={bucket.endTime}
          error={null}
          onChange={(event) =>
            onChange({ ...bucket, endTime: event.target.value })
          }
        />
        <TextField
          label="최대 인원"
          inputMode="numeric"
          value={bucket.maxPeople}
          error={errors[bucketErrorKey(index, 'maxPeople')] ?? null}
          onChange={(event) =>
            onChange({ ...bucket, maxPeople: event.target.value })
          }
        />
        <TextField
          label="최대 팀 수"
          inputMode="numeric"
          value={bucket.maxTeams}
          help="0이면 팀 수 제한 없이 인원으로만 판정합니다."
          error={errors[bucketErrorKey(index, 'maxTeams')] ?? null}
          onChange={(event) =>
            onChange({ ...bucket, maxTeams: event.target.value })
          }
        />
        <TextField
          label="예약 최소 인원"
          inputMode="numeric"
          value={bucket.minPartySize}
          error={errors[bucketErrorKey(index, 'minPartySize')] ?? null}
          onChange={(event) =>
            onChange({ ...bucket, minPartySize: event.target.value })
          }
        />
        <TextField
          label="예약 최대 인원"
          inputMode="numeric"
          value={bucket.maxPartySize}
          error={errors[bucketErrorKey(index, 'maxPartySize')] ?? null}
          onChange={(event) =>
            onChange({ ...bucket, maxPartySize: event.target.value })
          }
        />
      </div>

      {/* 설명은 label 밖에 둔다. 안에 넣으면 접근 가능 이름에 섞인다. */}
      <label className="op-check">
        <input
          type="checkbox"
          checked={bucket.infantsAllowed}
          onChange={(event) =>
            onChange({ ...bucket, infantsAllowed: event.target.checked })
          }
        />
        <span className="op-check__text">영유아 동반 허용</span>
      </label>
      <p className="op-check__hint">
        허용하지 않으면 영유아가 포함된 예약을 서버가 거절합니다.
      </p>
    </section>
  )
}
