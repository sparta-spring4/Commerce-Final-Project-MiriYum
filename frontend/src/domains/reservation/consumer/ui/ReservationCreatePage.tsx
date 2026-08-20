import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { withReturnTo } from '../../../../app/returnTo'
import { createIdempotencyKey } from '../../../../shared/api/idempotencyKey'
import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import { Alert, ErrorState } from '../../../../shared/ui/Feedback'
import { Icon, type IconName } from '../../../../shared/ui/Icon'
import { useCreateReservation, useMenuHoldAvailability } from '../api/queries'
import { useStoreDetail } from '../../../store/public/api/queries'
import {
  MAX_PARTY_PER_TYPE,
  infantsAnnounced as readInfantsAnnounced,
  partyTotal,
  readDraft,
  toCreateRequest,
  validateDraft,
  withoutMenus,
  writeDraft,
  type ReservationDraft,
  type ReservationCreateResult,
  isReservationRequest,
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

  /*
   * 영유아 동반 선택은 검색에서 한 번 들어오고 draft를 고치면 URL에서 사라진다.
   * 그 사실 자체는 이 화면에 머무는 동안 유지돼야 하므로 첫 렌더에 붙잡아 둔다.
   */
  const [infantsAnnounced] = useState(() => readInfantsAnnounced(searchParams))

  /*
   * 메뉴 미리 선택을 받지 않는 매장은 메뉴 단계를 거치지 않는다. 거치게 하면
   * 예약 전용 매장에서도 메뉴 가용성을 조회한 뒤 빈 화면을 보여 주고 사용자가
   * 다시 "다음"을 눌러야 한다.
   */
  const store = useStoreDetail(storeId, {})
  const menuHoldEnabled = store.data?.modes.menuHoldEnabled ?? null
  const steps: ReservationStep[] =
    menuHoldEnabled === false
      ? ['schedule', 'confirm']
      : ['schedule', 'menus', 'confirm']

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

  /** 일정 다음 단계. 메뉴를 받지 않는 매장은 확인으로 곧장 간다. */
  function goToNextAfterSchedule() {
    const errors = validateDraft(draft, { infantsAnnounced })
    setFieldErrors(errors)
    if (Object.keys(errors).length === 0) {
      setStep(menuHoldEnabled === false ? 'confirm' : 'menus')
    }
  }

  function submit(target: ReservationDraft = draft) {
    const errors = validateDraft(target, { infantsAnnounced })
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      setStep('schedule')
      return
    }

    setRecovery(null)
    mutation.mutate(
      { body: toCreateRequest(storeId, target), idempotencyKey: attemptKey },
      {
        onSuccess: handleCreateSuccess,
        /*
         * 실패해도 멱등 키를 바꾸지 않는다.
         *
         * 응답이 유실된 실패에서는 첫 요청이 서버에 이미 커밋됐을 수 있다.
         * 그때 새 키로 다시 보내면 같은 의도가 두 건의 예약이 된다. 같은 키를
         * 유지해야 서버가 앞선 결과를 그대로 돌려주며 하나로 수렴한다.
         *
         * 키는 사용자가 입력을 바꿀 때만 새로 만든다(updateDraft).
         */
        onError: (error) => setRecovery(toCreateRecovery(error)),
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
        onSuccess: handleCreateSuccess,
        onError: (error) => setRecovery(toCreateRecovery(error)),
      },
    )
  }

  function handleCreateSuccess(result: ReservationCreateResult) {
    if (isReservationRequest(result)) {
      void navigate(
        CONSUMER_PATHS.reservationRequestPayment.replace(
          ':reservationRequestId',
          encodeURIComponent(result.reservationRequestId),
        ),
        { replace: true },
      )
      return
    }

    // 계약이 즉시 확정을 정의한다. 다른 상태를 성공으로 표시하지 않는다.
    if (result.status !== 'CONFIRMED') {
      setRecovery({
        kind: 'none',
        message:
          '예약 결과를 확인하지 못했습니다. 내 예약에서 상태를 확인해 주세요.',
      })
      return
    }
    void navigate(`/reservations/${result.reservationId}/complete`, {
      replace: true,
    })
  }

  const currentDestination = `/stores/${storeId}/reserve?${writeDraft(draft).toString()}`

  return (
    <div className="mi-container reservation-create">
      <header className="mi-page-head reservation-create__header">
        <p className="reservation-create__back">
          <Link to={`/stores/${storeId}`}>
            <Icon name="arrowLeft" className="mi-icon--sm" />
            매장 상세로 돌아가기
          </Link>
        </p>
        <h1 className="mi-page-head__title">{STEP_TITLE[step]}</h1>
      </header>

      <ReservationStepper current={step} steps={steps} />

      {/*
        매장 정책을 못 읽으면 다음 단계를 정할 수 없어 버튼이 잠긴다. 이유를
        밝히지 않으면 사용자는 왜 진행이 막혔는지 알 수 없다. 조회 실패와
        조회 중을 구분해 실패에만 다시 시도를 준다.
      */}
      {store.isError && (
        <ErrorState
          error={store.error}
          message="매장 정보를 불러오지 못해 예약을 이어갈 수 없습니다."
          onRetry={() => void store.refetch()}
        />
      )}

      {recovery !== null && (
        <RecoveryAlert
          recovery={recovery}
          onReselectSchedule={() => setStep('schedule')}
          onReselectMenus={() => setStep('menus')}
          onProceedWithoutMenus={proceedWithoutMenus}
          contactHref={withReturnTo(CONSUMER_PATHS.myPage, currentDestination)}
        />
      )}

      {/*
        시안은 단계 내용과 요약 패널을 나란히 두고 패널이 따라 붙게 한다.
        패널의 버튼이 곧 다음 단계로 가는 유일한 주요 행동이다.
      */}
      <div className="reservation-create__layout">
        <div className="reservation-create__main">
          {step === 'schedule' && (
            <ScheduleStep
              draft={draft}
              errors={fieldErrors}
              onChange={updateDraft}
              onNext={goToNextAfterSchedule}
            />
          )}

          {step === 'menus' && (
            <MenuSelectionStep
              storeId={storeId}
              draft={draft}
              onChange={updateDraft}
            />
          )}

          {step === 'confirm' && <ConfirmStep storeId={storeId} draft={draft} />}
        </div>

        <aside className="reservation-create__panel" aria-label="선택 요약">
          <div className="mi-card mi-card--roomy">
            <div className="mi-card__body reservation-summary">
              <h2 className="reservation-summary__title">선택한 조건</h2>

              <ul className="reservation-summary__rows">
                <SummaryRow icon="calendar" label="방문 날짜">
                  {draft.serviceDate.length > 0 ? draft.serviceDate : '아직 없음'}
                </SummaryRow>
                <SummaryRow icon="clock" label="방문 시간">
                  {draft.startTime.length > 0 ? draft.startTime : '아직 없음'}
                </SummaryRow>
                <SummaryRow icon="group" label="인원">
                  {`총 ${partyTotal(draft)}명`}
                </SummaryRow>
                <SummaryRow icon="menu" label="미리 선택한 메뉴">
                  {draft.menuSelections.size === 0
                    ? '없음'
                    : `${draft.menuSelections.size}종`}
                </SummaryRow>
              </ul>

              <div className="reservation-create__actions">
                {step !== 'schedule' && (
                  <Button
                    variant="ghost"
                    disabled={mutation.isPending}
                    // 단계 표에서 한 칸 앞으로 간다. 메뉴 단계가 없는 매장은
                    // 확인에서 곧장 일정으로 돌아간다.
                    onClick={() => setStep(steps[steps.indexOf(step) - 1])}
                  >
                    이전
                  </Button>
                )}

                {step === 'schedule' && (
                  // 폼은 왼쪽 열에 있고 버튼은 패널에 있다. `form` 속성이 둘을
                  // 잇는다. 이렇게 해야 Enter 키 제출과 검증 흐름이 같아진다.
                  //
                  // 매장의 메뉴 정책을 아직 모르면 다음 단계를 정할 수 없다.
                  // 조회가 끝날 때까지 누르지 못하게 한다.
                  <Button
                    type="submit"
                    form={SCHEDULE_FORM_ID}
                    variant="primary"
                    size="lg"
                    block
                    disabled={menuHoldEnabled === null}
                  >
                    {menuHoldEnabled === false ? '예약 확인으로' : '메뉴 선택으로'}
                  </Button>
                )}

                {step === 'menus' && (
                  <Button
                    variant="primary"
                    size="lg"
                    block
                    onClick={() => setStep('confirm')}
                  >
                    다음
                  </Button>
                )}

                {step === 'confirm' && (
                  <Button
                    variant="primary"
                    size="lg"
                    block
                    loading={mutation.isPending}
                    onClick={() => submit()}
                  >
                    예약하기
                  </Button>
                )}
              </div>
            </div>
          </div>
        </aside>
      </div>
    </div>
  )
}

