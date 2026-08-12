import { useMemo, useState } from 'react'
import { createIdempotencyKeyCache } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { SectionCard } from '../../store-operator'
import {
  useCancelStoreReservation,
  useFulfillStoreReservation,
} from '../api/queries'
import { reservationOpsErrorMessage } from '../model/errors'
import {
  RESERVATION_STATUS_LABEL,
  type ReservationDetail,
} from '../model/types'

/** `StoreCancellationRequest.reason`의 maxLength */
const REASON_MAX_LENGTH = 500

/**
 * 예약 취소·방문 완료.
 *
 * 두 명령 모두 `CONFIRMED` 예약에서만 성립한다. 상태가 다르면 서버가
 * `RESERVATION_005`로 거절하므로, 화면도 그 상태에서만 버튼을 연다. 상태별로
 * 되지 않을 행동을 열어 두면 운영자에게 실패할 행동을 약속하는 셈이 된다.
 *
 * 취소는 사유를 요구하고 방문 완료는 요구하지 않는다. 대신 방문 완료는 되돌릴 수
 * 없고 한 번의 오클릭으로 끝나므로 확인 단계를 둔다.
 */
export function ReservationCommandPanel({
  storeId,
  reservation,
}: {
  storeId: string
  reservation: ReservationDetail
}) {
  const cancelReservation = useCancelStoreReservation(
    storeId,
    reservation.reservationId,
  )
  const fulfillReservation = useFulfillStoreReservation(
    storeId,
    reservation.reservationId,
  )

  const cancelKeys = useMemo(createIdempotencyKeyCache, [])
  const fulfillKeys = useMemo(createIdempotencyKeyCache, [])

  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [confirmingFulfillment, setConfirmingFulfillment] = useState(false)
  const [commandError, setCommandError] = useState<string | null>(null)

  if (reservation.status !== 'CONFIRMED') {
    return (
      <SectionCard title="예약 처리">
        <Alert
          tone="info"
          title={`이미 ${RESERVATION_STATUS_LABEL[reservation.status]} 상태입니다.`}
        >
          <p>
            취소와 방문 완료는 예약 확정 상태에서만 할 수 있습니다. 처리한 예약은
            이 화면에서 되돌리지 않습니다.
          </p>
        </Alert>
      </SectionCard>
    )
  }

  function validateReason(): string | null {
    const trimmed = reason.trim()
    if (trimmed.length === 0) {
      return '취소 사유를 입력해 주세요. 취소 이력에 남습니다.'
    }
    if (trimmed.length > REASON_MAX_LENGTH) {
      return `취소 사유는 ${REASON_MAX_LENGTH}자를 넘을 수 없습니다.`
    }
    return null
  }

  async function handleCancel() {
    const error = validateReason()
    setReasonError(error)
    setCommandError(null)
    if (error !== null) {
      return
    }

    const trimmed = reason.trim()
    try {
      await cancelReservation.mutateAsync({
        reason: trimmed,
        // 같은 사유로 다시 눌러도 같은 키다. 사유를 고쳐 보내면 새 키를 받는다.
        idempotencyKey: cancelKeys.keyFor(
          JSON.stringify({ reservation: reservation.reservationId, trimmed }),
        ),
      })
    } catch (caught) {
      setCommandError(reservationOpsErrorMessage(caught))
    }
  }

  async function handleFulfill() {
    setCommandError(null)
    try {
      await fulfillReservation.mutateAsync({
        idempotencyKey: fulfillKeys.keyFor(reservation.reservationId),
      })
      setConfirmingFulfillment(false)
    } catch (caught) {
      setCommandError(reservationOpsErrorMessage(caught))
    }
  }

  return (
    <SectionCard
      title="예약 처리"
      hint="처리 결과는 고객 화면과 수용량에 곧바로 반영됩니다."
    >
      {commandError !== null && <Alert tone="error" title={commandError} />}

      <div className="op-day__group">
        <p className="op-day__group-title">방문 완료</p>
        <p className="op-section__hint">
          고객이 방문해 이용을 마쳤을 때 처리합니다. 되돌릴 수 없습니다.
        </p>

        {confirmingFulfillment ? (
          <div className="op-actions">
            <Button
              variant="secondary"
              loading={fulfillReservation.isPending}
              onClick={() => void handleFulfill()}
            >
              방문 완료로 처리
            </Button>
            <Button
              variant="ghost"
              disabled={fulfillReservation.isPending}
              onClick={() => setConfirmingFulfillment(false)}
            >
              그만두기
            </Button>
          </div>
        ) : (
          <div className="op-actions">
            <Button
              variant="secondary"
              onClick={() => {
                setCommandError(null)
                setConfirmingFulfillment(true)
              }}
            >
              방문 완료 처리
            </Button>
          </div>
        )}
      </div>

      <div className="op-day__group">
        <p className="op-day__group-title">매장 사유 취소</p>
        <p className="op-section__hint">
          취소하면 수용량이 되돌아가고 고객에게 사유가 전달됩니다.
        </p>

        <TextField
          label="취소 사유"
          required
          value={reason}
          help={`${REASON_MAX_LENGTH}자 이내로 입력해 주세요.`}
          error={reasonError}
          onChange={(event) => {
            setReason(event.target.value)
            setReasonError(null)
          }}
        />

        <div className="op-actions">
          <Button
            variant="danger"
            loading={cancelReservation.isPending}
            onClick={() => void handleCancel()}
          >
            예약 취소
          </Button>
        </div>
      </div>
    </SectionCard>
  )
}
