import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { errorResponse, successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { OperatorCreatePage } from './OperatorCreatePage'

const CREATE_PATH = '/api/v1/platform-operators/accounts'
const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

function renderCreate() {
  render(
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <MemoryRouter>
          <OperatorCreatePage />
        </MemoryRouter>
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>,
  )
}

describe('운영자 계정 생성 권한', () => {
  it('생성 권한이 없으면 등록 폼을 보여 주지 않는다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({
        permissions: ['OPERATOR_AUTHORITY_MANAGE'],
      }),
    )
    renderCreate()

    expect(
      await screen.findByText('이 업무를 수행할 권한이 없습니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: '운영자 등록' }),
    ).not.toBeInTheDocument()
  })

  it('생성 권한이 있으면 등록 폼을 보여 준다', async () => {
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['OPERATOR_CREATE'] }),
    )
    renderCreate()

    expect(
      await screen.findByRole('form', { name: '운영자 등록' }),
    ).toBeInTheDocument()
  })

  it('실패한 입력을 그대로 재시도하면 키를 유지하고 입력을 바꾸면 새 발급 시도로 보낸다', async () => {
    const attempts: Array<{
      idempotencyKey: string
      provisioningId: string
    }> = []
    const reauthenticationTargets: string[] = []

    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['OPERATOR_CREATE'] }),
      http.post(REAUTH_PATH, async ({ request }) => {
        const body = (await request.json()) as { targetId: string }
        reauthenticationTargets.push(body.targetId)
        return successResponse({
          approval: `approval-${reauthenticationTargets.length}`,
          expiresAt: '2026-08-18T15:05:00Z',
        })
      }),
      http.post(CREATE_PATH, async ({ request }) => {
        const body = (await request.json()) as { provisioningId: string }
        attempts.push({
          idempotencyKey: request.headers.get('Idempotency-Key') ?? '',
          provisioningId: body.provisioningId,
        })
        return errorResponse(503, 'COMMON_012', '일시적으로 처리할 수 없습니다.')
      }),
    )
    renderCreate()

    await screen.findByRole('form', { name: '운영자 등록' })
    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: 'operator@miriyum.test' },
    })
    fireEvent.change(screen.getByLabelText('표시명'), {
      target: { value: '신규 운영자' },
    })
    fireEvent.change(screen.getByLabelText('임시 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByLabelText('회원지원'))
    fireEvent.change(screen.getByLabelText('사건 ID'), {
      target: { value: 'case-100' },
    })
    fireEvent.change(screen.getByLabelText('사건 version'), {
      target: { value: '1' },
    })

    async function submitAttempt(expectedCount: number) {
      fireEvent.click(screen.getByRole('button', { name: '운영자 등록' }))
      await screen.findByRole('dialog', { name: '재인증이 필요합니다' })
      fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
        target: { value: 'Miriyum1!' },
      })
      fireEvent.click(screen.getByRole('button', { name: '확인' }))
      await waitFor(() => expect(attempts).toHaveLength(expectedCount))
    }

    await submitAttempt(1)
    await submitAttempt(2)

    expect(attempts[1]).toEqual(attempts[0])
    expect(reauthenticationTargets[1]).toBe(attempts[1].provisioningId)

    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: 'changed@miriyum.test' },
    })
    await submitAttempt(3)

    expect(attempts[2].idempotencyKey).not.toBe(attempts[1].idempotencyKey)
    expect(attempts[2].provisioningId).not.toBe(attempts[1].provisioningId)
    expect(reauthenticationTargets[2]).toBe(attempts[2].provisioningId)
  })

  it('재인증 다이얼로그를 연 뒤 입력이 바뀌면 생성 명령을 보내지 않는다', async () => {
    const createRequests: Request[] = []
    server.use(
      authenticatedPlatformOperator(),
      currentPlatformOperator({ permissions: ['OPERATOR_CREATE'] }),
      http.post(REAUTH_PATH, () =>
        successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-18T15:05:00Z',
        }),
      ),
      http.post(CREATE_PATH, ({ request }) => {
        createRequests.push(request)
        return errorResponse(
          503,
          'COMMON_012',
          '일시적으로 처리할 수 없습니다.',
        )
      }),
    )
    renderCreate()

    const form = await screen.findByRole('form', { name: '운영자 등록' })
    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: 'operator@miriyum.test' },
    })
    fireEvent.change(screen.getByLabelText('표시명'), {
      target: { value: '신규 운영자' },
    })
    fireEvent.change(screen.getByLabelText('임시 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByLabelText('회원지원'))
    fireEvent.change(screen.getByLabelText('사건 ID'), {
      target: { value: 'case-100' },
    })
    fireEvent.change(screen.getByLabelText('사건 version'), {
      target: { value: '1' },
    })

    fireEvent.click(screen.getByRole('button', { name: '운영자 등록' }))
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })

    // 실제 브라우저에서는 inert가 막지만, DOM 조작이나 미지원 환경에서도
    // 명령 경계가 안전한지 확인하기 위해 대기 중 입력 변경을 강제로 발생시킨다.
    fireEvent.change(screen.getByLabelText('이메일'), {
      target: { value: 'changed@miriyum.test' },
    })
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await screen.findByText(/재인증 중 명령 입력이 변경됐습니다/)
    expect(form).not.toHaveAttribute('inert')
    expect(createRequests).toHaveLength(0)
  })
})
