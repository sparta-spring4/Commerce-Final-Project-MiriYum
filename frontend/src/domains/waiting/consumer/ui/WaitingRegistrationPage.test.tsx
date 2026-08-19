import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/renderWithProviders'
import type {
  WaitingReceptionState,
  WaitingRegistrationNotice,
  WaitingRegistrationProgress,
  WaitingRegistrationResult,
} from '../model/registrationView'
import { WaitingRegistrationPage } from './WaitingRegistrationPage'

const STORE_ID = '12'

interface Overrides {
  reception?: WaitingReceptionState
  progress?: WaitingRegistrationProgress
  partySize?: number
  partySizeError?: string | null
  notice?: WaitingRegistrationNotice | null
  result?: WaitingRegistrationResult | null
  storeName?: string
}

/**
 * 화면은 상태를 스스로 만들지 않는다. 각 테스트가 표현할 상태를 그대로 넣고
 * 콜백 호출만 관찰한다. 실제 위치 측정과 API 호출은 이 화면 밖에 있다.
 */
function renderPage(overrides: Overrides = {}) {
  const onPartySizeChange = vi.fn()
  const onSubmit = vi.fn()
  const onRetryLocation = vi.fn()

  const view = renderWithProviders(
    <WaitingRegistrationPage
      storeId={STORE_ID}
      storeName={overrides.storeName ?? '미리얌 본점'}
      reception={overrides.reception ?? 'accepting'}
      progress={overrides.progress ?? 'idle'}
      partySize={overrides.partySize ?? 2}
      partySizeError={overrides.partySizeError ?? null}
      notice={overrides.notice ?? null}
      result={overrides.result ?? null}
      onPartySizeChange={onPartySizeChange}
      onSubmit={onSubmit}
      onRetryLocation={onRetryLocation}
    />,
    { route: `/stores/${STORE_ID}/waiting` },
  )

  return { ...view, onPartySizeChange, onSubmit, onRetryLocation }
}

function submitButton() {
  return screen.getByRole('button', { name: '현재 위치 확인 후 웨이팅 등록' })
}

describe('웨이팅 등록 화면 - 접수 상태', () => {
  it('접수 가능하면 인원 입력과 등록 버튼을 보여 준다', () => {
    renderPage()

    expect(
      screen.getByRole('heading', { level: 1, name: '웨이팅 등록' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('방문 인원')).toHaveValue(2)
    expect(submitButton()).toBeEnabled()
  })

  it('매장 이름과 매장 상세로 돌아가는 링크를 보여 준다', () => {
    renderPage({ storeName: '미리얌 성수점' })

    expect(screen.getByText(/미리얌 성수점/)).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '매장 상세로 돌아가기' }),
    ).toHaveAttribute('href', `/stores/${STORE_ID}`)
  })

  it('접수 불가면 등록 폼을 두지 않고 안내만 보여 준다', () => {
    renderPage({ reception: 'closed' })

    expect(screen.getByText('지금은 웨이팅을 받지 않습니다.')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '현재 위치 확인 후 웨이팅 등록' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByLabelText('방문 인원')).not.toBeInTheDocument()
  })

  it('접수 가능 여부를 확인하는 중이면 로딩을 보여 준다', () => {
    renderPage({ reception: 'checking' })

    expect(
      screen.getByText('웨이팅 접수 가능 여부를 확인하는 중입니다.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '현재 위치 확인 후 웨이팅 등록' }),
    ).not.toBeInTheDocument()
  })
})

