import type { ApiClient } from '../../../../../shared/api/client'
import type { components } from '../../../../../shared/api/generated/platform-operator-capabilities'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../../../app/shells/platform-operator/querySession'

export type PlatformOperatorCapabilitiesData =
  components['schemas']['PlatformOperatorCapabilitiesData']

export const platformOperatorCapabilitiesQueryKey = [
  ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.currentCapabilities,
] as const

/**
 * 현재 중앙 authority version의 활성 역할과 최종 유효 권한만 조회한다.
 *
 * 계정 표시명·이메일·상태는 이 최소 권한 계약에 없으며 화면이 추정하지 않는다.
 */
export async function fetchPlatformOperatorCapabilities(
  apiClient: ApiClient,
  signal?: AbortSignal,
): Promise<PlatformOperatorCapabilitiesData> {
  const response = await apiClient('/api/v1/platform-operators/me', {
    method: 'get',
    signal,
  })
  return response.data
}
