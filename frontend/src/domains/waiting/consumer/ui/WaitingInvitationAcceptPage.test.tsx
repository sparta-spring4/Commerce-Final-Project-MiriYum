import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/renderWithProviders'
import type { WaitingJoinErrorView } from '../model/partyViewState'
import {
  WaitingInvitationAcceptPage,
  type WaitingJoinProgress,
} from './WaitingInvitationAcceptPage'

interface Overrides {
  progress?: WaitingJoinProgress
  error?: WaitingJoinErrorView | null
}

/**
 * 화면은 합류 요청을 보내지 않는다. 각 테스트가 표현할 상태를 넣고 입력·콜백만
 * 관찰한다. 실제 요청과 멱등 키는 이 화면 밖에 있다.
 */
function renderPage(overrides: Overrides = {}) {
  const onAcceptInvitation = vi.fn()
  const onRetry = vi.fn()

  function page(current: Overrides) {
    return (
      <WaitingInvitationAcceptPage
        progress={current.progress ?? 'idle'}
        error={current.error ?? null}
        onAcceptInvitation={onAcceptInvitation}
        onRetry={onRetry}
      />
    )
  }

  const view = renderWithProviders(page(overrides))

  function update(next: Overrides) {
    view.rerender(page({ ...overrides, ...next }))
  }

  return { ...view, onAcceptInvitation, onRetry, update }
}

function codeInput() {
  return screen.getByLabelText('초대 코드')
}

function submitButton() {
  return screen.getByRole('button', { name: '웨이팅 일행으로 합류' })
}

describe('일행 합류 화면 - 코드 입력', () => {
  it('코드 입력 전에는 빈 입력과 합류 버튼만 둔다', () => {
    renderPage()

    expect(
      screen.getByRole('heading', { level: 1, name: '웨이팅 일행 합류' }),
    ).toBeInTheDocument()
    expect(codeInput()).toHaveValue('')
    expect(submitButton()).toBeEnabled()
  })

  /* 들어오자마자 붉은 오류를 띄우지 않는다. 한 번 제출해 본 뒤에만 보여 준다. */
  it('들어온 직후에는 검증 문구를 보여 주지 않는다', () => {
    renderPage()

    expect(screen.queryByText('초대 코드를 입력해 주세요.')).not.toBeInTheDocument()
    expect(codeInput()).not.toHaveAttribute('aria-invalid')
  })

  it('빈 코드로 제출하면 요청하지 않고 입력 오류를 알린다', () => {
    const { onAcceptInvitation } = renderPage()

    fireEvent.click(submitButton())

    expect(onAcceptInvitation).not.toHaveBeenCalled()
    expect(screen.getByText('초대 코드를 입력해 주세요.')).toBeInTheDocument()
    expect(codeInput()).toHaveAttribute('aria-invalid', 'true')
  })

  it('공백만 입력하면 요청하지 않는다', () => {
    const { onAcceptInvitation } = renderPage()

    fireEvent.change(codeInput(), { target: { value: '   ' } })
    fireEvent.click(submitButton())

    expect(onAcceptInvitation).not.toHaveBeenCalled()
    expect(screen.getByText('초대 코드를 입력해 주세요.')).toBeInTheDocument()
  })

  it('입력한 코드를 앞뒤 공백만 떼고 그대로 넘긴다', () => {
    const { onAcceptInvitation } = renderPage()

    fireEvent.change(codeInput(), { target: { value: '  JOIN-4821  ' } })
    fireEvent.click(submitButton())

    expect(onAcceptInvitation).toHaveBeenCalledWith('JOIN-4821')
  })

  /*
   * 코드 형식은 계약이 공개하지 않는다. 화면이 길이나 문자 집합을 단정하면
   * 서버가 받는 코드를 화면이 대신 거절한다.
   */
  it('계약에 없는 형식 규칙으로 코드를 막지 않는다', () => {
    const { onAcceptInvitation } = renderPage()

    fireEvent.change(codeInput(), { target: { value: 'a' } })
    fireEvent.click(submitButton())

    expect(onAcceptInvitation).toHaveBeenCalledWith('a')
  })

  it('코드를 브라우저가 기억하지 않도록 자동완성을 끈다', () => {
    renderPage()

    expect(codeInput()).toHaveAttribute('autocomplete', 'off')
  })
})

