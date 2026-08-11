import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { catalogHandlers } from '../test/handlers'
import { storePage, storeSummary } from '../test/fixtures'
import { StoreSearchPage } from './StoreSearchPage'

/**
 * `@testing-library/user-event`는 설치돼 있지 않다. 공유 의존성은 #191이 단독
 * 소유하므로 여기서 추가하지 않고 RTL이 제공하는 fireEvent를 쓴다.
 */

/** 서버가 실제로 받은 query를 검사하기 위해 요청을 기록한다. */
let receivedSearch: URLSearchParams | null = null

function respondWithStores(...items: ReturnType<typeof storeSummary>[]) {
  server.use(
    ...catalogHandlers,
    http.get('/api/v1/stores', ({ request }) => {
      receivedSearch = new URL(request.url).searchParams
      return successResponse(storePage(items))
    }),
  )
}

function typeInto(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

beforeEach(() => {
  receivedSearch = null
})

describe('매장 찾기 결과 화면', () => {
  it('검색 결과를 목록으로 표시한다', async () => {
    respondWithStores(
      storeSummary({ name: '파스타 마스터즈' }),
      storeSummary({
        storeId: '01JBQ8Z4T7K2N9V6M3P5R8W1XB',
        name: '한식당 미리',
      }),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByRole('link', { name: '파스타 마스터즈' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '한식당 미리' })).toBeInTheDocument()
  })

  it('빈 결과는 오류가 아니라 안내로 표시한다', async () => {
    respondWithStores()

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByText('조건에 맞는 매장이 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('가용성 세 값을 서로 다른 의미로 표시한다', async () => {
    respondWithStores(
      storeSummary({
        storeId: '01JBQ8Z4T7K2N9V6M3P5R8W101',
        name: '가능한 매장',
        reservationAvailability: 'AVAILABLE',
      }),
      storeSummary({
        storeId: '01JBQ8Z4T7K2N9V6M3P5R8W102',
        name: '불가한 매장',
        reservationAvailability: 'UNAVAILABLE',
      }),
      storeSummary({
        storeId: '01JBQ8Z4T7K2N9V6M3P5R8W103',
        name: '조건 없는 매장',
        reservationAvailability: 'NOT_REQUESTED',
      }),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '가능한 매장' })

    expect(screen.getByText('예약 가능')).toBeInTheDocument()
    expect(screen.getByText('예약 불가')).toBeInTheDocument()
    expect(screen.getByText('예약 조건 미입력')).toBeInTheDocument()
  })

  it('URL의 완전한 예약 조건을 서버 query로 전달한다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, {
      route:
        '/stores?serviceDate=2026-09-01&startTime=19:00&partySize=2&availableOnly=true',
    })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    expect(receivedSearch?.get('serviceDate')).toBe('2026-09-01')
    expect(receivedSearch?.get('startTime')).toBe('19:00')
    expect(receivedSearch?.get('partySize')).toBe('2')
    expect(receivedSearch?.get('availableOnly')).toBe('true')
  })

  it('부분 예약 조건은 서버로 보내지 않고 사용자에게 안내한다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, {
      route: '/stores?serviceDate=2026-09-01',
    })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    expect(receivedSearch?.has('serviceDate')).toBe(false)
    expect(receivedSearch?.has('availableOnly')).toBe(false)
    expect(
      screen.getByText('예약 조건이 완전하지 않습니다.'),
    ).toBeInTheDocument()
  })

  it('예약 조건이 불완전하면 예약 가능만 보기를 켤 수 없다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    expect(
      screen.getByRole('checkbox', { name: '예약 가능한 매장만 보기' }),
    ).toBeDisabled()
  })

  it('예약 조건을 모두 채우면 예약 가능만 보기를 켤 수 있다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    typeInto('방문 날짜', '2026-09-01')
    typeInto('방문 시간', '19:00')
    typeInto('인원', '2')

    await waitFor(() =>
      expect(
        screen.getByRole('checkbox', { name: '예약 가능한 매장만 보기' }),
      ).toBeEnabled(),
    )
  })

  it('검색 조건을 제출하면 서버 query에 반영한다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    typeInto('검색어', '파스타')
    fireEvent.change(screen.getByLabelText('지역'), {
      target: { value: 'BUSAN' },
    })
    fireEvent.click(screen.getByRole('button', { name: '이 조건으로 검색' }))

    await waitFor(() => expect(receivedSearch?.get('keyword')).toBe('파스타'))
    expect(receivedSearch?.get('region')).toBe('BUSAN')
  })

  it('카테고리 표시명은 서버 catalog에서 받아 쓴다', async () => {
    respondWithStores(storeSummary({ storeCategoryCode: 'ITALIAN' }))

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    // code가 아니라 서버가 준 displayName이 보여야 한다.
    expect(screen.getByText('서울 · 이탈리안')).toBeInTheDocument()
    expect(
      within(screen.getByLabelText('카테고리')).getByRole('option', {
        name: '한식',
      }),
    ).toBeInTheDocument()
  })

  it('검증 오류는 입력을 고치도록 안내한다', async () => {
    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores', () =>
        errorResponse(400, 'COMMON_001', '검증에 실패했습니다.'),
      ),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByText(
        '검색 조건이 올바르지 않습니다. 날짜·시간·인원을 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('요청 제한은 잠시 후 재시도를 안내한다', async () => {
    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores', () =>
        errorResponse(429, 'COMMON_010', '요청이 많습니다.'),
      ),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByText(
        '검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('비회원 공개 경로에서 로그인을 강제하지 않는다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    expect(screen.queryByText('로그인하기')).not.toBeInTheDocument()
  })
})
