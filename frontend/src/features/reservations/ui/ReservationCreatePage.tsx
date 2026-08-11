import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ROUTES } from '../../../app/routes'
import { withReturnTo } from '../../../app/returnTo'
import { createIdempotencyKey } from '../../../shared/api/idempotencyKey'
import { Button } from '../../../shared/ui/Button'
import { TextField } from '../../../shared/ui/Field'
import { Alert } from '../../../shared/ui/Feedback'
import { useCreateReservation, useMenuHoldAvailability } from '../api/queries'
import {
  MAX_PARTY_PER_TYPE,
  partyTotal,
  readDraft,
  toCreateRequest,
  validateDraft,
  withoutMenus,
  writeDraft,
  type ReservationDraft,
} from '../model/draft'
import { toCreateRecovery, type CreateRecovery } from '../model/errors'
import { MenuSelectionStep } from './MenuSelectionStep'
import { ReservationStepper, type ReservationStep } from './ReservationStepper'

/**
 * 예약 작성.
 *
 * draft는 URL search params에 둔다. 연락처 미등록(`ACCOUNT_006`)으로
 * 마이페이지에 다녀와도 입력이 남아 있어야 한다.
 *
 * 예약과 메뉴 홀드는 한 번의 쓰기로 만든다.
 */
export function ReservationCreatePage() {
  const { storeId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const draft = readDraft(searchParams)
  const [step, setStep] = useState<ReservationStep>('schedule')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [recovery, setRecovery] = useState<CreateRecovery | null>(null)

  /**
   * 멱등 키. 같은 입력으로 재시도하는 동안 유지하고, 사용자가 입력을 바꿔
   * 새로 제출하면 새 시도이므로 새 키를 만든다.
   */
  const [attemptKey, setAttemptKey] = useState(createIdempotencyKey)

  const mutation = useCreateReservation()

  function updateDraft(next: ReservationDraft) {
    // 조건이 바뀌면 이전 시도의 결과와 멱등 키를 버린다.
    setRecovery(null)
    setAttemptKey(createIdempotencyKey())
    setSearchParams(writeDraft(next), { replace: true })
  }

  function goToMenus() {
    const errors = validateDraft(draft)
    setFieldErrors(errors)
    if (Object.keys(errors).length === 0) {
      setStep('menus')
    }
  }

  function submit(target: ReservationDraft = draft) {
    const errors = validateDraft(target)
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      setStep('schedule')
      return
    }

    setRecovery(null)
    mutation.mutate(
      { body: toCreateRequest(storeId, target), idempotencyKey: attemptKey },
      {
        onSuccess: (reservation) => {
          // 계약이 즉시 확정을 정의한다. 다른 상태를 성공으로 표시하지 않는다.
          if (reservation.status !== 'CONFIRMED') {
            setRecovery({
              kind: 'none',
              message:
                '예약 결과를 확인하지 못했습니다. 내 예약에서 상태를 확인해 주세요.',
            })
            return
          }
          void navigate(`/reservations/${reservation.reservationId}`, {
            replace: true,
          })
        },
        onError: (error) => {
          setRecovery(toCreateRecovery(error))
          // 확정 실패 뒤 다음 제출은 새 시도다. 같은 키를 재사용하면 COMMON_007이 된다.
          setAttemptKey(createIdempotencyKey())
        },
      },
    )
  }

  function proceedWithoutMenus() {
    const next = withoutMenus(draft)
    setSearchParams(writeDraft(next), { replace: true })
    setRecovery(null)
    const key = createIdempotencyKey()
    setAttemptKey(key)
    mutation.mutate(
      { body: toCreateRequest(storeId, next), idempotencyKey: key },
      {
        onSuccess: (reservation) =>
          void navigate(`/reservations/${reservation.reservationId}`, {
            replace: true,
          }),
        onError: (error) => setRecovery(toCreateRecovery(error)),
      },
    )
  }

  const currentDestination = `/stores/${storeId}/reserve?${writeDraft(draft).toString()}`

  return (
    <div className="mi-container mi-container--narrow reservation-create">
      <header className="reservation-create__header">
        <h1>예약 정보 입력</h1>
        <p>
          <Link to={`/stores/${storeId}`}>매장 상세로 돌아가기</Link>
        </p>
      </header>

      <ReservationStepper current={step} />

      {recovery !== null && (
        <RecoveryAlert
          recovery={recovery}
          onReselectSchedule={() => setStep('schedule')}
          onReselectMenus={() => setStep('menus')}
          onProceedWithoutMenus={proceedWithoutMenus}
          contactHref={withReturnTo(ROUTES.myPage, currentDestination)}
        />
      )}

      {step === 'schedule' && (
        <ScheduleStep
          draft={draft}
          errors={fieldErrors}
          onChange={updateDraft}
          onNext={goToMenus}
        />
      )}

      {step === 'menus' && (
        <>
          <MenuSelectionStep
            storeId={storeId}
            draft={draft}
            onChange={updateDraft}
          />
          <div className="reservation-create__actions">
            <Button variant="ghost" onClick={() => setStep('schedule')}>
              이전
            </Button>
            <Button variant="primary" onClick={() => setStep('confirm')}>
              다음
            </Button>
          </div>
        </>
      )}

      {step === 'confirm' && (
        <ConfirmStep
          storeId={storeId}
          draft={draft}
          submitting={mutation.isPending}
          onBack={() => setStep('menus')}
          onSubmit={() => submit()}
        />
      )}
    </div>
  )
}

