import type { ReactNode } from 'react'
import { Outlet, useParams } from 'react-router'
import { useManagedStore } from '../../../domains/store/store-operator/api/queries'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { PageHeader, SectionCard } from './OperatorPage'

export function RequirePickupEnabled({ children }: { children?: ReactNode }) {
  const { storeId = '' } = useParams<{ storeId: string }>()
  const store = useManagedStore(storeId, storeId.length > 0)

  if (store.isPending) return <Loading label="매장 픽업 설정을 확인하는 중입니다." />
  if (store.isError) return <ErrorState error={store.error} message="매장 픽업 설정을 확인하지 못했습니다." onRetry={() => void store.refetch()} />
  if (!store.data.modes.pickupEnabled) {
    return <>
      <PageHeader title="픽업 운영" />
      <SectionCard title="이 매장은 픽업을 사용하지 않습니다.">
        <p>매장 정보에서 픽업 사용 여부를 변경한 뒤 이용해 주세요.</p>
      </SectionCard>
    </>
  }
  return children ?? <Outlet />
}
