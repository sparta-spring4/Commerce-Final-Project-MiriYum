import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import type { ReactNode } from 'react'

/**
 * 인증 shell을 렌더하는 테스트용 query provider.
 *
 * `ConsumerAuthProvider`가 세션 경계에서 보호 query를 캐시에서 지우므로
 * QueryClient 없이는 렌더할 수 없다. 앱에서도 항상 provider 안에 있다.
 *
 * 렌더마다 새 client를 만든다. 하나를 공유하면 앞 테스트의 캐시가 넘어와
 * 계정 전환 회귀 테스트가 통과한 것처럼 보일 수 있다.
 */
export function TestQueryProvider({
  children,
  onReady,
}: {
  children: ReactNode
  /** 캐시를 직접 들여다봐야 하는 테스트가 client를 받아 간다. */
  onReady?: (queryClient: QueryClient) => void
}) {
  const [queryClient] = useState(() => {
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    onReady?.(client)
    return client
  })

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}
