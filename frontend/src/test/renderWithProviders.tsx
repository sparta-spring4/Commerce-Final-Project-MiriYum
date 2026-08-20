import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'

/**
 * 테스트용 query client.
 *
 * 재시도를 끈다. production 정책은 네트워크 실패를 두 번 재시도하는데,
 * 테스트에서 그대로 두면 오류 화면 검증이 재시도 시간만큼 느려지고 불안정해진다.
 */
function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

interface Options {
  /** 초기 주소. search params를 포함할 수 있다. */
  route?: string
  /** route 패턴. `:storeId` 같은 경로 변수를 쓰는 화면에 필요하다. */
  path?: string
}

/**
 * 화면을 router와 query provider 안에서 렌더한다.
 *
 * 각 테스트가 새 QueryClient를 받으므로 이전 테스트의 캐시가 넘어오지 않는다.
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/', path }: Options = {},
) {
  const queryClient = createTestQueryClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          {path === undefined ? (
            children
          ) : (
            <Routes>
              <Route path={path} element={children} />
            </Routes>
          )}
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  return render(ui, { wrapper: Wrapper })
}
