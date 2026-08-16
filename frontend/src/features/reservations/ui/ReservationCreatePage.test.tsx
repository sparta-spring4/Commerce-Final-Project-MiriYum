import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { ROUTES } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { AccountErrorCode } from '../../auth/model/authErrors'
import { authenticatedConsumer } from '../../auth/test/handlers'
import { MenuHoldErrorCode, ReservationErrorCode } from '../model/errors'
import {
  MENU_HOLD_AVAILABILITY_PATH,
  MENU_ID,
  RESERVATIONS_PATH,
  STORE_ID,
  menuHoldAvailability,
  reservationDetail,
  storeWithMenuHold,
} from '../test/fixtures'
import { ReservationCreatePage } from './ReservationCreatePage'

function LocationProbe() {
  const { pathname, search } = useLocation()
  return <p data-testid="location">{`${pathname}${search}`}</p>
}

const SCHEDULE_QUERY = 'serviceDate=2026-09-01&startTime=19:00&adultCount=2'

function renderCreate(search = SCHEDULE_QUERY) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[`/stores/${STORE_ID}/reserve?${search}`]}>
          <Routes>
            <Route
              path={ROUTES.reservationCreate}
              element={<ReservationCreatePage />}
            />
            <Route
              path={ROUTES.reservationComplete}
              element={<LocationProbe />}
            />
            <Route
              path={ROUTES.reservationDetail}
              element={<LocationProbe />}
            />
            <Route path={ROUTES.myPage} element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

let createBody: Record<string, unknown> | null = null
let createKeys: string[] = []

function respondCreateWith(
  responder: () => ReturnType<typeof successResponse>,
) {
  server.use(
    authenticatedConsumer(),
    storeWithMenuHold(),
    http.get(MENU_HOLD_AVAILABILITY_PATH, () =>
      successResponse(menuHoldAvailability()),
    ),
    http.post(RESERVATIONS_PATH, async ({ request }) => {
      createBody = (await request.json()) as Record<string, unknown>
      const key = request.headers.get('Idempotency-Key')
      if (key !== null) {
        createKeys.push(key)
      }
      return responder()
    }),
  )
}


/**
 * 일정 단계를 통과한다.
 *
 * 매장의 메뉴 정책을 알기 전에는 다음 버튼이 열리지 않는다. 열릴 때까지
 * 기다렸다가 누른다.
 */
async function advanceToSubmit() {
  const next = await screen.findByRole('button', {
    name: /메뉴 선택으로|예약 확인으로/,
  })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
}

beforeEach(() => {
  createBody = null
  createKeys = []
})

