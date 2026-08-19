import { useParams } from 'react-router'
import { ErrorState, Loading } from '../../../../shared/ui/Feedback'
import { useAdoptStoreFromRoute } from '../../../../app/shells/store-operator/CurrentStoreProvider'
import { useManagedStore } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import { ClosuresHeader, RegularClosureSection } from './RegularClosureSection'
import { TemporaryClosureSection } from './TemporaryClosureSection'

/**
 * 휴무·휴점.
 *
 * 정기 휴무는 초안·게시 모델이고 임시 휴점은 즉시 등록 후 종료 시각 변경·취소
 * 모델이다. 두 계약이 다르므로 화면에서도 섹션을 나눠 같은 버튼으로 묶지 않는다.
 */
export function ClosuresPage() {
  const { storeId = '' } = useParams<{ storeId: string }>()
  useAdoptStoreFromRoute(storeId)

  const storeQuery = useManagedStore(storeId)

  if (storeQuery.isPending) {
    return <Loading label="매장 정보를 불러오는 중입니다." />
  }
  if (storeQuery.isError) {
    return (
      <ErrorState
        error={storeQuery.error}
        message={storeErrorMessage(storeQuery.error)}
        onRetry={() => void storeQuery.refetch()}
      />
    )
  }

  const { timeZoneId } = storeQuery.data

  return (
    <>
      <ClosuresHeader />
      <div className="op-stack">
        <RegularClosureSection storeId={storeId} timeZoneId={timeZoneId} />
        <TemporaryClosureSection storeId={storeId} timeZoneId={timeZoneId} />
      </div>
    </>
  )
}
