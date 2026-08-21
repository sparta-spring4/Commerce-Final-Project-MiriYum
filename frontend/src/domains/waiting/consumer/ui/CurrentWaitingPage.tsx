import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { CONSUMER_PATHS } from '../../../../app/routes/paths/consumerPaths'
import { Badge } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { Alert } from '../../../../shared/ui/Feedback'
import { Icon } from '../../../../shared/ui/Icon'
import type { ConsumerWaitingSnapshot } from '../api/queries'
import type { WaitingEventConnectionState } from '../waitingEventStream'
import {
  WAITING_CANCEL_GUIDANCE,
  WAITING_STATUS_GUIDE,
  WAITING_STATUS_LABEL,
  WAITING_STATUS_TONE,
  canCancelWaiting,
  formatBusinessDate,
  isWaitingClosed,
  type WaitingCancelErrorView,
} from '../model/currentWaitingView'
import { formatDateTime } from '../model/partyViewState'
import { WaitingPartyConfirmDialog } from './WaitingPartyConfirmDialog'
import './waitingParty.css'
import './currentWaiting.css'

/** 취소 흐름 단계. 확인 단계를 건너뛰고 바로 명령을 보내지 않는다. */
export type WaitingCancelPhase = 'idle' | 'confirming' | 'submitting'

interface Props {
  snapshot: ConsumerWaitingSnapshot
  /** 이 snapshot을 서버에서 받은 시각. 화면이 스스로 만들지 않는다. */
  fetchedAt: number
  refreshing: boolean
  realtimeState?: WaitingEventConnectionState | null
  cancelPhase: WaitingCancelPhase
  cancelError?: WaitingCancelErrorView | null
  /** 일행 관리 패널. 컨테이너가 연결한 노드를 그대로 끼운다. */
  partyPanel?: ReactNode
  onRefresh: () => void
  onRequestCancel: () => void
  onConfirmCancel: () => void
  onDismissCancel: () => void
}

/**
 * 현재 웨이팅 상세.
 *
 * 순번·앞 팀 수·상태와 취소를 소유하고, 일행 구성은 `partyPanel`로 받아 끼운다.
 * 두 관심사를 한 컴포넌트에 합치면 일행 명령의 진행 상태가 상세 표시와 뒤섞인다.
 *
 * 예상 대기시간을 만들지 않는다. 계약에 없는 값이고 확정 입장 시각처럼 읽힌다.
 * 종료된 웨이팅에서는 순번과 앞 팀 수를 감춘다. 등록 당시 숫자를 그대로 두면
 * 아직 자리가 남아 있는 것처럼 보인다.
 */
