import { useSearchParams } from 'react-router'
import { Badge, type BadgeTone } from '../../../../shared/ui/Badge'
import { Button } from '../../../../shared/ui/Button'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  useConsumerWaitingHistory,
  type ConsumerWaitingHistoryItem,
} from '../api/queries'

const LABEL: Record<ConsumerWaitingHistoryItem['status'], string> = {
  WAITING: '대기 중', CALLED: '호출됨', ARRIVED: '도착 확인',
  CHECKED_IN: '입장 완료', CANCELLED: '취소됨', NO_SHOW: '미도착 종료',
  CLOSED_BY_STORE: '매장 종료', RESERVATION_CONVERTING: '예약 전환 중',
  RESERVATION_CONVERTED: '예약 전환 완료',
}
const TONE: Record<ConsumerWaitingHistoryItem['status'], BadgeTone> = {
  WAITING: 'neutral', CALLED: 'attention', ARRIVED: 'attention',
  CHECKED_IN: 'positive', CANCELLED: 'neutral', NO_SHOW: 'negative',
  CLOSED_BY_STORE: 'attention', RESERVATION_CONVERTING: 'attention',
  RESERVATION_CONVERTED: 'positive',
}

export function WaitingHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const cursor = searchParams.get('cursor') ?? undefined
  const history = useConsumerWaitingHistory(cursor)

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
                    {item.terminatedAt !== null && (
                      <time dateTime={item.terminatedAt}>{formatTimestamp(item.terminatedAt)}</time>
                    )}
                  </div>
                  <h2>매장 #{item.storeId}</h2>
                  <p>등록 {formatTimestamp(item.registeredAt)}</p>
                </div>
              </li>
            ))}
          </ul>
          {history.data.nextCursor !== null && (
            <Button type="button" variant="secondary" onClick={() => {
              setSearchParams({ cursor: history.data.nextCursor! })
            }}>다음 이력</Button>
          )}
        </>
      )}
    </main>
  )
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Seoul',
  }).format(new Date(value))
}