/** 단계마다 화면 제목이 바뀐다. 시안 `_7`·`_8`·`_10`의 제목 그대로다. */
const STEP_TITLE: Record<ReservationStep, string> = {
  schedule: '예약 정보 입력',
  menus: '메뉴 선택',
  confirm: '예약 확인',
}

/** 오른쪽 폼과 왼쪽 버튼을 잇는 id. 화면에 폼이 하나뿐이라 고정값으로 둔다. */
const SCHEDULE_FORM_ID = 'reservation-schedule-form'

function SummaryRow({
  icon,
  label,
  children,
}: {
  icon: IconName
  label: string
  children: ReactNode
}) {
  return (
    <li className="reservation-summary__row">
      <Icon name={icon} />
      <span className="reservation-summary__label">{label}</span>
      <span className="reservation-summary__value">{children}</span>
    </li>
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
      id={SCHEDULE_FORM_ID}
      className="reservation-form"
      aria-label="예약 조건"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        onNext()
      }}
    >
      {/*
        시안은 달력 격자와 시간대 칩으로 고르게 한다. 1차 MVP 계약에는 매장의
        예약 가능 슬롯을 이 화면에서 조회하는 통로가 없어 어떤 칸이 열려 있는지
        알 수 없다. 열리지 않은 칸까지 고를 수 있는 가짜 달력을 만들지 않고,
        같은 카드 안에 날짜·시간 입력을 둔다. 실제 판정은 서버가 한다.
      */}
      <section className="mi-card mi-card--roomy reservation-form__section">
        <div className="mi-card__body mi-card__body--roomy">
          <h2>날짜와 시간</h2>

          <div className="reservation-form__grid">
            <TextField
              label="방문 날짜"
              type="date"
              name="serviceDate"
              required
              value={draft.serviceDate}
              error={errors.serviceDate ?? null}
              onChange={(event) =>
                onChange({ ...draft, serviceDate: event.target.value })
              }
            />

            <TextField
              label="방문 시간"
              type="time"
              name="startTime"
              required
              help="종료 시각은 매장 정책에 따라 서버가 계산합니다."
              value={draft.startTime}
              error={errors.startTime ?? null}
              onChange={(event) =>
                onChange({ ...draft, startTime: event.target.value })
              }
            />
          </div>
        </div>
      </section>

      <section className="mi-card mi-card--roomy reservation-form__section">
        <div className="mi-card__body mi-card__body--roomy">
          <div className="reservation-form__section-head">
            <h2>인원 선택</h2>
            <p className="reservation-form__total">{`총 ${partyTotal(draft)}명`}</p>
          </div>

          <ul className="reservation-form__party">
            <PartyRow
              label="성인"
              hint="만 13세 이상"
              value={draft.adultCount}
              error={errors.adultCount ?? null}
              onChange={(next) => onChange({ ...draft, adultCount: next })}
            />
            <PartyRow
              label="아동"
              hint="36개월 ~ 만 12세"
              value={draft.childCount}
              onChange={(next) => onChange({ ...draft, childCount: next })}
            />
            <PartyRow
              label="영유아"
              hint="36개월 미만"
              value={draft.infantCount}
              error={errors.infantCount ?? null}
              onChange={(next) => onChange({ ...draft, infantCount: next })}
            />
          </ul>
        </div>
      </section>
    </form>
  )
}