function RecoveryAlert({
  recovery,
  onReselectSchedule,
  onReselectMenus,
  onProceedWithoutMenus,
  contactHref,
}: {
  recovery: CreateRecovery
  onReselectSchedule: () => void
  onReselectMenus: () => void
  onProceedWithoutMenus: () => void
  contactHref: string
}) {
  return (
    <Alert
      tone="error"
      title={recovery.message}
      actions={
        <>
          {recovery.kind === 'reselectSchedule' && (
            <Button variant="ghost" size="sm" onClick={onReselectSchedule}>
              조건 다시 선택
            </Button>
          )}
          {recovery.kind === 'reselectMenus' && (
            <>
              <Button variant="ghost" size="sm" onClick={onReselectMenus}>
                메뉴 다시 선택
              </Button>
              <Button variant="ghost" size="sm" onClick={onProceedWithoutMenus}>
                메뉴 없이 예약
              </Button>
            </>
          )}
          {recovery.kind === 'registerContact' && (
            // 마이페이지에서 연락처를 등록하고 이 화면으로 되돌아온다.
            <Link
              className="mi-button mi-button--ghost mi-button--sm"
              to={contactHref}
            >
              연락처 등록하러 가기
            </Link>
          )}
        </>
      }
    />
  )
}

function ScheduleStep({
  draft,
  errors,
  onChange,
  onNext,
}: {
  draft: ReservationDraft
  errors: Record<string, string>
  onChange: (next: ReservationDraft) => void
  onNext: () => void
}) {
  return (
    <form
      className="reservation-form"
      aria-label="예약 조건"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        onNext()
      }}
    >
      <TextField
        label="방문 날짜"
        type="date"
        name="serviceDate"
        required
        value={draft.serviceDate}
        error={errors.serviceDate ?? null}
        onChange={(event) => onChange({ ...draft, serviceDate: event.target.value })}
      />

      <TextField
        label="방문 시간"
        type="time"
        name="startTime"
        required
        help="종료 시각은 매장 정책에 따라 서버가 계산합니다."
        value={draft.startTime}
        error={errors.startTime ?? null}
        onChange={(event) => onChange({ ...draft, startTime: event.target.value })}
      />

      <div className="reservation-form__party">
        <TextField
          label="성인"
          type="number"
          name="adultCount"
          min={0}
          max={MAX_PARTY_PER_TYPE}
          value={draft.adultCount}
          error={errors.adultCount ?? null}
          onChange={(event) => onChange({ ...draft, adultCount: event.target.value })}
        />
        <TextField
          label="아동"
          type="number"
          name="childCount"
          min={0}
          max={MAX_PARTY_PER_TYPE}
          value={draft.childCount}
          onChange={(event) => onChange({ ...draft, childCount: event.target.value })}
        />
        <TextField
          label="영유아"
          type="number"
          name="infantCount"
          min={0}
          max={MAX_PARTY_PER_TYPE}
          value={draft.infantCount}
          onChange={(event) => onChange({ ...draft, infantCount: event.target.value })}
        />
      </div>

      <p className="reservation-form__total">{`총 ${partyTotal(draft)}명`}</p>

      <Button type="submit" variant="primary" block>
        메뉴 선택으로
      </Button>
    </form>
  )
}

function ConfirmStep({
  storeId,
  draft,
  submitting,
  onBack,
  onSubmit,
}: {
  storeId: string
  draft: ReservationDraft
  submitting: boolean
  onBack: () => void
  onSubmit: () => void
}) {
  // 같은 query key라 메뉴 단계에서 받은 결과를 그대로 재사용한다.
  const availability = useMenuHoldAvailability(
    storeId,
    draft.serviceDate,
    draft.startTime,
    draft.menuSelections.size > 0,
  )

  const menuNames = new Map(
    (availability.data?.items ?? []).map((item) => [item.menuId, item.menuName]),
  )

  const selections = [...draft.menuSelections]

  return (
    <section className="mi-card reservation-confirm" aria-label="예약 확인">
      <div className="mi-card__body">
        <h2>선택하신 내역을 확인해 주세요</h2>

        <dl className="reservation-confirm__list">
          <dt>일정</dt>
          <dd>{`${draft.serviceDate} ${draft.startTime}`}</dd>
          <dt>인원</dt>
          <dd>
            {`성인 ${draft.adultCount || 0}명 · 아동 ${draft.childCount || 0}명 · 영유아 ${draft.infantCount || 0}명`}
          </dd>
          <dt>선택 메뉴</dt>
          <dd>
            {selections.length === 0
              ? '선택한 메뉴가 없습니다.'
              : selections
                  .map(
                    ([menuId, quantity]) =>
                      `${menuNames.get(menuId) ?? menuId} x ${quantity}`,
                  )
                  .join(', ')}
          </dd>
        </dl>

        <Alert tone="info" title="예약 확정은 서버가 최종 판정합니다.">
          <p>
            지금까지 본 가용성과 메뉴 수량은 미리보기이며, 예약을 만드는 시점에
            다시 확인합니다.
          </p>
        </Alert>

        <div className="reservation-create__actions">
          <Button variant="ghost" onClick={onBack} disabled={submitting}>
            이전
          </Button>
          <Button variant="primary" loading={submitting} onClick={onSubmit}>
            예약하기
          </Button>
        </div>
      </div>
    </section>
  )
}
