import { useState } from 'react'
import { Link } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { Button } from '../../../../shared/ui/Button'
import { FieldShell } from '../../../../shared/ui/Field'
import { Alert, Loading } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import {
  MIN_WAITING_PARTY_SIZE,
  WAITING_REGISTRATION_GUIDANCE,
  readPartySize,
  validatePartySize,
  type WaitingReceptionState,
  type WaitingRegistrationNotice,
  type WaitingRegistrationProgress,
  type WaitingRegistrationResult,
} from '../model/registrationView'
import './waitingRegistration.css'

interface Props {
  storeId: string
  /** 매장 이름. 아직 불러오지 못했으면 넘기지 않고 제목만 보여 준다. */
  storeName?: string
  /** 접수 가능 여부. 서버 availability 판정을 그대로 옮겨 담는다. */
  reception: WaitingReceptionState
  /** 위치 확인·등록 진행 단계. */
  progress: WaitingRegistrationProgress
  partySize: number
  /**
   * 서버가 준 인원 필드 오류. 있으면 화면 자체 검증 문구 대신 이 문구를 쓴다.
   * 서버 판단이 화면 판단보다 항상 우선한다.
   */
  partySizeError?: string | null
  /** 등록을 막은 사유. 위치 판정 결과나 등록 오류를 그대로 옮겨 담는다. */
  notice?: WaitingRegistrationNotice | null
  /** 등록 성공 결과. `progress`가 `succeeded`일 때만 쓴다. */
  result?: WaitingRegistrationResult | null
  onPartySizeChange: (next: number) => void
  onSubmit: () => void
  onRetryLocation: () => void
}

/**
 * 소비자 웨이팅 등록 화면.
 *
 * 이 컴포넌트는 화면과 사용자 조작만 소유한다. 위치 측정, 위치 증빙 판정,
 * 웨이팅 등록 요청은 모두 바깥 컨테이너가 수행하고 결과만 props로 내려 준다.
 * 화면이 직접 `navigator.geolocation`을 부르거나 API를 호출하지 않는다.
 *
 * "사용자가 등록을 실행한 시점에만 위치를 요청한다"는 계약이 이 경계에서
 * 지켜진다. 화면에는 렌더만으로 위치를 요구하는 실행 지점이 없다.
 */
export function WaitingRegistrationPage({
  storeId,
  storeName,
  reception,
  progress,
  partySize,
  partySizeError,
  notice,
  result,
  onPartySizeChange,
  onSubmit,
  onRetryLocation,
}: Props) {
  /*
   * 검증 문구를 언제 보일지 정하는 화면 상태다.
   *
   * 들어오자마자 붉은 오류를 띄우지 않는다. 값을 바꿨거나 등록을 눌러 본
   * 뒤에만 보여 준다.
   */
  const [dirty, setDirty] = useState(false)
  const [attempted, setAttempted] = useState(false)

  const storeDetailPath = `/stores/${storeId}`

  /* 위치 확인과 등록은 서로 다른 기다림이지만 조작을 막는 조건은 같다. */
  const busy = progress === 'locating' || progress === 'registering'
  const done = progress === 'succeeded'

  const clientError = dirty || attempted ? validatePartySize(partySize) : null
  const fieldError = partySizeError ?? clientError

  function changePartySize(next: number) {
    setDirty(true)
    onPartySizeChange(next)
  }

  function submit() {
    setAttempted(true)
    /*
     * 진행 중이거나 이미 끝난 요청은 다시 보내지 않는다.
     *
     * 버튼도 함께 비활성화하지만 키보드 Enter와 빠른 연속 클릭이 비활성화
     * 사이를 빠져나갈 수 있어 핸들러에서 한 번 더 막는다.
     */
    if (busy || done) {
      return
    }
    if (validatePartySize(partySize) !== null) {
      return
    }
    onSubmit()
  }

  return (
    <div className="mi-container mi-container--narrow waiting-register">
      <header className="mi-page-head waiting-register__header">
        <p className="waiting-register__back">
          <Link to={storeDetailPath}>
            <Icon name="arrowLeft" className="mi-icon--sm" />
            매장 상세로 돌아가기
          </Link>
        </p>
        <h1 className="mi-page-head__title">웨이팅 등록</h1>
        <p className="mi-page-head__lead">
          {storeName === undefined
            ? '매장 근처에서 현재 위치를 확인한 뒤 대기 순번을 받습니다.'
            : `${storeName} 근처에서 현재 위치를 확인한 뒤 대기 순번을 받습니다.`}
        </p>
      </header>

      {reception === 'checking' && (
        <Loading label="웨이팅 접수 가능 여부를 확인하는 중입니다." />
      )}

      {reception === 'closed' && (
        <Alert tone="info" title="지금은 웨이팅을 받지 않습니다.">
          <p>매장이 접수를 다시 열면 이 화면에서 등록할 수 있습니다.</p>
          <p className="waiting-register__closed-link">
            <Link to={storeDetailPath}>매장 상세로 돌아가기</Link>
          </p>
        </Alert>
      )}

      {reception === 'accepting' &&
        (done && result != null ? (
          <RegistrationResult
            result={result}
            storeDetailPath={storeDetailPath}
          />
        ) : (
          <form
            className="waiting-register__form"
            aria-label="웨이팅 등록"
            noValidate
            onSubmit={(event) => {
              event.preventDefault()
              submit()
            }}
          >
            <section className="mi-card mi-card--roomy">
              <div className="mi-card__body mi-card__body--roomy">
                <h2 className="waiting-register__section-title">방문 인원</h2>

                <FieldShell
                  label="방문 인원"
                  required
                  help="본인을 포함한 실제 방문 인원수를 입력해 주세요."
                  error={fieldError}
                >
                  {({ controlId, describedBy, invalid }) => (
                    <div className="mi-counter waiting-register__counter">
                      <button
                        type="button"
                        className="mi-counter__button"
                        aria-label="인원 줄이기"
                        disabled={
                          busy || done || partySize <= MIN_WAITING_PARTY_SIZE
                        }
                        onClick={() => changePartySize(partySize - 1)}
                      >
                        <Icon name="minus" />
                      </button>
                      {/*
                        버튼만 두지 않고 입력 자체를 남긴다. 인원이 많은 팀은 한
                        번에 적는 편이 빠르고, 보조기술도 현재 값을 입력 값으로
                        읽는다.
                      */}
                      <input
                        id={controlId}
                        className="mi-counter__value waiting-register__party-input"
                        type="number"
                        inputMode="numeric"
                        min={MIN_WAITING_PARTY_SIZE}
                        step={1}
                        autoComplete="off"
                        required
                        disabled={busy || done}
                        value={partySize}
                        aria-invalid={invalid || undefined}
                        aria-describedby={describedBy}
                        onChange={(event) =>
                          changePartySize(readPartySize(event.target.value))
                        }
                      />
                      <button
                        type="button"
                        className="mi-counter__button"
                        aria-label="인원 늘리기"
                        disabled={busy || done}
                        onClick={() => changePartySize(partySize + 1)}
                      >
                        <Icon name="plus" />
                      </button>
                    </div>
                  )}
                </FieldShell>
              </div>
            </section>

            <p className="waiting-register__location-note">
              <Icon name="pin" className="mi-icon--sm" />
              등록을 누를 때만 현재 위치를 확인합니다. 위치는 매장 근처인지
              확인하는 데에만 쓰고 따로 저장하지 않습니다.
            </p>

            {/*
              진행 문구는 항상 같은 자리에 둔다. 내용이 있을 때만 요소를 만들면
              보조기술이 새 영역을 늦게 잡아 첫 안내를 놓칠 수 있다.
            */}
            <p
              className="waiting-register__progress"
              role="status"
              aria-live="polite"
            >
              {busy && <span className="mi-spinner" aria-hidden="true" />}
              {progress === 'locating' && '현재 위치를 확인하는 중입니다.'}
              {progress === 'registering' && '웨이팅을 등록하는 중입니다.'}
            </p>

            {notice != null && (
              <RegistrationNotice
                notice={notice}
                busy={busy}
                onRetryLocation={onRetryLocation}
                onRetrySubmit={submit}
              />
            )}

            <Button
              type="submit"
              variant="primary"
              size="lg"
              block
              loading={busy}
              disabled={done}
            >
              현재 위치 확인 후 웨이팅 등록
            </Button>
          </form>
        ))}
    </div>
  )
}

