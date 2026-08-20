import { Link, useSearchParams } from 'react-router'
import { Badge, type BadgeTone } from '../../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import {
  useConsumerWaitingHistory,
  type ConsumerWaitingHistoryItem,
} from '../api/queries'

const LABEL: Record<ConsumerWaitingHistoryItem['status'], string> = {
  CHECKED_IN: '입장 완료', CANCELLED: '취소됨', NO_SHOW: '미도착 종료',
  CLOSED_BY_STORE: '매장 종료', RESERVATION_CONVERTED: '예약 전환 완료',
}
const TONE: Record<ConsumerWaitingHistoryItem['status'], BadgeTone> = {
  CHECKED_IN: 'positive', CANCELLED: 'neutral', NO_SHOW: 'negative',
  CLOSED_BY_STORE: 'attention', RESERVATION_CONVERTED: 'positive',
}

export function WaitingHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const parsed = Number.parseInt(searchParams.get('page') ?? '', 10)
  const page = Number.isInteger(parsed) && parsed > 0 ? parsed : 0
  const history = useConsumerWaitingHistory(page)

  return (
    <main className="mi-container waiting-history">
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">지난 웨이팅</h1>
        <p className="mi-page-head__lead">입장·취소·예약 전환으로 끝난 웨이팅을 확인하세요.</p>
      </header>
      {history.isPending && <Loading label="웨이팅 이력을 불러오는 중입니다." />}
      {history.isError && <ErrorState error={history.error} onRetry={() => void history.refetch()} />}
      {history.isSuccess && history.data.items.length === 0 && (
        <EmptyState title="지난 웨이팅이 없습니다." />
      )}
      {history.isSuccess && history.data.items.length > 0 && (
        <>
          <ul className="waiting-history__list">
            {history.data.items.map((item) => (
              <li key={item.waitingTeamId} className="mi-card waiting-history__item">
                <div className="mi-card__body">
                  <div className="waiting-history__head">
                    <Badge tone={TONE[item.status]}>{LABEL[item.status]}</Badge>
                    <time dateTime={item.endedAt}>{item.businessDate}</time>
                  </div>
                  <h2>매장 #{item.storeId}</h2>
                  <p>{item.partySize}명 · 접수 순번 {item.queueSequence}번</p>
                  {item.reservationId !== null && (
                    <Link className="mi-button mi-button--ghost mi-button--block" to={`/reservations/${item.reservationId}`}>
                      전환된 예약 보기
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ul>
          <Pagination
            number={history.data.page.number}
            totalPages={history.data.page.totalPages}
            totalElements={history.data.page.totalElements}
            hasNext={history.data.page.hasNext}
            onChange={(next) => {
              const params = new URLSearchParams()
              if (next > 0) params.set('page', String(next))
              setSearchParams(params)
            }}
          />
        </>
      )}
    </main>
  )
}