/**
 * 인원 한 종류.
 *
 * 시안은 숫자 입력 대신 − / + 버튼을 쓴다. 값 자체는 draft가 문자열로 들고
 * 있으므로 여기서도 문자열로 되돌려 준다. 빈 문자열은 0으로 읽는다.
 */
function PartyRow({
  label,
  hint,
  value,
  error,
  onChange,
}: {
  label: string
  hint: string
  value: string
  error?: string | null
  onChange: (next: string) => void
}) {
  const count = Number.parseInt(value, 10)
  const current = Number.isInteger(count) && count > 0 ? count : 0

  return (
    <li className="reservation-form__party-row">
      <div>
        <p className="reservation-form__party-label">{label}</p>
        <p className="reservation-form__party-hint">{hint}</p>
        {error !== null && error !== undefined && (
          <p className="mi-field__error">{error}</p>
        )}
      </div>

      <div className="mi-counter">
        <button
          type="button"
          className="mi-counter__button"
          aria-label={`${label} 수 줄이기`}
          disabled={current <= 0}
          onClick={() => onChange(String(current - 1))}
        >
          <Icon name="minus" />
        </button>
        <output className="mi-counter__value" aria-label={`${label} 수`}>
          {current}
        </output>
        <button
          type="button"
          className="mi-counter__button"
          aria-label={`${label} 수 늘리기`}
          disabled={current >= MAX_PARTY_PER_TYPE}
          onClick={() => onChange(String(current + 1))}
        >
          <Icon name="plus" />
        </button>
      </div>
    </li>
  )
}

