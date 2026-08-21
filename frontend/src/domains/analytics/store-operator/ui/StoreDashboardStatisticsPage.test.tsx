import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  operatorStorePath,
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { StoreDashboardStatisticsPage } from './StoreDashboardStatisticsPage'

const STATISTICS_PATH = operatorStorePath('/dashboard-statistics')

const metadata = {
  definitionVersion: 'analytics-v1',
  aggregationVersion: 2,
  asOf: '2026-08-20T15:00:00+09:00',
  dataThrough: '2026-08-20T14:59:30+09:00',
  inputCheckpoint: 'checkpoint:2',
  completeness: 'COMPLETE',
  corrected: false,
  reasonCode: null,
} as const

describe('점주 운영 통계 화면', () => {
  it('동일 snapshot의 예약·수용량·취소·웨이팅·노쇼 지표를 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      http.get(STATISTICS_PATH, () => successResponse({
        snapshotId: '8db61ee7-2df6-4cbc-ab53-720ef1a79cc1',
        storeId: STORE_ID,
        businessDate: '2026-08-20',
        timeZoneId: 'Asia/Seoul',
        asOf: metadata.asOf,
        generatedAt: '2026-08-20T15:00:01+09:00',
        storeAuthorityVersion: 1,
        metrics: {
          todayReservationTeams: { ...metadata, value: 12 },
          reservationRate: { ...metadata, value: { numerator: 12, denominator: 20, ratio: 0.6 } },
          teamCapacityUsageRate: { ...metadata, value: { numerator: 9, denominator: 20, ratio: 0.45 } },
          cancellationRate: { ...metadata, value: { numerator: 2, denominator: 14, ratio: 0.142857 } },
          waiting: { ...metadata, value: { waitingTeams: 3, calledTeams: 1, waitingPeople: 8, calledPeople: 2, longestWaitSeconds: 1260 } },
          noShow: {
            ...metadata,
            completeness: 'PARTIAL',
            reasonCode: 'SOURCE_CONTRACT_MISSING',
            value: {
              reservationCandidate: { ...metadata, value: null, dataThrough: null, inputCheckpoint: null, completeness: 'UNAVAILABLE', reasonCode: 'SOURCE_CONTRACT_MISSING' },
              reservationConfirmed: { ...metadata, value: 1 },
              waitingConfirmed: { ...metadata, value: 2 },
            },
          },
        },
      })),
    )

    renderOperator(<StoreDashboardStatisticsPage />, {
      route: `/store-operator/stores/${STORE_ID}/dashboard-statistics`,
      path: '/store-operator/stores/:storeId/dashboard-statistics',
    })

    expect(await screen.findByText('오늘 예약 12팀')).toBeInTheDocument()
    expect(screen.getByText('예약률 60.0%')).toBeInTheDocument()
    expect(screen.getByText('팀 수용량 사용률 45.0%')).toBeInTheDocument()
    expect(screen.getByText('취소율 14.3%')).toBeInTheDocument()
    expect(screen.getByText('대기 3팀 · 호출 1팀')).toBeInTheDocument()
    expect(screen.getByText('최장 대기 21분')).toBeInTheDocument()
    expect(screen.getByText('예약 노쇼 확정 1팀 · 웨이팅 노쇼 확정 2팀')).toBeInTheDocument()
    expect(screen.getByText('예약 노쇼 후보 집계 불가')).toBeInTheDocument()
    expect(screen.getByText('일부 지표의 집계가 완전하지 않습니다.')).toBeInTheDocument()
  })
})
