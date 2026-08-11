import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { AppErrorBoundary } from './AppErrorBoundary'

function Exploding(): never {
  throw new Error('렌더링 실패')
}

beforeEach(() => {
  // React가 잡힌 오류를 콘솔에 다시 찍는다. 테스트 출력만 조용하게 만든다.
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

test('렌더링 예외를 잡아 빈 화면으로 끝나지 않는다', () => {
  render(
    <AppErrorBoundary>
      <Exploding />
    </AppErrorBoundary>,
  )

  expect(
    screen.getByRole('heading', { level: 1, name: '화면을 표시하지 못했습니다' }),
  ).toBeInTheDocument()
})

test('오류 원문을 화면에 노출하지 않는다', () => {
  render(
    <AppErrorBoundary>
      <Exploding />
    </AppErrorBoundary>,
  )

  expect(screen.queryByText(/렌더링 실패/)).not.toBeInTheDocument()
})
