import type { QueryClient } from '@tanstack/react-query'

/**
 * 플랫폼 운영자 인증이 있어야 볼 수 있는 query key 뿌리.
 *
 * 각 기능의 key 객체가 자기 뿌리를 여기서 가져간다. 목록을 따로 적어 두면
 * 기능이 뿌리를 바꿨을 때 세션 종료 정리에서 조용히 빠진다.
 */
export const PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS = {
  members: ['platform-operator', 'members'],
  memberSupportCases: ['platform-operator', 'member-support-cases'],
  auditEvents: ['platform-operator', 'audit-events'],
} as const

/**
 * 세션 경계에서 운영자 보호 데이터를 캐시에서 지운다.
 *
 * 일반 사용자 shell과 같은 이유이지만 여기서는 더 무겁다. 캐시에 남는 것이
 * 회원 최소 식별정보와 감사 사건이라, 같은 브라우저에서 다음 운영자가
 * 로그인했을 때 이전 운영자의 권한 범위 데이터가 먼저 그려진다.
 *
 * 먼저 취소하고 지운다. 지우기만 하면 세션이 끝나기 직전에 떠난 요청이
 * 나중에 도착해 캐시를 다시 채운다.
 */
export async function clearPlatformOperatorProtectedQueries(
  queryClient: QueryClient,
): Promise<void> {
  await Promise.all(
    Object.values(PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS).map(
      async (queryKey) => {
        await queryClient.cancelQueries({ queryKey })
        queryClient.removeQueries({ queryKey })
      },
    ),
  )
}
