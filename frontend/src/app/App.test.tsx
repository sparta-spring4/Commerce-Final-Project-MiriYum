import { render, screen } from '@testing-library/react'
import { expect, test, describe } from 'vitest'
import App from './App'

function renderAt(path: string) {
  window.history.pushState({}, '', path)
  render(<App />)
}

describe('앱 셸', () => {
  test('홈에서 서비스 이름을 표시한다', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1, name: 'MiriYum' })).toBeInTheDocument()
  })

  test('공개 shell의 네비게이션에 뒤 단계 기능 항목이 없다', () => {
    renderAt('/')

    const nav = screen.getByRole('navigation', { name: '주 메뉴' })

    for (const label of ['웨이팅', '결제', '리뷰', '채팅', '알림']) {
      expect(nav).not.toHaveTextContent(label)
    }
  })

  test('없는 경로는 404 화면을 보여 준다', () => {
    renderAt('/이런-경로는-없다')

    expect(
      screen.getByRole('heading', { level: 1, name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument()
  })

  test('권한 없음은 404와 다른 화면이다', () => {
    renderAt('/forbidden')

    expect(
      screen.getByRole('heading', { level: 1, name: '접근할 수 없습니다' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('페이지를 찾을 수 없습니다')).not.toBeInTheDocument()
  })
})
