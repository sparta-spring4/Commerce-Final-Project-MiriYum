import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import { renderWithProviders } from '../../../../test/renderWithProviders'
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

/** 응답을 테스트가 원하는 순간까지 붙잡아 전환 구간을 관찰한다. */
function deferred() {
  let resolve = () => {}
  const promise = new Promise<void>((settle) => {
    resolve = settle
  })
  return { promise, resolve: () => resolve() }
}

/** 두 쪽짜리 결과의 한 쪽. 페이지 번호만 다른 응답을 만든다. */
function pageOf(name: string, number: number) {
  return {
    items: [storeSummary({ name })],
    page: { number, size: 20, totalElements: 40, totalPages: 2, hasNext: number === 0 },
  }
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
    // 계약이 지역을 하나만 받으므로 화면은 누름 상태를 가진 칩으로 고르게 한다.
    fireEvent.click(
      within(screen.getByRole('group', { name: '지역' })).getByRole('button', {
        name: '부산',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '이 조건으로 검색' }))

    await waitFor(() => expect(receivedSearch?.get('keyword')).toBe('파스타'))
    expect(receivedSearch?.get('region')).toBe('BUSAN')
  })

  /*
   * 조건이 바뀌는 동안 이전 결과를 그대로 두면 칩과 총 개수는 새 조건인데
   * 목록은 이전 조건의 결과인 구간이 생긴다. 그 사이 카드를 누르면 이전
   * 조건의 매장에 새 예약 조건을 붙여 예약 화면으로 넘어간다.
   */
  it('조건이 바뀌면 이전 조건의 결과를 새 결과처럼 보여 주지 않는다', async () => {
    const second = deferred()
    let call = 0

    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores', async () => {
        call += 1
        if (call === 1) {
          return successResponse(storePage([storeSummary({ name: '이전 매장' })]))
        }
        // 두 번째 조회를 붙잡아 전환 구간을 관찰한다.
        await second.promise
        return successResponse(storePage([storeSummary({ name: '새 매장' })]))
      }),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '이전 매장' })

    fireEvent.click(
      within(screen.getByRole('group', { name: '지역' })).getByRole('button', {
        name: '부산',
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: '이 조건으로 검색' }))

    // 새 조건의 응답을 기다리는 동안 이전 목록이 남아 있으면 안 된다.
    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: '이전 매장' }),
      ).not.toBeInTheDocument(),
    )

    second.resolve()
    expect(
      await screen.findByRole('link', { name: '새 매장' }),
    ).toBeInTheDocument()
  })

  it('페이지만 넘길 때는 이전 목록을 유지해 깜빡이지 않는다', async () => {
    const second = deferred()
    let call = 0

    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores', async () => {
        call += 1
        if (call === 1) {
          return successResponse(pageOf('첫 페이지 매장', 0))
        }
        await second.promise
        return successResponse(pageOf('둘째 페이지 매장', 1))
      }),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '첫 페이지 매장' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))

    // 같은 조건이므로 다음 페이지가 도착할 때까지 이전 목록을 보여 준다.
    await waitFor(() => expect(call).toBe(2))
    expect(
      screen.getByRole('link', { name: '첫 페이지 매장' }),
    ).toBeInTheDocument()

    second.resolve()
    expect(
      await screen.findByRole('link', { name: '둘째 페이지 매장' }),
    ).toBeInTheDocument()
  })

  /*
   * 예약 버튼은 서버가 받아 줄 매장에만 둔다.
   *
   * `reservationEnabled`만 보면 임시 휴무 매장에도 버튼이 떠서, 서버가 거절할
   * 쓰기 화면으로 사용자를 보낸다. 매장 상세의 `StoreTransactionActions`와
   * 같은 기준(영업 중 + 예약 활성)을 쓴다.
   */
  it('임시 휴무 매장에는 예약 버튼 대신 상세 보기를 둔다', async () => {
    respondWithStores(
      storeSummary({
        name: '임시 휴무 매장',
        operationStatus: 'TEMPORARILY_CLOSED',
      }),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '임시 휴무 매장' })

    expect(
      screen.queryByRole('link', { name: '예약하기' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '매장 자세히 보기' }),
    ).toBeInTheDocument()
  })

  it('영업 중이고 예약을 받는 매장에만 예약 버튼을 둔다', async () => {
    respondWithStores(storeSummary({ operationStatus: 'OPEN' }))

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByRole('link', { name: '예약하기' }),
    ).toBeInTheDocument()
  })

  it('같은 지역 칩을 다시 누르면 조건에서 뺀다', async () => {
    respondWithStores(storeSummary())

    renderWithProviders(<StoreSearchPage />, { route: '/stores?region=SEOUL' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    const seoul = within(
      screen.getByRole('group', { name: '지역' }),
    ).getByRole('button', { name: '서울' })
    expect(seoul).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(seoul)
    fireEvent.click(screen.getByRole('button', { name: '이 조건으로 검색' }))

    await waitFor(() => expect(receivedSearch?.get('region')).toBeNull())
  })

  it('카테고리 표시명은 서버 catalog에서 받아 쓴다', async () => {
    respondWithStores(storeSummary({ storeCategoryCode: 'ITALIAN' }))

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    await screen.findByRole('link', { name: '파스타 마스터즈' })

    // code가 아니라 서버가 준 displayName이 보여야 한다.
    expect(screen.getByText('서울 · 이탈리안')).toBeInTheDocument()
    expect(
      within(screen.getByRole('group', { name: '카테고리' })).getByRole(
        'button',
        { name: '한식' },
      ),
    ).toBeInTheDocument()
  })

  it('cursor 모드 응답이 오면 빈 결과로 넘기지 않고 계약 위반으로 다룬다', async () => {
    // 2차 MVP 통합 검색 응답 모양. 1차 MVP는 searchInput을 보내지 않으므로
    // 이 응답이 오면 안 된다. 조용히 "결과 없음"으로 보여 주면 사용자가
    // 검색이 정상 동작했다고 오해한다.
    server.use(
      ...catalogHandlers,
      http.get('/api/v1/stores', () =>
        successResponse({
          items: [],
          normalizedCondition: {},
          warnings: [],
          ruleVersion: 'rule-v1',
          vocabularyVersion: 'vocab-v1',
          rankingRuleVersion: null,
          nextCursor: null,
        }),
      ),
    )

    renderWithProviders(<StoreSearchPage />, { route: '/stores' })

    expect(
      await screen.findByText('서비스를 일시적으로 이용할 수 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('조건에 맞는 매장이 없습니다.'),
    ).not.toBeInTheDocument()
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
