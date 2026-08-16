import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiContractError, ApiError, NetworkError } from '../api/apiError'
import { ErrorState } from './Feedback'

describe('ErrorState', () => {
  it('네트워크 실패는 확정 실패로 표시하지 않고 상태 재확인을 제안한다', () => {
    render(
      <ErrorState error={new NetworkError('연결 실패')} onRetry={vi.fn()} />,
    )

    expect(
      screen.getByText(/처리 여부가 확정되지 않았습니다/),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '상태 다시 확인' }),
    ).toBeInTheDocument()
  })

  it('401은 로그인 행동을 제안한다', () => {
    render(
      <ErrorState
        error={
          new ApiError({ status: 401, code: 'AUTH_003', message: '인증 필요' })
        }
        onSignIn={vi.fn()}
      />,
    )

    expect(
      screen.getByRole('button', { name: '로그인하기' }),
    ).toBeInTheDocument()
  })

  it('계약 위반은 재시도를 권하지 않는다', () => {
    render(
      <ErrorState
        error={new ApiContractError(200, 'codeNotSuccess')}
        onRetry={vi.fn()}
      />,
    )

    expect(
      screen.getByText('서비스를 일시적으로 이용할 수 없습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('화면이 판정한 문구가 있으면 그 문구를 우선 표시한다', () => {
    render(
      <ErrorState
        error={
          new ApiError({
            status: 404,
            code: 'STORE_001',
            message: '내부 메시지',
          })
        }
        message="찾을 수 없는 매장입니다."
      />,
    )

    expect(screen.getByText('찾을 수 없는 매장입니다.')).toBeInTheDocument()
  })
})
