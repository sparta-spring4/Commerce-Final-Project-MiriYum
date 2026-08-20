import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  OPERATOR_STORES_PATH,
  authenticatedOperator,
  catalogHandlers,
  managedStore,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreCreatePage } from './StoreCreatePage'

function renderCreatePage() {
  return renderOperator(<StoreCreatePage />, {
    route: STORE_OPERATOR_PATHS.storeCreate,
    path: STORE_OPERATOR_PATHS.storeCreate,
    probePaths: [STORE_OPERATOR_PATHS.store],
  })
}

function fillRequiredFields() {
  fireEvent.change(screen.getByLabelText('사업자등록번호'), {
    target: { value: '123-45-67890' },
  })
  fireEvent.change(screen.getByLabelText('매장명'), {
    target: { value: '카페 에비뉴' },
  })
  fireEvent.change(screen.getByLabelText('매장 주소'), {
    target: { value: '서울 강남구 테헤란로 152' },
  })
  fireEvent.change(screen.getByLabelText('주 카테고리'), {
    target: { value: 'CAFE_DESSERT' },
  })
}

function agreeAll() {
  fireEvent.click(
    screen.getByLabelText('입력한 사업자 정보에 대한 책임을 확약합니다.'),
  )
  fireEvent.click(screen.getByLabelText('필수 입점 약관에 동의합니다.'))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '매장 등록' }))
}

describe('매장 등록 화면', () => {
  it('자기확약과 약관 동의 전에는 요청을 보내지 않는다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, () => {
        called = true
        return successResponse(managedStore())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    submit()

    expect(
      await screen.findByText('사업자 정보 자기확약이 필요합니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('필수 입점 약관 동의가 필요합니다.'),
    ).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('시간대를 함께 제출하고 멱등 키를 붙인다', async () => {
    let body: Record<string, unknown> | null = null
    let idempotencyKey: string | null = null
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        idempotencyKey = request.headers.get('idempotency-key')
        return successResponse(managedStore())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()

    await waitFor(() => expect(body).not.toBeNull())
    // 주소나 브라우저 기본값으로 시간대를 추측하지 않는다.
    expect(body).toMatchObject({
      businessRegistrationNumber: '1234567890',
      timeZoneId: 'Asia/Seoul',
      applicantSelfAttested: true,
      requiredTermsAgreed: true,
    })
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('업종이 OTHER여도 픽업 선택을 자동으로 되돌리지 않는다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(managedStore())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    fireEvent.change(screen.getByLabelText('업종'), {
      target: { value: 'OTHER' },
    })
    fireEvent.click(screen.getByLabelText(/픽업/))
    agreeAll()
    submit()

    await waitFor(() => expect(body).not.toBeNull())
    // 업종은 픽업 자격의 근거가 아니다. 값을 클라이언트가 보정하지 않는다.
    expect(body).toMatchObject({
      businessType: 'OTHER',
      modes: {
        reservationEnabled: true,
        menuHoldEnabled: false,
        pickupEnabled: true,
      },
    })
  })

  it('음식점 업종을 선택해 등록 요청에 담는다', async () => {
    let body: Record<string, unknown> | null = null
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return successResponse(managedStore())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    fireEvent.change(screen.getByLabelText('업종'), {
      target: { value: 'RESTAURANT' },
    })
    agreeAll()
    submit()

    await waitFor(() => expect(body).not.toBeNull())
    expect(body).toMatchObject({ businessType: 'RESTAURANT' })
  })

  it('사업자등록번호 중복은 서버 오류 문구로 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, () =>
        errorResponse(409, 'STORE_002', '이미 등록된 사업자등록번호입니다.'),
      ),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()

    expect(
      await screen.findByText('이미 등록된 사업자등록번호입니다.'),
    ).toBeInTheDocument()
  })

  it('승인되지 않은 카테고리 오류는 다시 선택하도록 안내한다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, () =>
        errorResponse(400, 'STORE_004', '승인되지 않은 코드입니다.'),
      ),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()

    expect(
      await screen.findByText(
        '승인되지 않은 카테고리 또는 태그입니다. 목록에서 다시 선택해 주세요.',
      ),
    ).toBeInTheDocument()
  })

  it('등록에 성공하면 승인 대기 없이 매장 관리 화면으로 이어 간다', async () => {
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, () => successResponse(managedStore())),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/store-operator/stores/7',
      ),
    )
  })

  it('같은 내용으로 다시 제출하면 같은 멱등 키를 유지한다', async () => {
    const keys: string[] = []
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, ({ request }) => {
        keys.push(request.headers.get('idempotency-key') ?? '')
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()
    await waitFor(() => expect(keys).toHaveLength(1))

    submit()
    await waitFor(() => expect(keys).toHaveLength(2))

    // 같은 내용의 재시도가 새 키를 받으면 중복 등록을 막지 못한다.
    expect(keys[0]).toBe(keys[1])
  })

  it('입력을 고쳐 다시 제출하면 새 멱등 키를 쓴다', async () => {
    const keys: string[] = []
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, ({ request }) => {
        keys.push(request.headers.get('idempotency-key') ?? '')
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')

    fillRequiredFields()
    agreeAll()
    submit()
    await waitFor(() => expect(keys).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('매장명'), {
      target: { value: '카페 에비뉴 2호점' },
    })
    submit()
    await waitFor(() => expect(keys).toHaveLength(2))

    // 내용이 다른데 이전 키를 쓰면 서버가 COMMON_007로 거절한다.
    expect(keys[0]).not.toBe(keys[1])
  })
})
