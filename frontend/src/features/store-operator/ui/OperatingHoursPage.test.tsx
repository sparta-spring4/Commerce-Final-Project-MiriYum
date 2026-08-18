import { fireEvent, screen, waitFor, within } from '@testing-library/react'
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
import { OperatingHoursPage } from './OperatingHoursPage'

const DRAFT_PATH = operatorStorePath('/operating-hours')

function publicationPath(version: number) {
  return `${DRAFT_PATH}/${version}/publications`
}

function cancellationPath(version: number) {
  return `${DRAFT_PATH}/${version}/publication-cancellations`
}

function operatingHoursData(overrides: Record<string, unknown> = {}) {
  return {
    version: 4,
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

function renderPage() {
  return renderOperator(<OperatingHoursPage />, {
    route: fillPath(ROUTES.storeOperatorOperatingHours, { storeId: STORE_ID }),
    path: ROUTES.storeOperatorOperatingHours,
  })
}

async function openMondayWith(start: string, end: string) {
  fireEvent.click(await screen.findByLabelText('월요일 영업'))
  fireEvent.click(screen.getByRole('button', { name: '영업 구간 추가' }))

  const group = screen.getByRole('group', { name: '월요일 영업 구간 1번' })
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

describe('영업시간 화면', () => {
  it('저장이 주간 전체를 대체한다는 사실을 먼저 알린다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText('저장하면 주간 전체가 대체됩니다.'),
    ).toBeInTheDocument()
  })

  it('휴무 요일을 포함해 월~일 일곱 개를 모두 보낸다', async () => {
    let body: { days: { dayOfWeek: string }[] } | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, async ({ request }) => {
        body = (await request.json()) as { days: { dayOfWeek: string }[] }
        return successResponse(operatingHoursData())
      }),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    await waitFor(() => expect(body).not.toBeNull())
    const sent = body as unknown as { days: { dayOfWeek: string }[] }
    // 일부 요일만 보내면 서버가 나머지를 유지해 주지 않는다.
    expect(sent.days).toHaveLength(7)
    expect(sent.days.map((day) => day.dayOfWeek)).toEqual([
      'MONDAY',
      'TUESDAY',
      'WEDNESDAY',
      'THURSDAY',
      'FRIDAY',
      'SATURDAY',
      'SUNDAY',
    ])
  })

  it('휴게시간이 영업 구간 밖이면 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => {
        called = true
        return successResponse(operatingHoursData())
      }),
    )

    renderPage()
    await openMondayWith('09:00', '12:00')

    fireEvent.click(screen.getByRole('button', { name: '휴게시간 추가' }))
    const breakGroup = screen.getByRole('group', {
      name: '월요일 휴게시간 1번',
    })
    fireEvent.change(within(breakGroup).getByLabelText('시작'), {
      target: { value: '13:00' },
    })
    fireEvent.change(within(breakGroup).getByLabelText('종료'), {
      target: { value: '14:00' },
    })
    saveDraft()

    expect(
      await screen.findByText('휴게시간은 영업 구간 안에 있어야 합니다.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('초안을 저장하기 전에는 게시 단계를 열지 않는다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText('먼저 초안을 저장해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('변경 사유')).not.toBeInTheDocument()
  })

  it('초안 저장은 게시가 아니며 상태를 초안으로 표시한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => successResponse(operatingHoursData())),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    expect(await screen.findByText('초안')).toBeInTheDocument()
    expect(screen.queryByText('게시됨')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '버전 4 게시' }),
    ).toBeInTheDocument()
  })

  it('즉시 게시에는 적용 시각을 보내지 않는다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => successResponse(operatingHoursData())),
      http.post(publicationPath(4), async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(operatingHoursData({ status: 'ACTIVE' }))
      }),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    fireEvent.change(await screen.findByLabelText('변경 사유'), {
      target: { value: '여름 영업시간' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 4 게시' }))

    await waitFor(() => expect(body).not.toBeNull())
    // IMMEDIATE에 effectiveAt을 함께 보내면 계약 위반이다.
    expect(body).toEqual({
      publicationMode: 'IMMEDIATE',
      changeReason: '여름 영업시간',
    })
  })

  it('예약 게시는 매장 시간대 오프셋을 포함한 시각을 보낸다', async () => {
    let body: Record<string, string> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => successResponse(operatingHoursData())),
      http.post(publicationPath(4), async ({ request }) => {
        body = (await request.json()) as Record<string, string>
        return successResponse(
          operatingHoursData({
            status: 'SCHEDULED',
            effectiveAt: '2099-01-01T09:00:00+09:00',
          }),
        )
      }),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    fireEvent.click(await screen.findByLabelText('예약 게시'))
    fireEvent.change(screen.getByLabelText('게시 시각'), {
      target: { value: '2099-01-01T09:00' },
    })
    fireEvent.change(screen.getByLabelText('변경 사유'), {
      target: { value: '신년 영업시간' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 4 게시 예약' }))

    await waitFor(() => expect(body).not.toBeNull())
    const sent = body as unknown as Record<string, string>
    expect(sent.publicationMode).toBe('SCHEDULED')
    expect(sent.effectiveAt).toBe('2099-01-01T09:00:00+09:00')
  })

  it('과거 시각으로는 예약 게시하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => successResponse(operatingHoursData())),
      http.post(publicationPath(4), () => {
        called = true
        return successResponse(operatingHoursData())
      }),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    fireEvent.click(await screen.findByLabelText('예약 게시'))
    fireEvent.change(screen.getByLabelText('게시 시각'), {
      target: { value: '2000-01-01T09:00' },
    })
    fireEvent.change(screen.getByLabelText('변경 사유'), {
      target: { value: '과거 시각' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 4 게시 예약' }))

    expect(await screen.findByText('미래 시각을 입력해 주세요.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('게시 예약 상태에서만 게시 취소를 노출한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () => successResponse(operatingHoursData())),
      http.post(publicationPath(4), () =>
        successResponse(
          operatingHoursData({
            status: 'SCHEDULED',
            effectiveAt: '2099-01-01T09:00:00+09:00',
          }),
        ),
      ),
      http.post(cancellationPath(4), () =>
        successResponse(operatingHoursData({ status: 'DRAFT' })),
      ),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    // 초안 상태에는 철회 경로가 없다.
    await screen.findByRole('button', { name: '버전 4 게시' })
    expect(
      screen.queryByRole('button', { name: '예약 게시 취소' }),
    ).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('예약 게시'))
    fireEvent.change(screen.getByLabelText('게시 시각'), {
      target: { value: '2099-01-01T09:00' },
    })
    fireEvent.change(screen.getByLabelText('변경 사유'), {
      target: { value: '신년 영업시간' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 4 게시 예약' }))

    expect(
      await screen.findByRole('button', { name: '예약 게시 취소' }),
    ).toBeInTheDocument()
  })

  it('서버의 시간대 충돌은 구간을 확인하도록 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(DRAFT_PATH, () =>
        errorResponse(409, 'STORE_006', '영업 시간대가 충돌합니다.'),
      ),
    )

    renderPage()
    await openMondayWith('09:00', '22:00')
    saveDraft()

    expect(
      await screen.findByText(
        '영업시간 또는 예약 접수 시간대가 충돌합니다. 구간을 확인해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('영업시간 변경이 기존 예약을 자동으로 바꾸지 않는다고 밝힌다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText(/이미 접수된 예약을 자동으로 옮기거나 취소하지/),
    ).toBeInTheDocument()
  })
})
