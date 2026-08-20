import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedStoreHandler,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreInfoPage } from './StoreInfoPage'
import { StoreOperatorHomePage } from './StoreOperatorHomePage'

function renderHome() {
  return renderOperator(<StoreOperatorHomePage />, {
    route: STORE_OPERATOR_PATHS.home,
    path: STORE_OPERATOR_PATHS.home,
  })
}

describe('매장 운영 홈', () => {
  it('아는 매장이 없으면 등록으로 안내한다', async () => {
    server.use(authenticatedOperator())

    renderHome()

    expect(
      await screen.findByText('관리 중인 매장이 없습니다'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '매장 등록하기' })).toHaveAttribute(
      'href',
      STORE_OPERATOR_PATHS.storeCreate,
    )
  })

  it('매장 목록을 조회하는 계약이 없으므로 선택 드롭다운을 만들지 않는다', async () => {
    server.use(authenticatedOperator())

    renderHome()
    await screen.findByText('관리 중인 매장이 없습니다')

    expect(screen.queryByLabelText('매장 선택')).not.toBeInTheDocument()
  })

  it('아는 매장이 있으면 그 매장의 관리 화면으로 이어 간다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    // 매장 화면을 먼저 열면 셸이 그 매장을 현재 매장으로 채택한다.
    const { unmount } = renderOperator(<StoreInfoPage />, {
      route: fillPath(STORE_OPERATOR_PATHS.store, { storeId: STORE_ID }),
      path: STORE_OPERATOR_PATHS.store,
    })
    await screen.findByLabelText('매장명')
    unmount()

    renderHome()

    expect(await screen.findByText('카페 에비뉴')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '매장 추가 등록' })).toHaveAttribute(
      'href',
      STORE_OPERATOR_PATHS.storeCreate,
    )
    expect(screen.getByRole('link', { name: '예약 목록' })).toHaveAttribute(
      'href',
      fillPath(STORE_OPERATOR_PATHS.reservations, { storeId: STORE_ID }),
    )
  })

  it('계약이 없는 통계나 준비도 지표를 만들지 않는다', async () => {
    server.use(authenticatedOperator())

    renderHome()
    await screen.findByText('관리 중인 매장이 없습니다')

    expect(screen.queryByText(/오늘 예약/)).not.toBeInTheDocument()
    expect(screen.queryByText(/설정 완성도/)).not.toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('등록 안내에서 계약에 있는 기능만 설명한다', async () => {
    server.use(authenticatedOperator())

    renderHome()
    await screen.findByText('관리 중인 매장이 없습니다')

    // 웨이팅·결제·리뷰는 1차 서비스 계약에 없다.
    expect(screen.queryByText(/웨이팅/)).not.toBeInTheDocument()
    expect(screen.queryByText(/결제/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '매장 등록하기' }))
  })
})