function ConfirmStep({
  storeId,
  draft,
}: {
  storeId: string
  draft: ReservationDraft
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
    <section
      className="mi-card mi-card--roomy reservation-confirm"
      aria-label="예약 확인"
    >
      <div className="mi-card__body mi-card__body--roomy">
        <h2>선택하신 내역을 확인해 주세요</h2>

        {/* 시안 `_10`의 아이콘 + 레이블 + 값 묶음. */}
        <div className="reservation-confirm__grid">
          <div className="reservation-confirm__item">
            <Icon name="calendar" />
            <div>
              <p className="reservation-confirm__label">일정</p>
              <p className="reservation-confirm__value">{draft.serviceDate}</p>
              <p className="reservation-confirm__value">{draft.startTime}</p>
            </div>
          </div>

          <div className="reservation-confirm__item">
            <Icon name="group" />
            <div>
              <p className="reservation-confirm__label">인원</p>
              <p className="reservation-confirm__value">
                {`성인 ${draft.adultCount || 0}명 · 아동 ${draft.childCount || 0}명 · 영유아 ${draft.infantCount || 0}명`}
              </p>
            </div>
          </div>
        </div>

        <div className="reservation-confirm__menus">
          <p className="reservation-confirm__menus-head">
            <Icon name="menu" />
            선택 메뉴
          </p>
          {selections.length === 0 ? (
            <p className="reservation-confirm__value">선택한 메뉴가 없습니다.</p>
          ) : (
            <ul className="reservation-confirm__menu-list">
              {selections.map(([menuId, quantity]) => (
                <li key={menuId}>
                  <span>{menuNames.get(menuId) ?? menuId}</span>
                  <span className="reservation-confirm__quantity">{`x ${quantity}`}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <Alert tone="info" title="예약 확정은 서버가 최종 판정합니다.">
          <p>
            지금까지 본 가용성과 메뉴 수량은 미리보기이며, 예약을 만드는 시점에
            다시 확인합니다.
          </p>
        </Alert>
      </div>
    </section>
  )
}
