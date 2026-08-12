import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { ROUTES, fillPath } from '../../../app/routes'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedStoreHandler,
  operatorStorePath,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { ClosuresPage } from './ClosuresPage'

const REGULAR_PATH = operatorStorePath('/regular-closures')
const TEMPORARY_PATH = operatorStorePath('/temporary-closures')

function regularClosureData(overrides: Record<string, unknown> = {}) {
  return {
    version: 3,
    status: 'DRAFT',
    timeZoneId: 'Asia/Seoul',
    effectiveAt: null,
    changeReason: null,
    weeklyDays: ['MONDAY'],
    dates: [],
    ...overrides,
  }
}

function temporaryClosureData(overrides: Record<string, unknown> = {}) {
  return {
    closureId: 42,
    storeId: 7,
    startAt: '2026-09-01T00:00:00+09:00',
    endAt: '2026-09-02T00:00:00+09:00',
    timeZoneId: 'Asia/Seoul',
    reason: 'MAINTENANCE',
    publicMessage: null,
    status: 'SCHEDULED',
    changeVersion: 1,
    ...overrides,
  }
}

function renderPage() {
  return renderOperator(<ClosuresPage />, {
    route: fillPath(ROUTES.storeOperatorClosures, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorClosures,
  })
}

describe('휴무·휴점 화면', () => {
  it('정기 휴무 저장이 전체를 대체한다는 사실을 알린다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText('저장하면 정기 휴무 전체가 대체됩니다.'),
    ).toBeInTheDocument()
  })

  it('요일과 날짜를 한 요청으로 보낸다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(REGULAR_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(regularClosureData())
      }),
    )

    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '월요일' }))
    fireEvent.change(screen.getByLabelText('추가할 날짜'), {
      target: { value: '2026-12-25' },
    })
    fireEvent.click(screen.getByRole('button', { name: '날짜 추가' }))
    fireEvent.click(
      screen.getByRole('button', { name: '정기 휴무 초안 저장' }),
    )

    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toEqual({ weeklyDays: ['MONDAY'], dates: ['2026-12-25'] })
  })

  it('같은 날짜를 두 번 추가하지 않는다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()
    const input = await screen.findByLabelText('추가할 날짜')

    fireEvent.change(input, { target: { value: '2026-12-25' } })
    fireEvent.click(screen.getByRole('button', { name: '날짜 추가' }))
    fireEvent.change(input, { target: { value: '2026-12-25' } })
    fireEvent.click(screen.getByRole('button', { name: '날짜 추가' }))

    expect(screen.getByText('이미 추가한 날짜입니다.')).toBeInTheDocument()
  })

  it('임시 휴점 시각에 매장 시간대 오프셋을 붙인다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(temporaryClosureData())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('휴점 시작'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toEqual({
      startAt: '2026-09-01T00:00:00+09:00',
      endAt: '2026-09-02T00:00:00+09:00',
      reason: 'MAINTENANCE',
    })
  })

  it('종료가 시작보다 앞서면 등록하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, () => {
        called = true
        return successResponse(temporaryClosureData())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('휴점 시작'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    expect(
      await screen.findByText('종료 시각이 시작 시각보다 뒤여야 합니다.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('빈 안내 문구는 본문에서 뺀다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(temporaryClosureData())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('휴점 시작'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.change(screen.getByLabelText('고객 안내 문구'), {
      target: { value: '   ' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    await waitFor(() => expect(body).not.toBeNull())
    // 빈 문자열을 보내 공개 안내를 덮어쓰지 않는다.
    expect(body).not.toHaveProperty('publicMessage')
  })

  it('등록 후에만 종료 시각 변경·취소를 연다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, () => successResponse(temporaryClosureData())),
    )

    renderPage()
    await screen.findByLabelText('휴점 시작')

    expect(
      screen.queryByRole('button', { name: '종료 시각 변경' }),
    ).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('휴점 시작'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    expect(
      await screen.findByRole('button', { name: '종료 시각 변경' }),
    ).toBeInTheDocument()
    expect(screen.getByText('예정')).toBeInTheDocument()
  })

  it('취소된 휴점에는 변경 명령을 남기지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, () => successResponse(temporaryClosureData())),
      http.post(`${TEMPORARY_PATH}/42/cancellation`, () =>
        successResponse(temporaryClosureData({ status: 'CANCELLED' })),
      ),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('휴점 시작'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    fireEvent.change(await screen.findByLabelText('휴점 취소 사유'), {
      target: { value: '정비 일정 변경' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 취소' }))

    expect(await screen.findByText('취소됨')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '종료 시각 변경' }),
    ).not.toBeInTheDocument()
  })

  it('취소 사유 없이 취소하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.post(TEMPORARY_PATH, () => successResponse(temporaryClosureData())),
      http.post(`${TEMPORARY_PATH}/42/cancellation`, () => {
        called = true
        return successResponse(temporaryClosureData({ status: 'CANCELLED' }))
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('휴점 시작'), {
      target: { value: '2026-09-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('휴점 종료'), {
      target: { value: '2026-09-02T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '임시 휴점 등록' }))

    fireEvent.click(
      await screen.findByRole('button', { name: '임시 휴점 취소' }),
    )

    expect(
      await screen.findByText('변경 사유를 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('정기 휴무 게시 충돌은 서버 코드로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(REGULAR_PATH, () =>
        errorResponse(409, 'STORE_006', '일정이 충돌합니다.'),
      ),
    )

    renderPage()
    fireEvent.click(
      await screen.findByRole('button', { name: '정기 휴무 초안 저장' }),
    )

    expect(
      await screen.findByText(
        '영업시간 또는 예약 접수 시간대가 충돌합니다. 구간을 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })
})
