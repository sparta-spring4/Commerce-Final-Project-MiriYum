import { Link } from 'react-router'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { Icon } from '../../../shared/ui/Icon'
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

      {/*
        시안은 이동 카드를 둘(예약 내역·픽업 내역) 놓는다. 1차 MVP에 픽업
        내역 목록 화면이 없어 두 번째 카드는 갈 곳이 없으므로 만들지 않는다.
      */}
      <section className="mypage__links" aria-label="바로 가기">
        <Link className="mypage__link-card" to={ROUTES.myReservations}>
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
      </section>
    </div>
  )
}
