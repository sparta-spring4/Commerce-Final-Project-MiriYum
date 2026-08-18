import { useState } from 'react'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { toStoreInstant } from '../model/storeTime'
import { validateChangeReason } from '../model/storeValidation'
import type { PublicationMode } from '../model/types'

export interface PublicationSubmission {
  publicationMode: PublicationMode
  /** SCHEDULED일 때만 채운다. IMMEDIATE에 함께 보내면 계약 위반이다. */
  effectiveAt?: string
  changeReason: string
}

interface Props {
  /** 초안 버전. 없으면 게시할 대상이 없다. */
  version: number | null
  /** 매장 시간대. 예약 게시 시각의 오프셋을 이 값으로 계산한다. */
  timeZoneId: string
  /** 예약 게시 취소는 게시 예약 상태에서만 의미가 있다. */
  canCancelPublication: boolean
  publishing: boolean
  cancelling: boolean
  onPublish: (submission: PublicationSubmission) => void
  onCancelPublication: (changeReason: string) => void
}

/**
 * 초안 게시·예약 게시·예약 게시 취소.
 *
 * 네 스케줄 계약(영업시간·예약 접수 시간대·정기 휴무·예약 시간 정책)이 같은
 * 요청 모양을 쓰므로 컴포넌트를 공유한다. 세 규칙을 여기서 한 번만 지킨다.
 *
 * 1. 초안 저장과 게시는 다른 단계다. 초안 버전이 없으면 게시 버튼을 열지 않는다.
 * 2. `IMMEDIATE`에는 `effectiveAt`을 보내지 않는다.
 * 3. `SCHEDULED`는 오프셋을 포함한 미래 시각이어야 한다.
 */
export function PublicationControls({
  version,
  timeZoneId,
  canCancelPublication,
  publishing,
  cancelling,
  onPublish,
  onCancelPublication,
}: Props) {
  const [mode, setMode] = useState<PublicationMode>('IMMEDIATE')
  const [effectiveAtLocal, setEffectiveAtLocal] = useState('')
  const [changeReason, setChangeReason] = useState('')
  const [cancelReason, setCancelReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  if (version === null) {
    return (
      <Alert tone="info" title="먼저 초안을 저장해 주세요.">
        <p>
          게시는 저장된 초안 버전을 대상으로 합니다. 초안을 저장하면 이 자리에
          게시 단계가 열립니다.
        </p>
      </Alert>
    )
  }

  function handlePublish() {
    const nextErrors: Record<string, string> = {}
    const reasonError = validateChangeReason(changeReason)
    if (reasonError !== null) {
      nextErrors.changeReason = reasonError
    }

    let effectiveAt: string | undefined
    if (mode === 'SCHEDULED') {
      const instant = toStoreInstant(timeZoneId, effectiveAtLocal)
      if (instant === null) {
        nextErrors.effectiveAt = '게시할 시각을 입력해 주세요.'
      } else if (instant.epochMs <= Date.now()) {
        nextErrors.effectiveAt = '미래 시각을 입력해 주세요.'
      } else {
        effectiveAt = instant.iso
      }
    }

    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    onPublish(
      mode === 'SCHEDULED'
        ? { publicationMode: mode, effectiveAt, changeReason }
        : { publicationMode: mode, changeReason },
    )
  }

  function handleCancelPublication() {
    const reasonError = validateChangeReason(cancelReason)
    if (reasonError !== null) {
      setErrors({ cancelReason: reasonError })
      return
    }
    setErrors({})
    onCancelPublication(cancelReason)
  }

  return (
    <div className="op-stack">
      {/*
        설명 문구는 label 밖에 둔다. label 안의 모든 텍스트가 접근 가능 이름에
        들어가면 "예약 게시 지정한 시각에 반영합니다…"처럼 한 덩어리로 읽힌다.
      */}
      <fieldset>
        <legend className="op-day__group-title">게시 방식</legend>
        <label className="op-check">
          <input
            type="radio"
            name="publicationMode"
            value="IMMEDIATE"
            checked={mode === 'IMMEDIATE'}
            onChange={() => setMode('IMMEDIATE')}
          />
          <span className="op-check__text">즉시 게시</span>
        </label>
        <p className="op-check__hint">저장한 초안을 지금 반영합니다.</p>

        <label className="op-check">
          <input
            type="radio"
            name="publicationMode"
            value="SCHEDULED"
            checked={mode === 'SCHEDULED'}
            onChange={() => setMode('SCHEDULED')}
          />
          <span className="op-check__text">예약 게시</span>
        </label>
        <p className="op-check__hint">
          지정한 시각에 반영합니다. 그 전까지는 게시를 취소할 수 있습니다.
        </p>
      </fieldset>

      {mode === 'SCHEDULED' && (
        <TextField
          label="게시 시각"
          type="datetime-local"
          required
          value={effectiveAtLocal}
          help={`매장 시간대(${timeZoneId}) 기준으로 전송합니다.`}
          error={errors.effectiveAt ?? null}
          onChange={(event) => setEffectiveAtLocal(event.target.value)}
        />
      )}

      <TextField
        label="변경 사유"
        required
        value={changeReason}
        help="게시 이력에 남습니다."
        error={errors.changeReason ?? null}
        onChange={(event) => setChangeReason(event.target.value)}
      />

      <div className="op-actions">
        <Button
          variant="secondary"
          loading={publishing}
          onClick={handlePublish}
        >
          {mode === 'SCHEDULED' ? `버전 ${version} 게시 예약` : `버전 ${version} 게시`}
        </Button>
      </div>

      {canCancelPublication && (
        <div>
          <p className="op-day__group-title">예약 게시 취소</p>
          <TextField
            label="취소 사유"
            required
            value={cancelReason}
            error={errors.cancelReason ?? null}
            onChange={(event) => setCancelReason(event.target.value)}
          />
          <div className="op-actions">
            <Button
              variant="ghost"
              loading={cancelling}
              onClick={handleCancelPublication}
            >
              예약 게시 취소
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
