import type { ApiClient } from '../../../shared/api/client'
import type { components } from '../../../shared/api/generated/platform-operator-authorization'

export type AdminCommandPurpose = components['schemas']['AdminCommandPurpose']
export type AdminTargetType = components['schemas']['AdminTargetType']
export type ReauthenticationApprovalData =
  components['schemas']['ReauthenticationApprovalData']

/**
 * 고위험 명령용 재인증 승인을 발급받는다.
 *
 * 승인값은 목적·대상·현재 세션에 결속된 5분짜리 일회용이다. 그래서 이 함수의
 * 결과를 모듈이나 storage에 캐시하지 않는다. 명령 한 번에 승인 한 번이다.
 *
 * 승인값을 재사용하려 하면 서버가 거절한다. 화면이 "아까 인증했으니 넘어가자"고
 * 판단하면, 사용자는 성공했다고 믿는데 명령은 실패한 상태가 된다.
 *
 * 응답의 `approval`은 비밀값이다. 로그·analytics·URL에 남기지 않는다.
 */
export async function createReauthenticationApproval(
  apiClient: ApiClient,
  input: {
    currentPassword: string
    purpose: AdminCommandPurpose
    targetType: AdminTargetType
    targetId: string
    signal?: AbortSignal
  },
): Promise<ReauthenticationApprovalData> {
  const response = await apiClient(
    '/api/v1/platform-operators/reauthentication-approvals',
    {
      method: 'post',
      body: {
        currentPassword: input.currentPassword,
        purpose: input.purpose,
        targetType: input.targetType,
        targetId: input.targetId,
      },
      signal: input.signal,
    },
  )
  return response.data
}

/** 계정 유형을 재인증 대상 유형으로 옮긴다. 계약의 두 enum이 이름이 다르다. */
export function accountTargetType(
  accountType: 'CONSUMER' | 'STORE_OPERATOR',
): AdminTargetType {
  return accountType === 'CONSUMER'
    ? 'CONSUMER_ACCOUNT'
    : 'STORE_OPERATOR_ACCOUNT'
}
