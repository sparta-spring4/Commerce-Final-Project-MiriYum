import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedStoreHandler,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { ReservationTimeSlotsPage } from './ReservationTimeSlotsPage'

const SLOTS_PATH = operatorStorePath('/reservation-time-slots')
const PUBLIC_STORE_PATH = `/api/v1/stores/${STORE_ID}`

function slotsData(overrides: Record<string, unknown> = {}) {
  return {
    version: 2,
    status: 'DRAFT',
    timeZoneId: 'Asia/Seoul',
    effectiveAt: null,
    changeReason: null,
    conflictCheckStatus: 'NOT_EVALUATED',
    conflictCount: null,
    days: [],
    ...overrides,
  }
}

/** 게시된 공개 상세. 충돌 표시의 기준값이며 편집 폼을 채우지 않는다. */
function publishedStore() {
  return {
    storeId: STORE_ID,
    name: '카페 에비뉴',
    description: '',
    region: 'SEOUL',
    address: '서울 강남구 테헤란로 152',
    timeZoneId: 'Asia/Seoul',
    storeCategoryCode: 'CAFE_DESSERT',
    tags: [],
    operationStatus: 'OPEN',
    modes: {
      reservationEnabled: true,
      menuHoldEnabled: false,
      pickupEnabled: true,
    },
    operatingHours: [
      {
        dayOfWeek: 'MONDAY',
        businessHours: [{ startTime: '11:00', endTime: '22:00' }],
        breakTimes: [{ startTime: '15:00', endTime: '17:00' }],
      },
    ],
    reservationTimeSlots: [],
    representativeMenus: [],
    reservationAvailability: 'AVAILABLE',
  }
}

function renderPage() {
  return renderOperator(<ReservationTimeSlotsPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.reservationTimeSlots, {
      storeId: STORE_ID,
    }),
    path: STORE_OPERATOR_PATHS.reservationTimeSlots,
  })
}

async function openMondaySlot(start: string, end: string) {
  fireEvent.click(await screen.findByLabelText('월요일 예약 접수'))
  fireEvent.click(screen.getByRole('button', { name: '시간대 추가' }))

  const group = screen.getByRole('group', { name: '월요일 접수 시간대 1번' })
  fireEvent.change(within(group).getByLabelText('시작'), {
    target: { value: start },
  })
  fireEvent.change(within(group).getByLabelText('종료'), {
    target: { value: end },
  })
}

function saveDraft() {
  fireEvent.click(screen.getByRole('button', { name: '초안 저장' }))
}

describe('예약 접수 시간대 화면', () => {
  it('휴무 요일을 포함해 주간 전체를 보낸다', async () => {
    let body: { days: { dayOfWeek: string }[] } | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () => successResponse(publishedStore())),
      http.put(SLOTS_PATH, async ({ request }) => {
        body = (await request.json()) as { days: { dayOfWeek: string }[] }
        return successResponse(slotsData())
      }),
    )

    renderPage()
    await openMondaySlot('11:00', '14:00')
    saveDraft()

    await waitFor(() => expect(body).not.toBeNull())
    const sent = body as unknown as { days: { dayOfWeek: string }[] }
    expect(sent.days).toHaveLength(7)
  })

  it('수용량 필드를 이 요청에 섞지 않는다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () => successResponse(publishedStore())),
      http.put(SLOTS_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(slotsData())
      }),
    )

    renderPage()
    await openMondaySlot('11:00', '14:00')
    saveDraft()

    await waitFor(() => expect(body).not.toBeNull())
    // 수용량은 날짜 단위 계약이고 화면도 따로 있다.
    expect(Object.keys(body ?? {})).toEqual(['days'])
    expect(screen.getByText('수용량은 별도입니다')).toBeInTheDocument()
  })

  it('게시된 영업시간 밖이면 경고하되 저장은 막지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () => successResponse(publishedStore())),
      http.put(SLOTS_PATH, () => {
        called = true
        return successResponse(slotsData())
      }),
    )

    renderPage()
    await openMondaySlot('09:00', '10:00')

    expect(
      await screen.findByText('게시된 영업시간 밖입니다.'),
    ).toBeInTheDocument()

    saveDraft()
    // 최종 판정은 서버가 한다. 경고가 제출을 막지 않는다.
    await waitFor(() => expect(called).toBe(true))
  })

  it('게시된 휴게시간과 겹치면 경고한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () => successResponse(publishedStore())),
    )

    renderPage()
    await openMondaySlot('14:00', '16:00')

    expect(
      await screen.findByText('게시된 휴게시간과 겹칩니다.'),
    ).toBeInTheDocument()
  })

  it('게시된 영업시간을 못 읽어도 편집을 계속할 수 있다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () =>
        errorResponse(404, 'STORE_001', '매장을 찾을 수 없습니다.'),
      ),
      http.put(SLOTS_PATH, () => successResponse(slotsData())),
    )

    renderPage()

    expect(
      await screen.findByText('게시된 영업시간을 불러오지 못했습니다.'),
    ).toBeInTheDocument()
    expect(await screen.findByLabelText('월요일 예약 접수')).toBeEnabled()
  })

  it('접수 요일에 시간대가 없으면 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.get(PUBLIC_STORE_PATH, () => successResponse(publishedStore())),
      http.put(SLOTS_PATH, () => {
        called = true
        return successResponse(slotsData())
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByLabelText('화요일 예약 접수'))
    saveDraft()

    expect(
      await screen.findByText(
        '접수하는 요일로 두려면 시간대를 최소 한 개 입력해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })
})
