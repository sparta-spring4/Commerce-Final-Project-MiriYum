import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { STORE_ID, authenticatedOperator, operatorStorePath } from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { ReservationVisitOperationsPage } from './ReservationVisitOperationsPage'

const QR_TOKEN = `rqg_v1_${'A'.repeat(43)}`
const CHECK_IN_PATH = operatorStorePath('/reservation-check-ins')
const NO_SHOW_PATH = operatorStorePath('/reservations/901/no-shows')
const result = (status: 'FULFILLED' | 'NO_SHOW') => ({
  reservationId: '901', storeId: STORE_ID, storeName: '카페 에비뉴',
  serviceDate: '2026-09-01', timeStatus: 'RESOLVED',
  startAt: '2026-09-01T09:30:00Z', serviceEndAt: '2026-09-01T11:00:00Z',
  timeZoneId: 'Asia/Seoul', party: { adultCount: 2, childCount: 0, infantCount: 0, totalCount: 2 },
  status, menuSelections: [], cancelledBy: null, cancellationReason: null,
  createdAt: '2026-08-20T02:00:00Z',
})

function renderPage() {
  return renderOperator(<ReservationVisitOperationsPage />, {
    route: `/store-operator/stores/${STORE_ID}/reservation-visits`,
    path: '/store-operator/stores/:storeId/reservation-visits',
  })
}

describe('예약 체크인·노쇼 처리 화면', () => {
  it('스캔 QR로 체크인하고 처리 결과를 표시한다', async () => {
    let body: unknown
    server.use(authenticatedOperator(), http.post(CHECK_IN_PATH, async ({ request }) => {
      body = await request.json()
      return successResponse(result('FULFILLED'))
    }))
    renderPage()
    fireEvent.change(await screen.findByLabelText('QR 스캔 값'), { target: { value: QR_TOKEN } })
    fireEvent.click(screen.getByRole('button', { name: 'QR 체크인 완료' }))
    await waitFor(() => expect(body).toEqual({ qrToken: QR_TOKEN }))
    expect(await screen.findByText('예약 901 방문 완료')).toBeInTheDocument()
  })

  it('예약 ID와 후보 사유를 확인한 뒤 노쇼를 확정한다', async () => {
    let body: unknown
    server.use(authenticatedOperator(), http.post(NO_SHOW_PATH, async ({ request }) => {
      body = await request.json()
      return successResponse(result('NO_SHOW'))
    }))
    renderPage()
    fireEvent.change(screen.getByLabelText('예약 ID'), { target: { value: '901' } })
    fireEvent.change(screen.getByLabelText('노쇼 후보 사유'), { target: { value: 'USER_CAUSE_CANDIDATE' } })
    fireEvent.click(screen.getByRole('button', { name: '노쇼 확정 검토' }))
    expect(screen.getByText('예약 901을 노쇼로 확정합니다.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '노쇼로 확정' }))
    await waitFor(() => expect(body).toEqual({ reason: 'USER_CAUSE_CANDIDATE' }))
    expect(await screen.findByText('예약 901 노쇼 확정')).toBeInTheDocument()
  })
})
