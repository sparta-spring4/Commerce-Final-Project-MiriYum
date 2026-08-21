import { Link, useSearchParams } from 'react-router'
import { Badge } from '../../../../shared/ui/Badge'
import { EmptyState, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { Pagination } from '../../../../shared/ui/Pagination'
import { useMyPickups } from '../api/queries'
import { PICKUP_STATUS_LABEL, PICKUP_STATUS_TONE } from '../model/pickup'

export function MyPickupsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const value = Number.parseInt(searchParams.get('page') ?? '', 10)
  const page = Number.isInteger(value) && value > 0 ? value : 0
  const pickups = useMyPickups(page)

  return (
    <main className="mi-container pickup-history">
      <header className="mi-page-head">
        <h1 className="mi-page-head__title">내 픽업 내역</h1>
        <p className="mi-page-head__lead">예정된 픽업과 지난 수령 기록을 확인하세요.</p>
      </header>

      {pickups.isPending && <Loading label="픽업 내역을 불러오는 중입니다." />}
      {pickups.isError && (
        <ErrorState error={pickups.error} onRetry={() => void pickups.refetch()} />
      )}
      {pickups.isSuccess && pickups.data.items.length === 0 && (
        <EmptyState
          title="픽업 내역이 없습니다."
          description="매장 메뉴를 미리 주문하면 이곳에서 확인할 수 있습니다."
        />
      )}
      {pickups.isSuccess && pickups.data.items.length > 0 && (
        <>
          <ul className="pickup-history__list">
            {pickups.data.items.map((pickup) => {
              const first = pickup.items[0]
              const totalQuantity = pickup.items.reduce((sum, item) => sum + item.quantity, 0)
              return (
                <li key={pickup.pickupReservationId} className="mi-card pickup-history__item">
                  <div className="mi-card__body">
                    <div className="pickup-history__head">
                      <div>
                        <Badge tone={PICKUP_STATUS_TONE[pickup.status]}>
                          {PICKUP_STATUS_LABEL[pickup.status]}
                        </Badge>
                        <h2>{pickup.storeName}</h2>
                      </div>
                      <time dateTime={`${pickup.pickupDate}T${pickup.pickupTime}`}>
                        {pickup.pickupDate} {pickup.pickupTime}
                      </time>
                    </div>
                    <p className="pickup-history__menus">
                      {first === undefined
                        ? '메뉴 정보 없음'
                        : `${first.menuName} 외 ${pickup.items.length - 1}개 · 총 ${totalQuantity}개`}
                    </p>
                    <Link
                      className="mi-button mi-button--primary mi-button--block"
                      to={`/pickup-reservations/${pickup.pickupReservationId}`}
                    >
                      픽업 상세 보기
                    </Link>
                  </div>
                </li>
              )
            })}
          </ul>
          <Pagination
            number={pickups.data.page.number}
            totalPages={pickups.data.page.totalPages}
            totalElements={pickups.data.page.totalElements}
            hasNext={pickups.data.page.hasNext}
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
