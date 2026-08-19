import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fillPath } from '../../../../app/routes/path'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  STORE_ID,
  authenticatedOperator,
  managedStoreHandler,
  operatorStorePath,
} from '../../../store/store-operator/test/handlers'
import { renderOperator } from '../../../store/store-operator/test/renderOperator'
import { ReservationTimePolicyPage } from './ReservationTimePolicyPage'

const POLICY_PATH = operatorStorePath('/reservation-time-policies')

function policyResponse(overrides: Record<string, unknown> = {}) {
  return {
    storeId: STORE_ID,
    version: 5,
    slotInterval: 30,
    serviceDuration: 90,
    turnoverDuration: 15,
    status: 'DRAFT',
    effectiveAt: null,
    ...overrides,
  }
}

function renderPage() {
  return renderOperator(<ReservationTimePolicyPage />, {
    route: fillPath(STORE_OPERATOR_PATHS.reservationTimePolicy, {
      storeId: STORE_ID,
    }),
    path: STORE_OPERATOR_PATHS.reservationTimePolicy,
  })
}

function saveDraft() {
  fireEvent.click(screen.getByRole('button', { name: '초안 저장' }))
}

describe('예약 시간 정책 화면', () => {
  it('세 시간 값을 각각 보낸다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(policyResponse())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('예약 시작 간격(분)'), {
      target: { value: '20' },
    })
    saveDraft()

    await waitFor(() => expect(body).not.toBeNull())
    // 세 값은 의미가 다르다. 하나로 합쳐 계산하지 않는다.
    expect(body).toEqual({
      slotInterval: 20,
      serviceDuration: 90,
      turnoverDuration: 15,
    })
  })

  it('서비스 시간과 전환 시간의 합이 하루를 넘으면 저장하지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, () => {
        called = true
        return successResponse(policyResponse())
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('서비스 소요시간(분)'), {
      target: { value: '1000' },
    })
    fireEvent.change(screen.getByLabelText('자원 전환 시간(분)'), {
      target: { value: '600' },
    })
    saveDraft()

    expect(
      await screen.findByText(
        '서비스 시간과 전환 시간의 합은 1440분을 넘을 수 없습니다.',
      ),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('전환 시간 0을 허용한다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(policyResponse({ turnoverDuration: 0 }))
      }),
    )

    renderPage()
    fireEvent.change(await screen.findByLabelText('자원 전환 시간(분)'), {
      target: { value: '0' },
    })
    saveDraft()

    await waitFor(() => expect(body).toMatchObject({ turnoverDuration: 0 }))
  })

  it('초안을 저장하기 전에는 게시 단계를 열지 않는다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText('먼저 초안을 저장해 주세요.'),
    ).toBeInTheDocument()
  })

  it('초안 상태를 게시된 상태로 표시하지 않는다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, () => successResponse(policyResponse())),
    )

    renderPage()
    await screen.findByLabelText('예약 시작 간격(분)')
    saveDraft()

    expect(await screen.findByText('초안')).toBeInTheDocument()
    expect(screen.queryByText('게시됨')).not.toBeInTheDocument()
  })

  it('예약 게시 후에만 게시 철회를 노출한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, () => successResponse(policyResponse())),
      http.post(`${POLICY_PATH}/5/publications`, () =>
        successResponse(
          policyResponse({
            status: 'SCHEDULED',
            effectiveAt: '2099-01-01T09:00:00+09:00',
          }),
        ),
      ),
    )

    renderPage()
    await screen.findByLabelText('예약 시작 간격(분)')
    saveDraft()

    await screen.findByRole('button', { name: '버전 5 게시' })
    expect(
      screen.queryByRole('button', { name: '예약 게시 취소' }),
    ).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('예약 게시'))
    fireEvent.change(screen.getByLabelText('게시 시각'), {
      target: { value: '2099-01-01T09:00' },
    })
    fireEvent.change(screen.getByLabelText('변경 사유'), {
      target: { value: '연말 정책' },
    })
    fireEvent.click(screen.getByRole('button', { name: '버전 5 게시 예약' }))

    expect(
      await screen.findByRole('button', { name: '예약 게시 취소' }),
    ).toBeInTheDocument()
  })

  it('시간 정책 상태 충돌은 서버 코드로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      managedStoreHandler,
      http.put(POLICY_PATH, () =>
        errorResponse(409, 'RESERVATION_010', '현재 상태에서 처리할 수 없습니다.'),
      ),
    )

    renderPage()
    await screen.findByLabelText('예약 시작 간격(분)')
    saveDraft()

    expect(
      await screen.findByText(
        '현재 시간 정책 상태에서는 이 작업을 할 수 없습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('게시가 기존 예약 시각을 바꾸지 않는다고 밝힌다', async () => {
    server.use(authenticatedOperator(), managedStoreHandler)

    renderPage()

    expect(
      await screen.findByText(/이미 접수된 예약의 시각은 바뀌지 않습니다/),
    ).toBeInTheDocument()
  })
})
