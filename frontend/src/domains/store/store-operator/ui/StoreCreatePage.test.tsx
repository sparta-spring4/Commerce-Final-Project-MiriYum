import { STORE_OPERATOR_PATHS } from '../../../../app/routes/paths/storeOperatorPaths'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { errorResponse, successResponse } from '../../../../test/msw/envelope'
import { server } from '../../../../test/msw/server'
import {
  OPERATOR_STORES_PATH,
  authenticatedOperator,
  catalogHandlers,
  onboardingApplication,
} from '../test/handlers'
import { renderOperator } from '../test/renderOperator'
import { StoreCreatePage } from './StoreCreatePage'

function renderCreatePage() {
  return renderOperator(<StoreCreatePage />, {
    route: STORE_OPERATOR_PATHS.storeCreate,
    path: STORE_OPERATOR_PATHS.storeCreate,
    probePaths: [STORE_OPERATOR_PATHS.onboardingApplication],
  })
}

function fillRequiredFields() {
  const values: readonly [string, string][] = [
    ['사업자등록번호', '123-45-67890'],
    ['법적 상호명', '미리윰 주식회사'],
    ['대표자명', '김대표'],
    ['개업일', '2026-08-21'],
    ['업태', '음식점업'],
    ['종목', '카페'],
    ['매장명', '카페 에비뉴'],
    ['매장 주소', '서울 강남구 테헤란로 152'],
    ['주 카테고리', 'CAFE_BAKERY'],
  ]
  for (const [label, value] of values) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
  fireEvent.change(screen.getByLabelText('사업자등록증 파일'), {
    target: {
      files: [
        new File(['certificate'], 'business-registration.png', {
          type: 'image/png',
        }),
      ],
    },
  })
}

function agreeAll() {
  fireEvent.click(
    screen.getByLabelText('입력한 사업자 정보에 대한 책임을 확약합니다.'),
  )
  fireEvent.click(screen.getByLabelText('필수 입점 약관에 동의합니다.'))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '입점 신청' }))
}

describe('매장 등록 화면', () => {
  it('법적 사업자 정보와 사업자등록증을 필수로 요구한다', async () => {
    let called = false
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, () => {
        called = true
        return successResponse(onboardingApplication())
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')
    fireEvent.change(screen.getByLabelText('사업자등록번호'), {
      target: { value: '1234567890' },
    })
    submit()

    expect(await screen.findByText('법적 상호명을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('대표자명을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('개업일을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('업태를 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('종목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('사업자등록증 파일을 선택해 주세요.')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('서버 카탈로그의 8개 매장 카테고리를 모두 표시한다', async () => {
    server.use(authenticatedOperator(), ...catalogHandlers())
    renderCreatePage()

    const category = await screen.findByLabelText('주 카테고리')
    expect(
      within(category)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual([
      '선택해 주세요',
      '한식',
      '중식',
      '일식',
      '양식',
      '아시아 음식',
      '카페·베이커리',
      '주점',
      '기타',
    ])
    expect(screen.queryByLabelText('비즈니스 타입')).not.toBeInTheDocument()
  })

  it('JSON과 사업자등록증을 multipart로 제출하고 신청 상태 화면으로 이동한다', async () => {
    let multipartBody: string | null = null
    let idempotencyKey: string | null = null
    const stringify = vi.spyOn(JSON, 'stringify')
    server.use(
      authenticatedOperator(),
      ...catalogHandlers(),
      http.post(OPERATOR_STORES_PATH, async ({ request }) => {
        multipartBody = await request.text()
        idempotencyKey = request.headers.get('idempotency-key')
        return successResponse(onboardingApplication({ applicationId: '41' }))
      }),
    )

    renderCreatePage()
    await screen.findByLabelText('매장명')
    fillRequiredFields()
    agreeAll()
    submit()

    await waitFor(() => expect(multipartBody).not.toBeNull())
    expect(multipartBody).toContain('name="application"')
    expect(multipartBody).toContain('application/json')
    expect(multipartBody).toContain('name="businessRegistrationEvidence"')
    expect(multipartBody).toContain('image/png')
    const applicationBody = stringify.mock.calls
      .map(([value]) => value)
      .find(
        (value) =>
          typeof value === 'object' &&
          value !== null &&
          'businessRegistrationNumber' in value,
      )
    expect(applicationBody).toMatchObject({ businessType: 'OTHER' })
    stringify.mockRestore()
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/store-operator/onboarding-applications/41',
    )
  })

  it('같은 신청 내용과 파일로 재시도하면 같은 멱등 키를 유지한다', async () => {
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

    expect(keys[0]).toBe(keys[1])
  })
})
