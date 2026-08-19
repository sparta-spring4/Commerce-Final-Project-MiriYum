import { useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { delay, http } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { successResponse } from '../../../test/msw/envelope'
import { server } from '../../../test/msw/server'
import { TestQueryProvider } from '../../../test/TestQueryProvider'
import { PlatformOperatorAuthProvider } from '../../platform-operator-auth'
import {
  authenticatedPlatformOperator,
  currentPlatformOperator,
} from '../../platform-operator-auth/test/handlers'
import { ReauthenticationDialog } from './ReauthenticationDialog'

const REAUTH_PATH = '/api/v1/platform-operators/reauthentication-approvals'

/**
 * 다이얼로그를 여는 화면. 실제 사용처와 같은 모양으로, 실행 버튼이 다이얼로그를
 * 열고 닫힌 뒤 포커스가 그 버튼으로 돌아오는지 확인하기 위해 필요하다.
 */
function Harness({
  onApproved = vi.fn(),
}: {
  onApproved?: (approval: string) => void
}) {
  return (
    <TestQueryProvider>
      <PlatformOperatorAuthProvider>
        <HarnessBody onApproved={onApproved} />
      </PlatformOperatorAuthProvider>
    </TestQueryProvider>
  )
}

function HarnessBody({
  onApproved,
}: {
  onApproved: (approval: string) => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        실행
      </button>
      {open && (
        <ReauthenticationDialog
          purpose="ACCOUNT_SANCTION"
          targetType="CONSUMER_ACCOUNT"
          targetId="member-1"
          description="본인 확인이 필요합니다."
          onApproved={(approval) => {
            setOpen(false)
            onApproved(approval)
          }}
          onCancel={() => setOpen(false)}
        />
      )}
    </>
  )
}

function openDialog() {
  fireEvent.click(screen.getByRole('button', { name: '실행' }))
}

describe('재인증 다이얼로그 접근성', () => {
  beforeEach(() => {
    server.use(authenticatedPlatformOperator(), currentPlatformOperator())
  })

  it('열리면 첫 입력으로 포커스가 이동한다', async () => {
    render(<Harness />)
    openDialog()

    const password = await screen.findByLabelText('현재 비밀번호')
    await waitFor(() => expect(document.activeElement).toBe(password))
  })

  it('제목과 설명을 다이얼로그에 연결한다', async () => {
    render(<Harness />)
    openDialog()

    const dialog = await screen.findByRole('dialog', {
      name: '재인증이 필요합니다',
    })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    const describedBy = dialog.getAttribute('aria-describedby')
    expect(describedBy).not.toBeNull()
    expect(document.getElementById(describedBy!)).toHaveTextContent(
      '본인 확인이 필요합니다.',
    )
  })

  it('마지막 요소에서 Tab을 누르면 다이얼로그 안 첫 요소로 돌아온다', async () => {
    render(<Harness />)
    openDialog()

    const dialog = await screen.findByRole('dialog', {
      name: '재인증이 필요합니다',
    })
    const focusable = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])',
      ),
    )
    const first = focusable[0]
    const last = focusable[focusable.length - 1]

    last.focus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(document.activeElement).toBe(first)

    first.focus()
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)
  })

  it('ESC로 닫고, 닫힌 뒤 열었던 버튼으로 포커스가 돌아온다', async () => {
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: '실행' })
    trigger.focus()
    openDialog()
    await screen.findByRole('dialog', { name: '재인증이 필요합니다' })

    fireEvent.keyDown(document, { key: 'Escape' })

    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: '재인증이 필요합니다' }),
      ).not.toBeInTheDocument(),
    )
    expect(document.activeElement).toBe(trigger)
  })

  /**
   * 처리 중 ESC로 닫으면 이미 발급된 승인값을 아무도 받지 못한 채 사라지고,
   * 사용자는 취소된 줄 안다. 발급이 끝날 때까지는 닫지 않는다.
   */
  it('승인 발급 중에는 ESC로 닫히지 않는다', async () => {
    let release: (() => void) | null = null
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.post(REAUTH_PATH, async () => {
        await pending
        return successResponse({
          approval: 'approval-1',
          expiresAt: '2026-08-17T10:05:00Z',
        })
      }),
    )
    const onApproved = vi.fn()
    render(<Harness onApproved={onApproved} />)
    openDialog()

    fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    // 발급이 끝나기 전 ESC. 다이얼로그는 그대로 있어야 한다.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '취소' })).toBeDisabled(),
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(
      screen.getByRole('dialog', { name: '재인증이 필요합니다' }),
    ).toBeInTheDocument()

    release!()
    await waitFor(() => expect(onApproved).toHaveBeenCalledWith('approval-1'))
  })

  it('승인 발급 중 unmount되면 늦은 응답을 부모에게 전달하지 않는다', async () => {
    let release: (() => void) | null = null
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    let requestSignal: AbortSignal | undefined
    server.use(
      http.post(REAUTH_PATH, async ({ request }) => {
        requestSignal = request.signal
        await pending
        return successResponse({
          approval: 'late-approval',
          expiresAt: '2026-08-17T10:05:00Z',
        })
      }),
    )
    const onApproved = vi.fn()
    const rendered = render(<Harness onApproved={onApproved} />)
    openDialog()
    fireEvent.change(await screen.findByLabelText('현재 비밀번호'), {
      target: { value: 'Miriyum1!' },
    })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '확인' })).toBeDisabled(),
    )
    await waitFor(() => expect(requestSignal).toBeDefined())

    rendered.unmount()
    expect(requestSignal!.aborted).toBe(true)
    release!()
    await delay(100)

    expect(onApproved).not.toHaveBeenCalled()
  })
})
