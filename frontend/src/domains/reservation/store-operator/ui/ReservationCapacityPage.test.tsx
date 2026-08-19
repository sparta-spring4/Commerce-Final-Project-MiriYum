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
  operatorStorePath,
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { ReservationCapacityPage } from './ReservationCapacityPage'

const SERVICE_DATE = '2026-09-01'
const CAPACITY_PATH = operatorStorePath(
  `/reservation-capacities/${SERVICE_DATE}`,
)

function capacitiesData() {
  return {
    serviceDate: SERVICE_DATE,
    policyVersion: 2,
    buckets: [
      {
        capacityBucketId: '31',
        startTime: '11:00',
        endTime: '14:00',
        maxPeople: 40,
        maxTeams: 10,
        occupiedPeople: 12,
        occupiedTeams: 3,
        availablePeople: 28,
        availableTeams: 7,
      },
    ],
  }
}

function renderPage() {
  return renderOperator(<ReservationCapacityPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.reservationCapacities, {
      storeId: STORE_ID,
    }),
    path: STORE_OPERATOR_PATHS.reservationCapacities,
  })
}

function fillFirstBucket() {
  const group = screen.getByRole('region', { name: '1번 구간' })
  fireEvent.change(within(group).getByLabelText('시작'), {
    target: { value: '11:00' },
  })
  fireEvent.change(within(group).getByLabelText('종료'), {
    target: { value: '14:00' },
  })
  fireEvent.change(within(group).getByLabelText('최대 인원'), {
    target: { value: '40' },
  })
  fireEvent.change(within(group).getByLabelText('최대 팀 수'), {
    target: { value: '10' },
  })
  fireEvent.change(within(group).getByLabelText('예약 최대 인원'), {
    target: { value: '8' },
  })
}

function save() {
  fireEvent.click(
    screen.getByRole('button', { name: '이 날짜 수용량 전체 교체' }),
  )
}

describe('예약 수용량 화면', () => {
  it('날짜 단위 전체 교체라는 사실을 먼저 알린다', async () => {
    server.use(authenticatedOperator())

    renderPage()

    expect(
      await screen.findByText('저장하면 그 날짜의 수용량이 전부 대체됩니다.'),
    ).toBeInTheDocument()
    // 버튼 문구도 부분 수정처럼 보이지 않아야 한다.
    expect(
      screen.getByRole('button', { name: '이 날짜 수용량 전체 교체' }),
    ).toBeInTheDocument()
  })

  it('날짜를 고르지 않으면 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, () => {
        called = true
        return successResponse(capacitiesData())
      }),
    )

    renderPage()
    await screen.findByLabelText('서비스 날짜')

    fillFirstBucket()
    save()

    expect(
      await screen.findByText('적용할 날짜를 선택해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('구간 목록 전체를 한 요청으로 보낸다', async () => {
    let body: { buckets: unknown[] } | null = null
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, async ({ request }) => {
        body = (await request.json()) as { buckets: unknown[] }
        return successResponse(capacitiesData())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 날짜'), {
      target: { value: SERVICE_DATE },
    })
    fillFirstBucket()
    save()

    await waitFor(() => expect(body).not.toBeNull())
    const sent = body as unknown as { buckets: unknown[] }
    expect(sent.buckets).toEqual([
      {
        startTime: '11:00',
        endTime: '14:00',
        maxPeople: 40,
        maxTeams: 10,
        minPartySize: 1,
        maxPartySize: 8,
        infantsAllowed: false,
      },
    ])
  })

  it('겹치는 구간은 서버를 부르기 전에 잡는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, () => {
        called = true
        return successResponse(capacitiesData())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 날짜'), {
      target: { value: SERVICE_DATE },
    })
    fillFirstBucket()

    fireEvent.click(screen.getByRole('button', { name: '구간 추가' }))
    const second = screen.getByRole('region', { name: '2번 구간' })
    fireEvent.change(within(second).getByLabelText('시작'), {
      target: { value: '13:00' },
    })
    fireEvent.change(within(second).getByLabelText('종료'), {
      target: { value: '16:00' },
    })
    fireEvent.change(within(second).getByLabelText('최대 인원'), {
      target: { value: '20' },
    })
    fireEvent.change(within(second).getByLabelText('최대 팀 수'), {
      target: { value: '5' },
    })
    fireEvent.change(within(second).getByLabelText('예약 최대 인원'), {
      target: { value: '6' },
    })
    save()

    expect(await screen.findAllByText('다른 구간과 겹칩니다.')).toHaveLength(2)
    expect(called).toBe(false)
  })

  it('저장 결과의 점유·잔여를 서버 값 그대로 보여 준다', async () => {
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, () => successResponse(capacitiesData())),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 날짜'), {
      target: { value: SERVICE_DATE },
    })
    fillFirstBucket()
    save()

    expect(await screen.findByText('11:00–14:00')).toBeInTheDocument()
    expect(screen.getByText('28')).toBeInTheDocument()
    // 조회 시점 값이라는 사실을 확정처럼 보여 주지 않는다.
    expect(
      screen.getByText('조회 시점 기준 값입니다. 새 예약이 들어오면 달라집니다.'),
    ).toBeInTheDocument()
  })

  it('기존 점유와 충돌하면 다른 사용자 상태를 노출하지 않고 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, () =>
        errorResponse(409, 'RESERVATION_008', '현재 점유와 충돌합니다.'),
      ),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 날짜'), {
      target: { value: SERVICE_DATE },
    })
    fillFirstBucket()
    save()

    expect(
      await screen.findByText(
        '이미 접수된 예약과 수용량 설정이 충돌합니다. 값을 낮추기 전에 예약 현황을 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('같은 내용을 다시 저장하면 같은 멱등 키를 유지한다', async () => {
    const keys: string[] = []
    server.use(
      authenticatedOperator(),
      http.put(CAPACITY_PATH, ({ request }) => {
        keys.push(request.headers.get('idempotency-key') ?? '')
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 날짜'), {
      target: { value: SERVICE_DATE },
    })
    fillFirstBucket()
    save()
    await waitFor(() => expect(keys).toHaveLength(1))

    save()
    await waitFor(() => expect(keys).toHaveLength(2))

    expect(keys[0]).toBe(keys[1])
  })
})
