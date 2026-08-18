import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { CurrentStoreProvider } from '../CurrentStoreProvider'
import { StoreOperatorAuthProvider } from '../StoreOperatorAuthProvider'

/**
 * 운영자 보호 화면을 셸 provider 안에서 렌더한다.
 *
 * 화면이 `useStoreOperatorAuth`로 보호 client를 얻고 `useCurrentStore`로 현재
 * 매장을 채택하므로, 두 provider 없이 렌더하면 화면이 아니라 provider 오류를
 * 검증하게 된다.
 */
function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

/** 이동 결과를 확인할 때 쓰는 위치 표시기. */
export function LocationProbe() {
  const { pathname, search } = useLocation()
  return <p data-testid="location">{`${pathname}${search}`}</p>
}

interface Options {
  /** 초기 주소. `:storeId` 자리를 실제 값으로 채워 넘긴다. */
  route: string
  /** route 패턴. 경로 변수를 쓰는 화면에 필요하다. */
  path: string
  /** 이동 대상을 확인할 추가 route 패턴. */
  probePaths?: readonly string[]
}

export function renderOperator(
  ui: ReactElement,
  { route, path, probePaths = [] }: Options,
) {
  const queryClient = createTestQueryClient()

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <StoreOperatorAuthProvider>
            <CurrentStoreProvider>
              <Routes>
                <Route path={path} element={children} />
                {probePaths.map((probePath) => (
                  <Route
                    key={probePath}
                    path={probePath}
                    element={<LocationProbe />}
                  />
                ))}
              </Routes>
            </CurrentStoreProvider>
          </StoreOperatorAuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  return render(ui, { wrapper: Wrapper })
}
