import { CONSUMER_PATHS } from '../../../../../app/routes/paths/consumerPaths'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../../../test/msw/envelope'
import { server } from '../../../../../test/msw/server'
import { ConsumerAuthProvider } from '../../auth'
import { AccountErrorCode } from '../../../../../shared/auth/authErrors'
import { authenticatedConsumer } from '../../auth/test/handlers'
import {
  CONSUMER_ME_CONTACT_PATH,
  CONSUMER_ME_PATH,
  CONSUMER_CURRENT_WAITING_PATH,
  CONSUMER_PAYMENTS_PATH,
  consumerAccount,
  consumerPayment,
  consumerPaymentHistory,
  consumerWaitingSnapshot,
} from '../test/fixtures'
import { MyPage } from './MyPage'

function renderMyPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <ConsumerAuthProvider>
        <MemoryRouter initialEntries={[CONSUMER_PATHS.myPage]}>
          <Routes>
            <Route path={CONSUMER_PATHS.myPage} element={<MyPage />} />
          </Routes>
        </MemoryRouter>
      </ConsumerAuthProvider>
    </QueryClientProvider>,
  )
}

describe('마이페이지', () => {
  it('본인 정보를 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
    )

    renderMyPage()

    expect(await screen.findByText('user@example.com')).toBeInTheDocument()
    expect(screen.getByText('미리냠')).toBeInTheDocument()
  })

  it('휴대전화는 서버가 마스킹한 값만 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
    )

    renderMyPage()

    expect(await screen.findByText('010-****-5678')).toBeInTheDocument()
  })

  it('닉네임 변경은 닉네임만 보내고 Idempotency-Key를 붙인다', async () => {
    let body: unknown = null
    let idempotencyKey: string | null = null

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.patch(CONSUMER_ME_PATH, async ({ request }) => {
        body = await request.json()
        idempotencyKey = request.headers.get('Idempotency-Key')
        return successResponse(consumerAccount({ nickname: '새이름' }))
      }),
    )

    renderMyPage()

    fireEvent.click(await screen.findByRole('button', { name: '닉네임 변경' }))
    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '새이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))

    await waitFor(() => expect(body).toEqual({ nickname: '새이름' }))
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  })

  /*
   * 닉네임은 요청 본문이라 요청 지문의 일부다. 값을 고쳐 다시 보내면서 같은
   * 키를 쓰면 서버가 `COMMON_007`로 거절한다.
   */
  it('확정 실패 뒤 닉네임을 고치면 새 멱등 키로 보낸다', async () => {
    const keys: string[] = []
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.patch(CONSUMER_ME_PATH, ({ request }) => {
        attempt += 1
        const key = request.headers.get('Idempotency-Key')
        if (key !== null) {
          keys.push(key)
        }
        if (attempt === 1) {
          return errorResponse(
            409,
            AccountErrorCode.NICKNAME_CHANGE_TOO_SOON,
            '아직 변경할 수 없습니다.',
          )
        }
        return successResponse(consumerAccount({ nickname: '둘째이름' }))
      }),
    )

    renderMyPage()

    fireEvent.click(await screen.findByRole('button', { name: '닉네임 변경' }))
    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '첫이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))
    await waitFor(() => expect(attempt).toBe(1))

    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '둘째이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))

    await waitFor(() => expect(attempt).toBe(2))
    expect(new Set(keys).size).toBe(2)
  })

  it('결과 불명 뒤 닉네임을 고친 재전송은 보내지 않는다', async () => {
    let attempt = 0

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.patch(CONSUMER_ME_PATH, () => {
        attempt += 1
        return errorResponse(500, 'COMMON_011', '서버 오류입니다.')
      }),
    )

    renderMyPage()

    fireEvent.click(await screen.findByRole('button', { name: '닉네임 변경' }))
    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '첫이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))
    await waitFor(() => expect(attempt).toBe(1))

    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '둘째이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))

    expect(
      await screen.findByText(/닉네임을 바꿔 다시 보내면 두 번 변경될 수 있습니다/),
    ).toBeInTheDocument()
    expect(attempt).toBe(1)
  })

  it('ACCOUNT_005는 서버 메시지를 그대로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.patch(CONSUMER_ME_PATH, () =>
        errorResponse(
          409,
          AccountErrorCode.NICKNAME_CHANGE_TOO_SOON,
          '닉네임은 2026-09-01 이후에 다시 변경할 수 있습니다.',
        ),
      ),
    )

    renderMyPage()

    fireEvent.click(await screen.findByRole('button', { name: '닉네임 변경' }))
    fireEvent.change(screen.getByLabelText('새 닉네임'), {
      target: { value: '새이름' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장하기' }))

    // 다음 변경 가능 시각을 클라이언트가 계산해 지어내지 않는다.
    expect(
      await screen.findByText(
        '닉네임은 2026-09-01 이후에 다시 변경할 수 있습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('연락처가 없는 계정에만 최초 등록 폼을 보여 준다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () =>
        successResponse(consumerAccount({ phoneNumber: null })),
      ),
    )

    renderMyPage()

    expect(
      await screen.findByRole('form', { name: '연락처 등록' }),
    ).toBeInTheDocument()
  })

  it('연락처가 이미 있으면 등록·변경 폼을 만들지 않는다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
    )

    renderMyPage()

    await screen.findByText('010-****-5678')
    expect(
      screen.queryByRole('form', { name: '연락처 등록' }),
    ).not.toBeInTheDocument()
  })

  it('연락처를 정규화해 등록하고 Idempotency-Key를 붙인다', async () => {
    let body: { phoneNumber?: string } | null = null
    let idempotencyKey: string | null = null

    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () =>
        successResponse(consumerAccount({ phoneNumber: null })),
      ),
      http.put(CONSUMER_ME_CONTACT_PATH, async ({ request }) => {
        body = (await request.json()) as { phoneNumber: string }
        idempotencyKey = request.headers.get('Idempotency-Key')
        return successResponse(consumerAccount())
      }),
    )

    renderMyPage()

    fireEvent.change(await screen.findByLabelText('휴대전화 번호'), {
      target: { value: '010-1234-5678' },
    })
    fireEvent.click(screen.getByRole('button', { name: '연락처 등록' }))

    await waitFor(() => expect(body?.phoneNumber).toBe('01012345678'))
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('ACCOUNT_007은 변경 불가로 안내한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () =>
        successResponse(consumerAccount({ phoneNumber: null })),
      ),
      http.put(CONSUMER_ME_CONTACT_PATH, () =>
        errorResponse(
          409,
          AccountErrorCode.CONTACT_CHANGE_NOT_ALLOWED,
          '최초 등록한 연락처는 변경할 수 없습니다.',
        ),
      ),
    )

    renderMyPage()

    fireEvent.change(await screen.findByLabelText('휴대전화 번호'), {
      target: { value: '010-1234-5678' },
    })
    fireEvent.click(screen.getByRole('button', { name: '연락처 등록' }))

    expect(
      await screen.findByText('이미 등록한 연락처는 변경할 수 없습니다.'),
    ).toBeInTheDocument()
  })

  it('연결된 알림 이력과 아직 준비되지 않은 항목을 구분한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
    )

    renderMyPage()

    await screen.findByText('user@example.com')

    expect(screen.getByRole('link', { name: /알림 이력/ })).toHaveAttribute(
      'href',
      CONSUMER_PATHS.notificationHistory,
    )

    expect(screen.getByRole('link', { name: /지난 웨이팅/ })).toHaveAttribute(
      'href', CONSUMER_PATHS.waitingHistory,
    )

    for (const label of ['환불 내역', '비밀번호 변경']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })

  it('실제 결제 이력과 현재 웨이팅을 함께 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.get(CONSUMER_PAYMENTS_PATH, () =>
        successResponse(consumerPaymentHistory([consumerPayment()])),
      ),
      http.get(CONSUMER_CURRENT_WAITING_PATH, () =>
        successResponse(consumerWaitingSnapshot()),
      ),
    )

    renderMyPage()

    expect(await screen.findByText('25,000원')).toBeInTheDocument()
    expect(screen.getByText('대기 중')).toBeInTheDocument()
    expect(screen.getByText('7번')).toBeInTheDocument()
    expect(screen.getByText('3팀')).toBeInTheDocument()
  })

  it('현재 웨이팅의 WAITING_003 404는 빈 상태로 표시한다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.get(CONSUMER_PAYMENTS_PATH, () =>
        successResponse(consumerPaymentHistory()),
      ),
      http.get(CONSUMER_CURRENT_WAITING_PATH, () =>
        errorResponse(404, 'WAITING_003', '웨이팅 팀을 찾을 수 없습니다.'),
      ),
    )

    renderMyPage()

    expect(
      await screen.findByText('현재 웨이팅이 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('현재 웨이팅을 불러오지 못했습니다.')).not.toBeInTheDocument()
  })

  it('결제 조회 실패는 결제 영역에서 다시 시도할 수 있다', async () => {
    server.use(
      authenticatedConsumer(),
      http.get(CONSUMER_ME_PATH, () => successResponse(consumerAccount())),
      http.get(CONSUMER_PAYMENTS_PATH, () =>
        errorResponse(503, 'COMMON_012', '서비스를 일시적으로 이용할 수 없습니다.'),
      ),
      http.get(CONSUMER_CURRENT_WAITING_PATH, () =>
        errorResponse(404, 'WAITING_003', '웨이팅 팀을 찾을 수 없습니다.'),
      ),
    )

    renderMyPage()

    expect(
      await screen.findByText('결제 내역을 불러오지 못했습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '상태 다시 확인' }),
    ).toBeInTheDocument()
  })
})
