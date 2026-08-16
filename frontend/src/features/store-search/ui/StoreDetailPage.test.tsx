import { screen } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { catalogHandlers } from '../test/handlers'
import { publicMenu, storeDetail } from '../test/fixtures'
import { StoreDetailPage } from './StoreDetailPage'

const STORE_ID = '01JBQ8Z4T7K2N9V6M3P5R8W1XA'

/** 상세 화면이 부르는 두 조회를 한 번에 등록한다. */
function respondWithDetail(
  detail: ReturnType<typeof storeDetail>,
  menus: ReturnType<typeof publicMenu>[] = [],
  onDetailRequest?: (search: URLSearchParams) => void,
) {
  server.use(
    ...catalogHandlers,
    http.get('/api/v1/stores/:storeId', ({ request }) => {
      onDetailRequest?.(new URL(request.url).searchParams)
      return successResponse(detail)
    }),
    http.get('/api/v1/stores/:storeId/menus', () =>
      successResponse({ items: menus }),
    ),
  )
}

function renderDetail(route = `/stores/${STORE_ID}`) {
  return renderWithProviders(<StoreDetailPage />, {
    route,
    path: '/stores/:storeId',
  })
}

describe('매장 상세 화면', () => {
  it('계약이 주는 매장 정보를 표시한다', async () => {
    respondWithDetail(storeDetail())

    renderDetail()

    expect(
      await screen.findByRole('heading', { level: 1, name: '파스타 마스터즈' }),
    ).toBeInTheDocument()
    // 카테고리는 시안의 알약, 지역과 주소는 그 아래 한 줄이다.
    // 둘 다 code가 아니라 표시명이어야 한다.
    expect(screen.getByText('이탈리안')).toBeInTheDocument()
    expect(
      screen.getByText('서울 · 서울특별시 성동구 연무장길 14'),
    ).toBeInTheDocument()
  })

  it('태그 표시명을 서버 catalog에서 받아 쓴다', async () => {
    respondWithDetail(storeDetail({ tags: ['DATE_COURSE'] }))

    renderDetail()

    await screen.findByRole('heading', { level: 1 })

    // code(DATE_COURSE)가 아니라 displayName(데이트)이 보여야 한다.
    expect(screen.getByText('데이트')).toBeInTheDocument()
    expect(screen.queryByText('DATE_COURSE')).not.toBeInTheDocument()
  })

  it('주간 영업시간과 예약 접수 시간대를 각각 표시한다', async () => {
    respondWithDetail(storeDetail())

    renderDetail()

    await screen.findByRole('heading', { level: 1 })

    const hours = screen.getByRole('table', {
      name: '요일별 영업시간과 브레이크타임',
    })
    const slots = screen.getByRole('table', { name: '요일별 예약 접수 시간대' })

    expect(hours).toHaveTextContent('11:30 – 22:00')
    expect(hours).toHaveTextContent('15:00 – 17:00')
    expect(slots).toHaveTextContent('11:30 – 14:30')
    // 영업일이 없는 요일은 휴무로 채운다. 응답 순서에 의존하지 않는다.
    expect(hours).toHaveTextContent('휴무')
  })

  it('활성화한 거래 방식만 다음 행동으로 제시한다', async () => {
    respondWithDetail(
      storeDetail({
        modes: {
          reservationEnabled: true,
          menuHoldEnabled: true,
          pickupEnabled: false,
        },
      }),
    )

    renderDetail()

    expect(
      await screen.findByRole('link', { name: '예약하고 메뉴 미리 선택' }),
    ).toHaveAttribute('href', `/stores/${STORE_ID}/reserve`)
    expect(
      screen.queryByRole('link', { name: '픽업 예약하기' }),
    ).not.toBeInTheDocument()
  })

  it('픽업만 활성화한 매장은 픽업 경로만 제시한다', async () => {
    respondWithDetail(
      storeDetail({
        modes: {
          reservationEnabled: false,
          menuHoldEnabled: false,
          pickupEnabled: true,
        },
      }),
    )

    renderDetail()

    expect(
      await screen.findByRole('link', { name: '픽업 예약하기' }),
    ).toHaveAttribute('href', `/stores/${STORE_ID}/pickup`)
    expect(screen.queryByRole('link', { name: '예약하기' })).not.toBeInTheDocument()
  })

  it('임시 휴무 매장은 거래 진입을 숨긴다', async () => {
    respondWithDetail(storeDetail({ operationStatus: 'TEMPORARILY_CLOSED' }))

    renderDetail()

    expect(
      await screen.findByText('임시 휴무 중인 매장입니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /예약/ })).not.toBeInTheDocument()
  })

  it('검색에서 넘어온 예약 조건을 상세 조회와 다음 화면에 그대로 전달한다', async () => {
    let received: URLSearchParams | null = null
    respondWithDetail(storeDetail(), [], (search) => {
      received = search
    })

    renderDetail(
      `/stores/${STORE_ID}?serviceDate=2026-09-01&startTime=19:00&partySize=2`,
    )

    await screen.findByRole('heading', { level: 1 })

    expect(received!.get('serviceDate')).toBe('2026-09-01')
    expect(received!.get('partySize')).toBe('2')
    expect(
      screen.getByRole('link', { name: '예약하고 메뉴 미리 선택' }),
    ).toHaveAttribute(
      'href',
      `/stores/${STORE_ID}/reserve?serviceDate=2026-09-01&startTime=19%3A00&partySize=2`,
    )
  })

  it('예약 조건이 불완전하면 가용성을 요청하지 않는다', async () => {
    let received: URLSearchParams | null = null
    respondWithDetail(storeDetail(), [], (search) => {
      received = search
    })

    renderDetail(`/stores/${STORE_ID}?serviceDate=2026-09-01`)

    await screen.findByRole('heading', { level: 1 })

    expect(received!.has('serviceDate')).toBe(false)
    expect(
      screen.getByText(
        '날짜·시간·인원을 모두 입력하면 예약 가능 여부를 확인할 수 있습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('가용성이 AVAILABLE이어도 확정이 아님을 함께 알린다', async () => {
    respondWithDetail(storeDetail({ reservationAvailability: 'AVAILABLE' }))

    renderDetail(
      `/stores/${STORE_ID}?serviceDate=2026-09-01&startTime=19:00&partySize=2`,
    )

    expect(
      await screen.findByText('지금 보이는 가용성은 확정이 아닙니다.'),
    ).toBeInTheDocument()
  })

  it('STORE_001은 찾을 수 없음으로 안내하고 재시도를 권하지 않는다', async () => {
    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores/:storeId', () =>
        errorResponse(404, 'STORE_001', '매장을 찾을 수 없습니다.'),
      ),
      http.get('/api/v1/stores/:storeId/menus', () =>
        successResponse({ items: [] }),
      ),
    )

    renderDetail()

    expect(
      await screen.findByText(
        '찾을 수 없는 매장입니다. 목록에서 다시 선택해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '다시 시도' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '매장 찾기로 돌아가기' }),
    ).toBeInTheDocument()
  })

  it('서비스 일시 불가는 복구 대기로 표시한다', async () => {
    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores/:storeId', () =>
        errorResponse(503, 'COMMON_012', '일시적으로 이용할 수 없습니다.'),
      ),
      http.get('/api/v1/stores/:storeId/menus', () =>
        successResponse({ items: [] }),
      ),
    )

    renderDetail()

    expect(
      await screen.findByText('서비스를 일시적으로 이용할 수 없습니다.'),
    ).toBeInTheDocument()
  })

  it('메뉴의 품절 상태를 색이 아닌 문구로 구분한다', async () => {
    respondWithDetail(
      storeDetail({ representativeMenus: [] }),
      [
        publicMenu({ menuId: '01JBQ8Z4T7K2N9V6M3P5R8W1M1', name: '판매 메뉴' }),
        publicMenu({
          menuId: '01JBQ8Z4T7K2N9V6M3P5R8W1M2',
          name: '품절 메뉴',
          saleStatus: 'SOLD_OUT',
        }),
      ],
    )

    renderDetail()

    expect(await screen.findByText('품절 메뉴')).toBeInTheDocument()
    expect(screen.getByText('품절')).toBeInTheDocument()
    expect(screen.getByText('판매 중')).toBeInTheDocument()
  })

  it('공개 메뉴가 없으면 오류가 아니라 빈 상태로 표시한다', async () => {
    respondWithDetail(storeDetail({ representativeMenus: [] }), [])

    renderDetail()

    expect(
      await screen.findByText('공개된 메뉴가 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('등록된 대표 메뉴가 없습니다.'),
    ).toBeInTheDocument()
  })

  it('1차 MVP에 없는 기능 버튼을 만들지 않는다', async () => {
    respondWithDetail(storeDetail())

    renderDetail()

    await screen.findByRole('heading', { level: 1 })

    for (const label of [
      '웨이팅 등록',
      '매장에 채팅 문의',
      '리뷰',
      '찜하기',
      '공유하기',
    ]) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })
})
