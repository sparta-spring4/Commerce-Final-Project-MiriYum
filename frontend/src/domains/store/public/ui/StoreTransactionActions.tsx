import { Link } from 'react-router'
import { Alert } from '../../../../shared/ui/Feedback'
import type { StoreDetail } from '../model/searchParams'

interface Props {
  store: StoreDetail
  /** 검색에서 넘어온 예약 조건. 예약 화면이 그대로 이어받는다. */
  search: string
}

/**
 * 매장이 활성화한 거래 방식만 다음 행동으로 제시한다.
 *
 * 꺼진 방식은 비활성 버튼이나 준비 중 안내로 남기지 않는다. 1차 MVP는 뒤 단계
 * 버튼·빈 메뉴·가짜 진행 상태를 만들지 않는다.
 *
 * 영업 상태가 `OPEN`이 아니면 거래 진입 자체를 숨긴다. 임시 휴무·폐점 매장의
 * 예약 시도는 서버가 거절하므로 화면에서 먼저 막는다.
 *
 * 웨이팅만 예외다. 매장 상세 계약에 웨이팅 on/off 필드가 없어 여기서는 켜짐
 * 여부를 알 수 없고, 서버 availability가 소유한 판정을 화면이 대신하지 않는다.
 */
export function StoreTransactionActions({ store, search }: Props) {
  if (store.operationStatus !== 'OPEN') {
    return (
      <Alert
        tone="info"
        title={
          store.operationStatus === 'TEMPORARILY_CLOSED'
            ? '임시 휴무 중인 매장입니다.'
            : '영업을 종료한 매장입니다.'
        }
      >
        <p>지금은 예약과 픽업을 받지 않습니다.</p>
      </Alert>
    )
  }

  const { reservationEnabled, menuHoldEnabled, pickupEnabled } = store.modes

  if (!reservationEnabled && !pickupEnabled) {
    return (
      <Alert tone="info" title="이 매장은 온라인 예약을 받지 않습니다.">
        <p>매장 정보와 메뉴만 확인할 수 있습니다.</p>
      </Alert>
    )
  }

  return (
    <div className="store-detail__actions">
      {reservationEnabled && (
        <Link
          className="mi-button mi-button--primary"
          to={{ pathname: `/stores/${store.storeId}/reserve`, search }}
        >
          {menuHoldEnabled ? '예약하고 메뉴 미리 선택' : '예약하기'}
        </Link>
      )}
      {pickupEnabled && (
        <Link
          className="mi-button mi-button--secondary"
          to={{ pathname: `/stores/${store.storeId}/pickup`, search }}
        >
          픽업 예약하기
        </Link>
      )}
      {/*
        웨이팅은 매장 상세 계약에 on/off 필드가 없다. 실제 접수 가능 여부는
        등록 화면이 서버 availability로 판정하므로 여기서 추측하지 않고
        진입만 제공한다. 예약 조건 search는 웨이팅과 뜻이 달라 넘기지 않는다.
      */}
      <Link
        className="mi-button mi-button--ghost"
        to={`/stores/${store.storeId}/waiting`}
      >
        웨이팅 등록
      </Link>
    </div>
  )
}
