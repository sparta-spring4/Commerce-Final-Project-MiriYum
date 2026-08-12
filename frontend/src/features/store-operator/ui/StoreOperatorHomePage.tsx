import { Link } from 'react-router'
import {
  ROUTES,
  fillPath,
  storeOperatorNavigation,
} from '../../../app/routes'
import { Badge } from '../../../shared/ui/Badge'
import { ErrorState, Loading } from '../../../shared/ui/Feedback'
import { useCurrentStore } from '../CurrentStoreProvider'
import { useManagedStore } from '../api/queries'
import { storeErrorMessage } from '../model/storeErrors'
import { OPERATION_STATUS_LABEL, REGION_LABEL } from '../model/types'
import { PageHeader, SectionCard, SummaryList } from './PageHeader'

/**
 * 매장 운영 홈.
 *
 * 운영자가 소유한 매장 목록 계약이 없다. 그래서 "매장 없음"을 서버에 물어
 * 확인하지 않고, 이 셸이 아는 매장 ID가 있는지로만 갈라진다. 아는 ID가 없으면
 * 등록으로 안내하고, 있으면 그 매장의 관리 화면을 연다.
 *
 * 통계·운영 준비도는 집계 계약이 없어 만들지 않는다.
 */
export function StoreOperatorHomePage() {
  const { storeId } = useCurrentStore()

  if (storeId === null) {
    return <StoreOnboardingGuide />
  }
  return <ManagedStoreOverview storeId={storeId} />
}

function StoreOnboardingGuide() {
  return (
    <div className="op-onboarding">
      <h1 className="op-onboarding__title">관리 중인 매장이 없습니다</h1>
      <p className="op-onboarding__text">
        첫 매장을 등록하면 영업시간·예약 접수 시간대·메뉴·휴무를 이 화면에서
        관리할 수 있습니다. 1차 서비스는 플랫폼 심사 없이 사업자등록번호 확인
        후 바로 등록됩니다.
      </p>

      <Link className="mi-button mi-button--primary" to={ROUTES.storeOperatorStoreCreate}>
        매장 등록하기
      </Link>

      <ul className="op-benefit-list">
        {/* 계약이 보장하는 동작만 적는다. 없는 기능을 혜택으로 약속하지 않는다. */}
        <li className="mi-card">
          <div className="mi-card__body">
            <p className="op-benefit__title">예약 자원 관리</p>
            <p className="op-benefit__text">
              날짜별 수용량과 예약 시간 정책을 게시 시점까지 직접 정합니다.
            </p>
          </div>
        </li>
        <li className="mi-card">
          <div className="mi-card__body">
            <p className="op-benefit__title">메뉴 버전 관리</p>
            <p className="op-benefit__text">
              초안·게시 예약·게시를 나누고 노출과 판매 상태를 따로 다룹니다.
            </p>
          </div>
        </li>
        <li className="mi-card">
          <div className="mi-card__body">
            <p className="op-benefit__title">휴무 반영</p>
            <p className="op-benefit__text">
              정기 휴무와 임시 휴점을 구분해 공개 정보에 반영합니다.
            </p>
          </div>
        </li>
      </ul>
    </div>
  )
}

function ManagedStoreOverview({ storeId }: { storeId: string }) {
  const query = useManagedStore(storeId)
  const navigation = storeOperatorNavigation(storeId)

  if (query.isPending) {
    return <Loading label="매장 정보를 불러오는 중입니다." />
  }
  if (query.isError) {
    return (
      <ErrorState
        error={query.error}
        message={storeErrorMessage(query.error)}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const store = query.data

  return (
    <>
      <PageHeader
        title="매장 운영 관리"
        description="등록된 매장의 운영 설정을 관리합니다."
        actions={
          <Link
            className="mi-button mi-button--ghost"
            to={fillPath(ROUTES.storeOperatorStore, { storeId })}
          >
            매장 정보 수정
          </Link>
        }
      />

      <div className="op-stack">
        <SectionCard title={store.name}>
          <p className="op-section__hint">
            <Badge
              tone={store.operationStatus === 'OPEN' ? 'positive' : 'neutral'}
            >
              {OPERATION_STATUS_LABEL[store.operationStatus]}
            </Badge>
          </p>
          <SummaryList
            items={[
              { term: '지역', value: REGION_LABEL[store.region] },
              { term: '주소', value: store.address },
              { term: '시간대', value: store.timeZoneId },
              {
                term: '거래 방식',
                value: describeModes(store.modes),
              },
            ]}
          />
        </SectionCard>

        <SectionCard
          title="관리 화면"
          hint="1차 서비스 계약이 있는 화면만 표시합니다."
        >
          <ul className="op-chip-set">
            {navigation.map((item) => (
              <li key={item.path}>
                <Link className="mi-button mi-button--ghost mi-button--sm" to={item.path}>
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </SectionCard>
      </div>
    </>
  )
}

function describeModes(modes: {
  reservationEnabled: boolean
  menuHoldEnabled: boolean
  pickupEnabled: boolean
}): string {
  const enabled = [
    modes.reservationEnabled ? '예약' : null,
    modes.menuHoldEnabled ? '메뉴 미리 선택' : null,
    modes.pickupEnabled ? '픽업' : null,
  ].filter((label): label is string => label !== null)

  return enabled.length === 0 ? '활성화된 거래 방식이 없습니다' : enabled.join(' · ')
}
