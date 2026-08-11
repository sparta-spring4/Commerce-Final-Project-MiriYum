import { Link } from 'react-router'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { ROUTES } from '../../../app/routes'
import { useConsumerAccount } from '../api/queries'
import { ProfileSection } from './ProfileSection'

/**
 * 마이페이지.
 *
 * 1차 MVP 계약에 있는 항목만 둔다. 결제·환불 내역, 알림, 웨이팅 이력은
 * 고도화 범위이므로 진입점도 만들지 않는다.
 */
export function MyPage() {
  const account = useConsumerAccount()

  return (
    <div className="mi-container mypage">
      <header className="mypage__header">
        <h1>마이페이지</h1>
        <p>내 정보와 예약 내역을 관리하세요.</p>
      </header>

      {account.isPending && <Loading label="내 정보를 불러오는 중입니다." />}

      {account.isError && (
        <ErrorState
          error={account.error}
          onRetry={() => void account.refetch()}
        />
      )}

      {account.isSuccess && <ProfileSection account={account.data} />}

      <section className="mi-card mypage__section" aria-label="내 예약 내역">
        <div className="mi-card__body">
          <h2>내 예약 내역</h2>
          <p>다가오는 일정과 지난 방문 기록을 확인할 수 있습니다.</p>
          <Link
            className="mi-button mi-button--primary"
            to={ROUTES.myReservations}
          >
            예약 내역 보기
          </Link>
        </div>
      </section>
    </div>
  )
}
