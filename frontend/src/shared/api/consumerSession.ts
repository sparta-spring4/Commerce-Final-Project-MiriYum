import type { QueryClient } from '@tanstack/react-query'

/**
 * 일반 사용자 인증이 있어야 볼 수 있는 query key 뿌리.
 *
 * 각 기능의 key 객체가 자기 뿌리를 여기서 가져간다. 목록을 따로 적어 두면
 * 기능이 뿌리를 바꿨을 때 세션 종료 정리에서 조용히 빠진다.
 *
 * 예약·픽업 뿌리에는 공개 가용성 조회도 함께 매달려 있다. 함께 지워도
 * 다음 조회에서 다시 받아 오면 그만이므로 뿌리 단위로 지운다.
 */
export const CONSUMER_PROTECTED_QUERY_ROOTS = {
  account: ['consumer-account'],
  reservations: ['reservations'],
  pickupReservations: ['pickup-reservations'],
} as const

/**
 * 세션 경계에서 일반 사용자 보호 데이터를 캐시에서 지운다.
 *
 * query key에 계정 식별자가 없다. 지우지 않으면 같은 탭에서 A가 로그아웃한 뒤
 * B가 로그인해 같은 화면에 들어왔을 때 React Query가 A의 프로필·예약을
 * 캐시에서 먼저 그리고 나서 다시 조회한다. 그 사이가 계정 간 개인정보 노출이다.
 *
 * 먼저 취소하고 지운다. 지우기만 하면 세션이 끝나기 직전에 떠난 요청이
 * 나중에 도착해 캐시를 A의 데이터로 다시 채운다. 보호 query는 모두 `signal`을
 * 받아 두었으므로 취소가 실제로 요청을 끊는다.
 */
export async function clearConsumerProtectedQueries(
  queryClient: QueryClient,
): Promise<void> {
  await Promise.all(
    Object.values(CONSUMER_PROTECTED_QUERY_ROOTS).map(async (queryKey) => {
      await queryClient.cancelQueries({ queryKey })
      queryClient.removeQueries({ queryKey })
    }),
  )
}