/**
 * 등록을 막은 사유와 다음 행동.
 *
 * 위치 판정에서 온 사유는 같은 요청을 다시 보내도 결과가 같으므로 위치를 다시
 * 확인하게 하고, 그 밖의 실패만 재전송을 권한다.
 */
function RegistrationNotice({
  notice,
  busy,
  onRetryLocation,
  onRetrySubmit,
}: {
  notice: WaitingRegistrationNotice
  busy: boolean
  onRetryLocation: () => void
  onRetrySubmit: () => void
}) {
  const guidance = WAITING_REGISTRATION_GUIDANCE[notice.code]
  const retryLabel =
    guidance.retry === 'location' ? '현재 위치 다시 확인' : '다시 시도'

  return (
    <Alert
      tone="error"
      title={guidance.title}
      actions={
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          onClick={
            guidance.retry === 'location' ? onRetryLocation : onRetrySubmit
          }
        >
          {retryLabel}
        </Button>
      }
    >
      <p>{notice.message ?? guidance.description}</p>
    </Alert>
  )
}

/** 등록 성공. 순번과 앞 팀 수는 서버가 준 값을 그대로 보여 준다. */
function RegistrationResult({
  result,
  storeDetailPath,
}: {
  result: WaitingRegistrationResult
  storeDetailPath: string
}) {
  return (
    <section
      className="mi-card mi-card--roomy waiting-register__result"
      role="status"
      aria-live="polite"
    >
      <div className="mi-card__body mi-card__body--roomy">
        <p className="waiting-register__result-head">
          <Icon name="checkCircle" />
          웨이팅을 등록했습니다.
        </p>

        <dl className="waiting-register__summary">
          <div>
            <dt>내 순번</dt>
            <dd>{`${result.queueSequence}번`}</dd>
          </div>
          <div>
            <dt>앞 팀</dt>
            <dd>{`${result.teamsAhead}팀`}</dd>
          </div>
          <div>
            <dt>인원</dt>
            <dd>{`${result.partySize}명`}</dd>
          </div>
        </dl>

        <div className="waiting-register__result-actions">
          <Link
            className="mi-button mi-button--primary"
            to={CONSUMER_PATHS.waitingCurrent}
          >
            현재 웨이팅 보기
          </Link>
          <Link className="mi-button mi-button--ghost" to={storeDetailPath}>
            매장 상세로 돌아가기
          </Link>
        </div>
      </div>
    </section>
  )
}