describe('웨이팅 등록 화면 - 인원수', () => {
  it('버튼으로 인원을 늘리고 줄인다', () => {
    const { onPartySizeChange } = renderPage({ partySize: 3 })

    fireEvent.click(screen.getByRole('button', { name: '인원 늘리기' }))
    expect(onPartySizeChange).toHaveBeenLastCalledWith(4)

    fireEvent.click(screen.getByRole('button', { name: '인원 줄이기' }))
    expect(onPartySizeChange).toHaveBeenLastCalledWith(2)
  })

  it('입력으로 인원을 직접 적는다', () => {
    const { onPartySizeChange } = renderPage()

    fireEvent.change(screen.getByLabelText('방문 인원'), {
      target: { value: '6' },
    })

    expect(onPartySizeChange).toHaveBeenCalledWith(6)
  })

  it('한 명 밑으로는 줄이지 못한다', () => {
    renderPage({ partySize: 1 })

    expect(screen.getByRole('button', { name: '인원 줄이기' })).toBeDisabled()
  })

  it('인원이 0명이면 등록하지 않고 오류를 보여 준다', () => {
    const { onSubmit } = renderPage({ partySize: 0 })

    fireEvent.click(submitButton())

    expect(
      screen.getByText('방문 인원을 한 명 이상 입력해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('방문 인원')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('서버가 준 인원 오류를 그대로 보여 준다', () => {
    renderPage({ partySizeError: '이 매장이 허용하는 인원 범위가 아닙니다.' })

    expect(
      screen.getByText('이 매장이 허용하는 인원 범위가 아닙니다.'),
    ).toBeInTheDocument()
  })

  it('처음 들어왔을 때는 오류를 먼저 띄우지 않는다', () => {
    renderPage({ partySize: 0 })

    expect(
      screen.queryByText('방문 인원을 한 명 이상 입력해 주세요.'),
    ).not.toBeInTheDocument()
  })
})

describe('웨이팅 등록 화면 - 진행 상태', () => {
  it('위치를 확인하는 중이면 진행 문구를 알리고 버튼을 잠근다', () => {
    renderPage({ progress: 'locating' })

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('현재 위치를 확인하는 중입니다.')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(submitButton()).toBeDisabled()
    expect(submitButton()).toHaveAttribute('aria-busy', 'true')
  })

  it('등록하는 중이면 위치 확인과 구분해서 알린다', () => {
    renderPage({ progress: 'registering' })

    expect(screen.getByRole('status')).toHaveTextContent(
      '웨이팅을 등록하는 중입니다.',
    )
    expect(submitButton()).toBeDisabled()
  })

  it('진행 중에는 인원을 바꾸지 못한다', () => {
    renderPage({ progress: 'registering' })

    expect(screen.getByLabelText('방문 인원')).toBeDisabled()
    expect(screen.getByRole('button', { name: '인원 늘리기' })).toBeDisabled()
  })

  /*
   * 버튼 비활성화만으로는 부족하다. 폼은 Enter로도 제출되고, 빠른 연속 입력이
   * 비활성화 사이를 빠져나가면 같은 팀이 두 번 등록된다.
   */
  it('진행 중 폼을 다시 제출해도 등록을 다시 요청하지 않는다', () => {
    const { onSubmit } = renderPage({ progress: 'locating' })

    fireEvent.submit(screen.getByRole('form', { name: '웨이팅 등록' }))

    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('정상 상태에서 제출하면 등록을 한 번 요청한다', () => {
    const { onSubmit } = renderPage({ partySize: 4 })

    fireEvent.click(submitButton())

    expect(onSubmit).toHaveBeenCalledTimes(1)
  })
})

describe('웨이팅 등록 화면 - 실패 안내', () => {
  const locationCases: Array<{
    notice: WaitingRegistrationNotice
    title: string
  }> = [
    { notice: { code: 'PERMISSION_DENIED' }, title: '위치 권한이 거부되었습니다.' },
    {
      notice: { code: 'POSITION_UNAVAILABLE' },
      title: '현재 위치를 확인하지 못했습니다.',
    },
    {
      notice: { code: 'OUTSIDE_RADIUS' },
      title: '매장에서 3km 넘게 떨어져 있습니다.',
    },
    {
      notice: { code: 'ACCURACY_INSUFFICIENT' },
      title: '위치 정확도가 부족합니다.',
    },
    {
      notice: { code: 'MEASUREMENT_STALE' },
      title: '확인한 위치가 오래되었습니다.',
    },
    {
      notice: { code: 'MANIPULATION_SUSPECTED' },
      title: '위치 정보를 신뢰할 수 없습니다.',
    },
    {
      notice: { code: 'LOCATION_PROOF_INVALID' },
      title: '위치 확인이 만료되었습니다.',
    },
  ]

  it.each(locationCases)(
    '$notice.code 사유를 구분해 알리고 재측정을 권한다',
    ({ notice, title }) => {
      const { onRetryLocation, onSubmit } = renderPage({ notice })

      expect(screen.getByRole('alert')).toHaveTextContent(title)

      fireEvent.click(screen.getByRole('button', { name: '현재 위치 다시 확인' }))

      expect(onRetryLocation).toHaveBeenCalledTimes(1)
      // 위치 판정 실패는 같은 요청을 다시 보내도 결과가 같다.
      expect(onSubmit).not.toHaveBeenCalled()
    },
  )

  it('일반 API 오류는 같은 요청을 다시 보내게 한다', () => {
    const { onSubmit, onRetryLocation } = renderPage({
      notice: { code: 'REQUEST_FAILED' },
    })

    expect(screen.getByRole('alert')).toHaveTextContent(
      '웨이팅을 등록하지 못했습니다.',
    )

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onRetryLocation).not.toHaveBeenCalled()
  })

  it('서버가 준 문구가 있으면 기본 설명 대신 그 문구를 보여 준다', () => {
    renderPage({
      notice: {
        code: 'REQUEST_FAILED',
        message: '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
      },
    })

    expect(
      screen.getByText('요청이 많습니다. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument()
  })

  it('실패 안내가 떠도 등록 성공으로 보이지 않는다', () => {
    renderPage({ notice: { code: 'LOCATION_PROOF_INVALID' } })

    expect(screen.queryByText('웨이팅을 등록했습니다.')).not.toBeInTheDocument()
    expect(screen.queryByText(/내 순번/)).not.toBeInTheDocument()
  })

  it('진행 중에는 재시도 버튼도 잠근다', () => {
    renderPage({ progress: 'locating', notice: { code: 'OUTSIDE_RADIUS' } })

    expect(
      screen.getByRole('button', { name: '현재 위치 다시 확인' }),
    ).toBeDisabled()
  })
})

describe('웨이팅 등록 화면 - 등록 성공', () => {
  it('대기 순번과 앞 팀 수를 보여 준다', () => {
    renderPage({
      progress: 'succeeded',
      partySize: 4,
      result: { queueSequence: 12, teamsAhead: 3 },
    })

    const success = screen.getByRole('status')
    expect(success).toHaveTextContent('웨이팅을 등록했습니다.')
    expect(success).toHaveTextContent('내 순번')
    expect(success).toHaveTextContent('12번')
    expect(success).toHaveTextContent('앞 팀')
    expect(success).toHaveTextContent('3팀')
    expect(success).toHaveTextContent('4명')
  })

  it('마이페이지에서 확인하는 이동 버튼을 제공한다', () => {
    renderPage({
      progress: 'succeeded',
      result: { queueSequence: 1, teamsAhead: 0 },
    })

    expect(
      screen.getByRole('link', { name: '마이페이지에서 확인' }),
    ).toHaveAttribute('href', '/mypage')
  })

  it('성공한 뒤에는 등록 폼을 다시 두지 않는다', () => {
    renderPage({
      progress: 'succeeded',
      result: { queueSequence: 1, teamsAhead: 0 },
    })

    expect(
      screen.queryByRole('button', { name: '현재 위치 확인 후 웨이팅 등록' }),
    ).not.toBeInTheDocument()
  })
})

describe('웨이팅 등록 화면 - 접근성', () => {
  it('인원 입력에 레이블과 도움말을 연결한다', () => {
    renderPage()

    const input = screen.getByLabelText('방문 인원')
    const describedBy = input.getAttribute('aria-describedby')

    expect(describedBy).not.toBeNull()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      '본인을 포함한 실제 방문 인원수를 입력해 주세요.',
    )
  })

  it('위치를 언제 쓰는지 등록 전에 알린다', () => {
    renderPage()

    expect(
      screen.getByText(/등록을 누를 때만 현재 위치를 확인합니다/),
    ).toBeInTheDocument()
  })

  it('진행 문구 영역을 처음부터 같은 자리에 둔다', () => {
    const { container } = renderPage()

    const live = container.querySelector('.waiting-register__progress')
    expect(live).not.toBeNull()
    expect(live).toHaveAttribute('aria-live', 'polite')
  })
})
