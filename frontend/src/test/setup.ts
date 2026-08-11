import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw/server'

// vitest globals를 켜지 않아 RTL 자동 cleanup이 등록되지 않는다.
// 명시적으로 붙이지 않으면 이전 테스트의 DOM이 남아 조회가 중복 매칭된다.
afterEach(cleanup)

// 브라우저 mocking은 쓰지 않는다. 테스트에서 msw/node만 사용한다.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