describe('일행 합류 화면 - 진행과 성공', () => {
  it('합류 처리 중에는 버튼을 진행 중으로 두고 입력을 잠근다', () => {
    renderPage({ progress: 'submitting' })

    const button = submitButton()
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(codeInput()).toBeDisabled()
  })

  it('처리 중에는 같은 코드를 다시 제출하지 않는다', () => {
    const { onAcceptInvitation, update } = renderPage()

    fireEvent.change(codeInput(), { target: { value: 'JOIN-4821' } })
    fireEvent.click(submitButton())
    expect(onAcceptInvitation).toHaveBeenCalledTimes(1)

    /* 컨테이너가 요청을 시작했다고 알려 온 뒤의 상태. */
    update({ progress: 'submitting' })

    /* 버튼 클릭과 Enter(form submit) 양쪽 모두 두 번째 요청을 만들지 않는다. */
    fireEvent.click(submitButton())
    fireEvent.submit(codeInput())
    expect(onAcceptInvitation).toHaveBeenCalledTimes(1)
  })

  it('합류에 성공하면 입력을 거두고 결과를 알린다', () => {
    renderPage({ progress: 'succeeded' })

    expect(screen.getByText('일행으로 합류했습니다.')).toBeInTheDocument()
    expect(screen.queryByLabelText('초대 코드')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '웨이팅 일행으로 합류' }),
    ).not.toBeInTheDocument()
  })

  /* 계약은 새 팀을 만들지 않고 기존 팀에 membership만 더한다. */
  it('성공 안내가 새 웨이팅을 만들었다고 말하지 않는다', () => {
    const { unmount } = renderPage({ progress: 'succeeded' })

    const notice = screen.getByRole('status')
    expect(within(notice).queryByText(/새 웨이팅/)).not.toBeInTheDocument()
    expect(within(notice).getByText(/순번과 일행 목록/)).toBeInTheDocument()
    unmount()

    /* 들어올 때부터 새 웨이팅이 아니라는 사실을 먼저 말한다. */
    renderPage()
    expect(
      screen.getByText(/새 웨이팅이 만들어지지 않고 순번도 그대로 유지됩니다\./),
    ).toBeInTheDocument()
  })
})

describe('일행 합류 화면 - 실패 상태', () => {
  it('잘못된 코드를 알리고 재시도를 권하지 않는다', () => {
    renderPage({ error: { code: 'INVALID_CODE' } })

    expect(screen.getByText('사용할 수 없는 초대 코드입니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('만료된 코드는 15분 만료를 근거로 안내한다', () => {
    renderPage({ error: { code: 'EXPIRED_CODE' } })

    /* 입력 도움말에도 같은 문구가 있어 오류 배너 안으로 범위를 좁힌다. */
    const alert = screen.getByRole('alert')
    expect(within(alert).getByText('초대 코드가 만료되었습니다.')).toBeInTheDocument()
    expect(within(alert).getByText(/15분 동안만/)).toBeInTheDocument()
  })

  it('철회되거나 이미 사용된 코드를 따로 알린다', () => {
    renderPage({ error: { code: 'REVOKED_OR_USED_CODE' } })

    expect(screen.getByText('이미 처리된 초대 코드입니다.')).toBeInTheDocument()
    expect(screen.getByText(/철회했거나 다른 사람이 사용/)).toBeInTheDocument()
  })

  it('인원 초과와 기존 웨이팅 충돌을 구분해 알린다', () => {
    const { unmount } = renderPage({ error: { code: 'PARTY_CAPACITY_EXCEEDED' } })

    expect(screen.getByText('일행 인원이 가득 찼습니다.')).toBeInTheDocument()
    expect(screen.queryByText('이미 진행 중인 웨이팅이 있습니다.')).not.toBeInTheDocument()
    unmount()

    renderPage({ error: { code: 'ACCOUNT_ACTIVE_WAITING_EXISTS' } })
    expect(screen.getByText('이미 진행 중인 웨이팅이 있습니다.')).toBeInTheDocument()
    expect(screen.getByText(/기존 웨이팅을 취소하거나 종료한 뒤/)).toBeInTheDocument()
    expect(screen.queryByText('일행 인원이 가득 찼습니다.')).not.toBeInTheDocument()
  })

  it('팀 상태가 바뀌어 합류할 수 없는 경우를 알린다', () => {
    renderPage({ error: { code: 'TEAM_STATE_CHANGED' } })

    expect(screen.getByText('지금은 이 일행에 합류할 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('일시적 실패에는 재시도를 준다', () => {
    const { onRetry } = renderPage({ error: { code: 'REQUEST_FAILED' } })

    expect(screen.getByText('합류를 처리하지 못했습니다.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('서버가 준 문구가 있으면 그대로 보여 준다', () => {
    renderPage({
      error: { code: 'INVALID_CODE', message: '사용할 수 없는 일행 초대입니다.' },
    })

    expect(screen.getByText('사용할 수 없는 일행 초대입니다.')).toBeInTheDocument()
  })

  it('실패한 뒤에도 코드를 고쳐 다시 제출할 수 있다', () => {
    const { onAcceptInvitation } = renderPage({ error: { code: 'INVALID_CODE' } })

    fireEvent.change(codeInput(), { target: { value: 'JOIN-9999' } })
    fireEvent.click(submitButton())

    expect(onAcceptInvitation).toHaveBeenCalledWith('JOIN-9999')
  })
})

describe('일행 합류 화면 - 접근성', () => {
  it('입력에 레이블과 도움말을 프로그램적으로 연결한다', () => {
    renderPage()

    const input = codeInput()
    expect(input).toBeRequired()
    expect(input).toHaveAccessibleDescription(/15분 동안만 쓸 수 있습니다\./)
  })

  it('오류를 alert로 알린다', () => {
    renderPage({ error: { code: 'EXPIRED_CODE' } })

    expect(screen.getByRole('alert')).toHaveTextContent('초대 코드가 만료되었습니다.')
  })

  it('키보드로 코드를 입력하고 Enter로 제출할 수 있다', () => {
    const { onAcceptInvitation } = renderPage()

    const input = codeInput()
    input.focus()
    expect(input).toHaveFocus()

    fireEvent.change(input, { target: { value: 'JOIN-4821' } })
    fireEvent.submit(input)

    expect(onAcceptInvitation).toHaveBeenCalledWith('JOIN-4821')
  })
})