describe('예약 생성 화면', () => {
  it('예약과 메뉴 홀드를 한 번의 쓰기로 만든다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate()

    await advanceToSubmit()

    // 메뉴 수량 선택
    fireEvent.click(
      await screen.findByRole('button', { name: '트러플 크림 파파델레 수량 늘리기' }),
    )
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    await waitFor(() => expect(createBody).not.toBeNull())

    expect(createBody?.menuSelections).toEqual([{ menuId: MENU_ID, quantity: 1 }])
    // 예약 생성 후 별도의 홀드 쓰기를 호출하지 않는다.
    expect(createKeys).toHaveLength(1)
  })

  it('계약에 없는 필드를 보내지 않는다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    await waitFor(() => expect(createBody).not.toBeNull())

    expect(Object.keys(createBody as object).sort()).toEqual([
      'menuSelections',
      'party',
      'serviceDate',
      'startTime',
      'storeId',
    ])
  })

  it('성공하면 예약 완료 화면으로 이동한다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/reservations/01JBQ8Z4T7K2N9V6M3P5R8W1R1/complete',
      ),
    )
  })

  it('CONFIRMED가 아닌 결과를 성공으로 표시하지 않는다', async () => {
    respondCreateWith(() =>
      successResponse(reservationDetail({ status: 'CANCELLED' })),
    )

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    expect(
      await screen.findByText(
        '예약 결과를 확인하지 못했습니다. 내 예약에서 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('location')).not.toBeInTheDocument()
  })

  it('인원이 0명이면 제출하지 않는다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate('serviceDate=2026-09-01&startTime=19:00&adultCount=0')
    await advanceToSubmit()

    expect(
      await screen.findByText('방문 인원을 한 명 이상 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(createBody).toBeNull()
  })

  it('RESERVATION_003 수용량 충돌은 조건 재선택을 제안한다', async () => {
    respondCreateWith(() =>
      errorResponse(
        409,
        ReservationErrorCode.CAPACITY_UNAVAILABLE,
        '수용량이 부족합니다.',
      ),
    )

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    expect(
      await screen.findByText(
        '방금 자리가 찼습니다. 다른 시간이나 인원으로 다시 확인해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '조건 다시 선택' }),
    ).toBeInTheDocument()
  })

  it('MENU_HOLD_002는 메뉴 재선택과 메뉴 없이 진행을 함께 제안한다', async () => {
    respondCreateWith(() =>
      errorResponse(
        409,
        MenuHoldErrorCode.INSUFFICIENT_QUANTITY,
        '메뉴 수량이 부족합니다.',
      ),
    )

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    expect(
      await screen.findByRole('button', { name: '메뉴 다시 선택' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '메뉴 없이 예약' }),
    ).toBeInTheDocument()
  })

  it('메뉴 없이 예약하면 menuSelections를 비워 다시 보낸다', async () => {
    let attempt = 0
    server.use(
      authenticatedConsumer(),
      storeWithMenuHold(),
      http.get(MENU_HOLD_AVAILABILITY_PATH, () =>
        successResponse(menuHoldAvailability()),
      ),
      http.post(RESERVATIONS_PATH, async ({ request }) => {
        attempt += 1
        createBody = (await request.json()) as Record<string, unknown>
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          createKeys.push(key)
        }
        if (attempt === 1) {
          return errorResponse(
            409,
            MenuHoldErrorCode.INSUFFICIENT_QUANTITY,
            '메뉴 수량이 부족합니다.',
          )
        }
        return successResponse(reservationDetail())
      }),
    )

    renderCreate()
    await advanceToSubmit()

    fireEvent.click(
      await screen.findByRole('button', { name: '트러플 크림 파파델레 수량 늘리기' }),
    )
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    fireEvent.click(
      await screen.findByRole('button', { name: '메뉴 없이 예약' }),
    )

    await waitFor(() => expect(attempt).toBe(2))
    expect(createBody?.menuSelections).toEqual([])
    // 메뉴를 뺀 것은 입력이 바뀐 새 시도다. 그때만 새 멱등 키를 쓴다.
    expect(new Set(createKeys).size).toBe(2)
  })

  /*
   * 결과 불명 회귀.
   *
   * 첫 요청이 서버에 커밋되고 응답만 유실됐을 수 있다. 그때 새 키로 다시 보내면
   * 같은 의도가 두 건의 예약이 된다. 입력이 그대로면 키도 그대로여야 한다.
   */
  it('결과를 알 수 없는 실패 뒤 같은 입력 재시도는 멱등 키를 유지한다', async () => {
    let attempt = 0
    server.use(
      authenticatedConsumer(),
      storeWithMenuHold(),
      http.get(MENU_HOLD_AVAILABILITY_PATH, () =>
        successResponse(menuHoldAvailability()),
      ),
      http.post(RESERVATIONS_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          createKeys.push(key)
        }
        // 5xx는 서버가 처리 도중 끊겼을 수 있어 반영 여부가 불명이다.
        if (attempt === 1) {
          return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
        }
        return successResponse(reservationDetail())
      }),
    )

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    // 재시도를 권하지 않고 결과 확인을 안내한다.
    expect(
      await screen.findByText(
        '예약 처리 여부를 확인하지 못했습니다. 다시 시도하지 말고 내 예약에서 상태를 확인해 주세요.',
      ),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    await waitFor(() => expect(attempt).toBe(2))
    // 같은 의도의 재시도이므로 서버는 같은 키를 받아 결과를 하나로 수렴시킨다.
    expect(new Set(createKeys).size).toBe(1)
  })

  it('ACCOUNT_006은 draft를 보존한 채 연락처 등록으로 안내한다', async () => {
    respondCreateWith(() =>
      errorResponse(
        409,
        AccountErrorCode.RESERVATION_CONTACT_REQUIRED,
        '연락처를 먼저 등록해야 합니다.',
      ),
    )

    renderCreate()
    await advanceToSubmit()
    await screen.findByRole('button', { name: '다음' })
    fireEvent.click(screen.getByRole('button', { name: '다음' }))
    fireEvent.click(screen.getByRole('button', { name: '예약하기' }))

    const link = await screen.findByRole('link', {
      name: '연락처 등록하러 가기',
    })
    const href = link.getAttribute('href') ?? ''

    expect(href.startsWith(`${ROUTES.myPage}?returnTo=`)).toBe(true)
    // 복귀 주소에 예약 조건이 들어 있어야 draft가 살아남는다.
    expect(decodeURIComponent(href)).toContain('serviceDate=2026-09-01')
    expect(decodeURIComponent(href)).toContain('adultCount=2')
  })

  it('가용성이 확정이 아님을 사용자에게 알린다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate()
    await advanceToSubmit()

    expect(
      await screen.findByText('지금 보이는 수량은 확정이 아닙니다.'),
    ).toBeInTheDocument()
  })

  /*
   * 메뉴 미리 선택을 받지 않는 매장은 메뉴 단계를 거치지 않는다. 거치게 하면
   * 예약 전용 매장에서도 메뉴 가용성을 조회한 뒤 빈 화면을 보여 주고 사용자가
   * 다시 "다음"을 눌러야 한다.
   */
  it('메뉴 홀드를 받지 않는 매장은 메뉴 단계를 건너뛴다', async () => {
    let availabilityCalls = 0

    server.use(
      authenticatedConsumer(),
      storeWithMenuHold(false),
      http.get(MENU_HOLD_AVAILABILITY_PATH, () => {
        availabilityCalls += 1
        return successResponse(menuHoldAvailability())
      }),
      http.post(RESERVATIONS_PATH, () =>
        successResponse(reservationDetail()),
      ),
    )

    renderCreate()

    // 단계 표시도 두 칸으로 줄어든다.
    const stepper = await screen.findByRole('list', { name: '예약 진행 단계' })
    await waitFor(() =>
      expect(within(stepper).queryByText('메뉴 선택')).not.toBeInTheDocument(),
    )

    fireEvent.click(
      await screen.findByRole('button', { name: '예약 확인으로' }),
    )

    expect(
      await screen.findByRole('button', { name: '예약하기' }),
    ).toBeInTheDocument()
    expect(availabilityCalls).toBe(0)
  })

  it('영유아 동반 검색에서 넘어오면 인원 구성을 다시 정하게 한다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate(
      'serviceDate=2026-09-01&startTime=19:00&partySize=3&includesInfants=true',
    )

    // 총 인원을 성인으로 옮기지 않았으므로 인원이 0명이다.
    fireEvent.click(
      await screen.findByRole('button', { name: '메뉴 선택으로' }),
    )

    expect(
      await screen.findByText('방문 인원을 한 명 이상 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(createBody).toBeNull()
  })

  it('영유아 동반 검색에서 영유아 0명이면 다음 단계로 넘기지 않는다', async () => {
    respondCreateWith(() => successResponse(reservationDetail()))

    renderCreate(
      'serviceDate=2026-09-01&startTime=19:00&adultCount=3&infantCount=0&includesInfants=true',
    )

    fireEvent.click(
      await screen.findByRole('button', { name: '메뉴 선택으로' }),
    )

    expect(
      await screen.findByText(/검색에서 영유아 동반을 선택했습니다/),
    ).toBeInTheDocument()
    expect(createBody).toBeNull()
  })

  it('서버 잔여를 넘겨 메뉴 수량을 고를 수 없다', async () => {
    server.use(
      authenticatedConsumer(),
      storeWithMenuHold(),
      http.get(MENU_HOLD_AVAILABILITY_PATH, () =>
        successResponse(menuHoldAvailability({ availableOnlineQuantity: 1 })),
      ),
    )

    renderCreate()
    await advanceToSubmit()

    const increase = await screen.findByRole('button', {
      name: '트러플 크림 파파델레 수량 늘리기',
    })
    fireEvent.click(increase)

    await waitFor(() => expect(increase).toBeDisabled())
  })
})
