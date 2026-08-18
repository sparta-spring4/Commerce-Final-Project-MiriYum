import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'

// vitest globals를 켜지 않아 RTL 자동 cleanup이 등록되지 않는다.
// 명시적으로 붙이지 않으면 이전 테스트의 DOM이 남아 조회가 중복 매칭된다.
afterEach(cleanup)

// 매장 운영자 셸이 현재 매장 선택을 sessionStorage에 보존한다.
// 지우지 않으면 앞선 테스트의 선택이 다음 테스트의 초기 상태로 넘어온다.
afterEach(() => {
  window.sessionStorage.clear()
})

// 브라우저 mocking은 쓰지 않는다. 테스트에서 msw/node만 사용한다.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