export function CurrentWaitingPage({
  snapshot,
  fetchedAt,
  refreshing,
  realtimeState = null,
  cancelPhase,
  cancelError = null,
  partyPanel,
  onRefresh,
  onRequestCancel,
  onConfirmCancel,
  onDismissCancel,
}: Props) {
  const closed = isWaitingClosed(snapshot.status)
  const cancellable = canCancelWaiting(snapshot)
  const businessDate = formatBusinessDate(snapshot.businessDate)
  const guidance = cancelError === null ? null : WAITING_CANCEL_GUIDANCE[cancelError.code]

  return (
    <div className="mi-container mi-container--narrow waiting-current">
      <header className="mi-page-head waiting-current__header">
        <p className="waiting-current__back">
          <Link to={CONSUMER_PATHS.myPage}>
            <Icon name="arrowLeft" className="mi-icon--sm" />
            마이페이지로 돌아가기
          </Link>
        </p>
        <h1 className="mi-page-head__title">현재 웨이팅</h1>
        <p className="waiting-current__status">
          <Badge tone={WAITING_STATUS_TONE[snapshot.status]}>
            {WAITING_STATUS_LABEL[snapshot.status]}
          </Badge>
        </p>
        <p className="mi-page-head__lead">{WAITING_STATUS_GUIDE[snapshot.status]}</p>
      </header>

      <section
        className="mi-card mi-card--roomy waiting-current__queue"
        aria-labelledby="waiting-current-queue-title"
      >
        <div className="mi-card__body mi-card__body--roomy">
          <h2 id="waiting-current-queue-title">대기 현황</h2>

          {closed ? (
            <p className="waiting-current__closed">
              종료된 웨이팅입니다. 등록 당시 순번은 더 이상 유효하지 않습니다.
            </p>
          ) : (
            <dl className="waiting-current__metrics">
              <div>
                <dt>내 순번</dt>
                <dd className="waiting-current__metric-value">{`${snapshot.queueSequence}번`}</dd>
              </div>
              <div>
                <dt>앞 팀</dt>
                <dd className="waiting-current__metric-value">{`${snapshot.teamsAhead}팀`}</dd>
              </div>
              <div>
                <dt>방문 인원</dt>
                <dd className="waiting-current__metric-value">{`${snapshot.partySize}명`}</dd>
              </div>
            </dl>
          )}

          <dl className="waiting-current__facts">
            <div>
              <dt>영업일</dt>
              <dd>{businessDate ?? snapshot.businessDate}</dd>
            </div>
            {closed && (
              <div>
                <dt>방문 인원</dt>
                <dd>{`${snapshot.partySize}명`}</dd>
              </div>
            )}
            <TimestampFact label="등록 시각" value={snapshot.createdAt} />
            <TimestampFact label="호출 시각" value={snapshot.calledAt} />
            <TimestampFact label="도착 제한 시각" value={snapshot.arrivalDeadline} />
            <TimestampFact label="도착 확인 시각" value={snapshot.arrivedAt} />
            <TimestampFact label="취소 시각" value={snapshot.cancelledAt} />
          </dl>

          <div className="waiting-current__refresh">
            <div className="waiting-current__refresh-text" role="status" aria-live="polite">
              <p>{`마지막 갱신 ${formatDateTime(new Date(fetchedAt).toISOString()) ?? '확인 필요'}`}</p>
              {realtimeState === 'reconnecting' && (
                <p>실시간 연결을 다시 시도하고 있습니다.</p>
              )}
              {realtimeState === 'unavailable' && (
                <p>실시간 갱신을 사용할 수 없습니다. 상태를 직접 확인해 주세요.</p>
              )}
            </div>
            <Button
              variant="ghost"
              size="sm"
              loading={refreshing}
              onClick={onRefresh}
            >
              상태 다시 확인
            </Button>
          </div>

          <p className="waiting-current__store-link">
            <Link to={`/stores/${snapshot.storeId}`}>
              매장 정보 보기
              <Icon name="arrowRight" className="mi-icon--sm" />
            </Link>
          </p>
        </div>
      </section>

      {partyPanel}

      {cancellable && (
        <section className="waiting-current__cancel" aria-labelledby="waiting-current-cancel-title">
          <h2 id="waiting-current-cancel-title" className="waiting-current__cancel-title">
            웨이팅 취소
          </h2>
          <p className="waiting-current__cancel-text">
            취소하면 순번이 사라지고 일행의 참여도 함께 종료됩니다. 다시 이용하려면
            매장에서 새로 등록해야 합니다.
          </p>

          {guidance !== null && cancelError !== null && (
            <Alert
              tone={cancelError.code === 'OUTCOME_UNKNOWN' ? 'warning' : 'error'}
              title={cancelError.message ?? guidance.title}
              actions={
                /* 상태가 어긋난 실패는 같은 요청을 다시 보내도 같은 실패다.
                   그때는 재시도 대신 최신 상태 재조회로 보낸다. */
                guidance.retryable ? (
                  <Button variant="ghost" size="sm" onClick={onConfirmCancel}>
                    다시 시도
                  </Button>
                ) : (
                  <Button variant="ghost" size="sm" onClick={onRefresh}>
                    최신 상태 확인
                  </Button>
                )
              }
            >
              <p>{guidance.description}</p>
            </Alert>
          )}

          <Button
            variant="danger"
            disabled={cancelPhase !== 'idle'}
            onClick={onRequestCancel}
          >
            웨이팅 취소하기
          </Button>
        </section>
      )}

      {cancelPhase !== 'idle' && (
        <WaitingPartyConfirmDialog
          title="웨이팅을 취소할까요?"
          description={
            <>
              <p>{`${snapshot.queueSequence}번 순번이 사라집니다.`}</p>
              <p>일행이 있으면 일행의 참여도 함께 종료됩니다.</p>
            </>
          }
          confirmLabel="취소 확정"
          submitting={cancelPhase === 'submitting'}
          onConfirm={onConfirmCancel}
          onCancel={onDismissCancel}
        />
      )}
    </div>
  )
}

/** 값이 없는 시각은 줄을 만들지 않는다. 빈 칸이 늘면 읽을 값이 묻힌다. */
function TimestampFact({
  label,
  value,
}: {
  label: string
  value: string | null
}) {
  if (value === null) {
    return null
  }
  const formatted = formatDateTime(value)
  if (formatted === null) {
    return null
  }
  return (
    <div>
      <dt>{label}</dt>
      <dd>{formatted}</dd>
    </div>
  )
}
