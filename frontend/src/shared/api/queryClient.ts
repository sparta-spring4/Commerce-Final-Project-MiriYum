import { QueryClient } from '@tanstack/react-query'
import { isNetworkError } from './apiError'

const MAX_NETWORK_RETRIES = 2

/**
 * 재시도 정책은 서버 계약을 따른다.
 *
 * - 4xx는 재시도하지 않는다. 같은 요청을 다시 보내도 결과가 바뀌지 않고,
 *   429는 서버가 이미 거절한 것이라 클라이언트가 몰아치면 안 된다.
 * - 서버에 닿지 못한 실패만 재시도한다.
 * - 변경 요청(mutation)은 자동 재시도하지 않는다. 멱등 키를 유지해야 하는지
 *   새 요청이 필요한지는 화면이 서버 계약에 따라 판단한다.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) =>
          isNetworkError(error) && failureCount < MAX_NETWORK_RETRIES,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  })
}
