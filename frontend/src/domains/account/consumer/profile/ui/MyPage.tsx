import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { Link } from 'react-router'
import { EmptyState, ErrorState, Loading } from '../../../../../shared/ui/Feedback'
import { Icon } from '../../../../../shared/ui/Icon'
import {
  useConsumerAccount,
} from '../api/queries'
import { type ConsumerPayment, useConsumerPayments } from '../../../../payment/consumer/api/queries'
import {
  type ConsumerWaitingSnapshot,
  useCurrentConsumerWaiting,
} from '../../../../waiting/consumer/api/queries'
import { ProfileSection } from './ProfileSection'

/**
 * 마이페이지.
 *
 * 현재 공개된 소비자 계약만 노출한다. 최근 결제와 현재 활성 웨이팅은 연결하고,
 * 지난 웨이팅 이력은 #467의 공개 조회 계약·구현 뒤 별도 화면으로 확장한다.
 */
export function MyPage() {
  const account = useConsumerAccount()
  const payments = useConsumerPayments()
  const currentWaiting = useCurrentConsumerWaiting()

  return (
    <div className="mi-container mi-container--narrow mypage">
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">마이페이지</h1>
        <p className="mi-page-head__lead">내 정보와 활동 내역을 관리하세요.</p>
      </header>

      {account.isPending && <Loading label="내 정보를 불러오는 중입니다." />}

      {account.isError && (
        <ErrorState
          error={account.error}
          onRetry={() => void account.refetch()}
        />
      )}

      {account.isSuccess && <ProfileSection account={account.data} />}

      <section className="mypage__activity" aria-labelledby="mypage-activity-title">
        <div className="mypage__section-head">
          <div>
            <h2 id="mypage-activity-title">내 활동</h2>
            <p>결제 내역과 현재 웨이팅 상태를 확인하세요.</p>
          </div>
        </div>

        <div className="mypage__activity-grid">
          <section className="mi-card mypage__activity-card" aria-labelledby="mypage-payment-title">
            <div className="mi-card__body">
              <h3 id="mypage-payment-title">최근 결제</h3>
              {payments.isPending && <Loading label="결제 내역을 불러오는 중입니다." />}
              {payments.isError && (
                <ErrorState
                  error={payments.error}
                  message="결제 내역을 불러오지 못했습니다."
                  onRetry={() => void payments.refetch()}
                />
              )}
              {payments.isSuccess && payments.data.items.length === 0 && (
                <EmptyState
                  title="결제 내역이 없습니다."
                  description="예약금을 결제하면 이곳에서 확인할 수 있습니다."
                />
              )}
              {payments.isSuccess && payments.data.items.length > 0 && (
                <ul className="mypage__activity-list">
                  {payments.data.items.map((payment) => (
                    <li key={payment.paymentId} className="mypage__activity-item">
                      <span>
                        <strong>{paymentStatusLabel(payment.status)}</strong>
                        <small>{formatDate(payment.createdAt)}</small>
                      </span>
                      <span className="mypage__activity-amount">
                        {payment.amountMinor.toLocaleString('ko-KR')}원
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

          <section className="mi-card mypage__activity-card" aria-labelledby="mypage-waiting-title">
            <div className="mi-card__body">
              <h3 id="mypage-waiting-title">현재 웨이팅</h3>
              {currentWaiting.isPending && <Loading label="현재 웨이팅을 불러오는 중입니다." />}
              {currentWaiting.isError && (
                <ErrorState
                  error={currentWaiting.error}
                  message="현재 웨이팅을 불러오지 못했습니다."
                  onRetry={() => void currentWaiting.refetch()}
                />
              )}
              {currentWaiting.isSuccess && currentWaiting.data === null && (
                <EmptyState
                  title="현재 웨이팅이 없습니다."
                  description="웨이팅을 등록하면 순번과 앞 팀 수를 이곳에서 확인할 수 있습니다."
                />
              )}
              {currentWaiting.isSuccess && currentWaiting.data !== null && (
                <dl className="mypage__waiting-summary">
                  <div>
                    <dt>상태</dt>
                    <dd>{waitingStatusLabel(currentWaiting.data.status)}</dd>
                  </div>
                  <div>
                    <dt>내 순번</dt>
                    <dd>{currentWaiting.data.queueSequence}번</dd>
                  </div>
                  <div>
                    <dt>앞 팀</dt>
                    <dd>{currentWaiting.data.teamsAhead}팀</dd>
                  </div>
                  <div>
                    <dt>인원</dt>
                    <dd>{currentWaiting.data.partySize}명</dd>
                  </div>
                </dl>
              )}
            </div>
          </section>
        </div>
      </section>

      <section className="mypage__links" aria-label="바로 가기">
        <Link className="mypage__link-card" to={CONSUMER_PATHS.myReservations}>
          <span className="mypage__link-art" aria-hidden="true">
            <Icon name="menu" />
          </span>
          <span className="mypage__link-body">
            <span className="mypage__link-title">내 예약 내역</span>
            <span className="mypage__link-text">
              다가오는 다이닝 일정과 지난 방문 기록을 확인하세요.
            </span>
          </span>
          <span className="mypage__link-go">
            자세히 보기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </span>
        </Link>
        <Link className="mypage__link-card" to={CONSUMER_PATHS.myPickups}>
          <span className="mypage__link-art" aria-hidden="true">
            <Icon name="bag" />
          </span>
          <span className="mypage__link-body">
            <span className="mypage__link-title">내 픽업 내역</span>
            <span className="mypage__link-text">
              예정된 픽업과 지난 수령 기록을 확인하세요.
            </span>
          </span>
          <span className="mypage__link-go">
            자세히 보기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </span>
        </Link>
        <Link className="mypage__link-card" to={CONSUMER_PATHS.notificationHistory}>
          <span className="mypage__link-art" aria-hidden="true">
            <Icon name="alert" />
          </span>
          <span className="mypage__link-body">
            <span className="mypage__link-title">알림 이력</span>
            <span className="mypage__link-text">
              예약과 매장 이용 관련 알림을 다시 확인하세요.
            </span>
          </span>
          <span className="mypage__link-go">
            자세히 보기
            <Icon name="arrowRight" className="mi-icon--sm" />
          </span>
        </Link>
        <Link className="mypage__link-card" to={CONSUMER_PATHS.waitingHistory}>
          <span className="mypage__link-art" aria-hidden="true"><Icon name="clock" /></span>
          <span className="mypage__link-body">
            <span className="mypage__link-title">지난 웨이팅</span>
            <span className="mypage__link-text">종료된 대기와 예약 전환 기록을 확인하세요.</span>
          </span>
          <span className="mypage__link-go">자세히 보기<Icon name="arrowRight" className="mi-icon--sm" /></span>
        </Link>
      </section>
    </div>
  )
}

function formatDate(isoDateTime: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
  }).format(new Date(isoDateTime))
}

function paymentStatusLabel(status: ConsumerPayment['status']): string {
  return {
    READY: '결제 준비',
    CONFIRMING: '결제 확인 중',
    PAID: '결제 완료',
    PARTIALLY_REFUNDED: '부분 환불',
    REFUNDED: '환불 완료',
    RECONCILIATION_REQUIRED: '결제 상태 확인 필요',
  }[status]
}

function waitingStatusLabel(status: ConsumerWaitingSnapshot['status']): string {
  return {
    WAITING: '대기 중',
    CALLED: '입장 호출',
    ARRIVED: '도착 확인',
    CHECKED_IN: '입장 완료',
    CANCELLED: '취소됨',
    NO_SHOW: '미도착 종료',
    CLOSED_BY_STORE: '매장 종료',
    RESERVATION_CONVERTING: '예약 전환 중',
    RESERVATION_CONVERTED: '예약 전환 완료',
  }[status] ?? '상태 확인 중'
}
