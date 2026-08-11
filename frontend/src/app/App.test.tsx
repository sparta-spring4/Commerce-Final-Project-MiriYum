import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, test } from 'vitest'
import { catalogHandlers } from '../features/store-search/test/handlers'
import { server } from '../test/msw/server'
import App from './App'

function renderAt(path: string) {
  window.history.pushState({}, '', path)
  render(<App />)
}

beforeEach(() => {
  // 홈은 매장 카테고리 catalog를 부른다. 등록하지 않으면 MSW가 실패로 잡는다.
  server.use(...catalogHandlers)
})

describe('앱 셸', () => {
  test('홈이 매장 검색 진입점 역할을 한다', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('미리냠')
    expect(screen.getByRole('form', { name: '매장 검색 조건' })).toBeInTheDocument()
  })

  test('홈에 큐레이션·추천 목록을 만들지 않는다', () => {
    // 1차 MVP 계약에 큐레이션 기준이 없다. 이력 추천은 2차 MVP다.
    renderAt('/')

    for (const label of ['오늘의 추천', '추천 맛집', '인기 매장']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
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
