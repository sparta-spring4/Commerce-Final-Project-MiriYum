import type { ApiClient } from '../../../shared/api/client'
import type {
  components,
  operations,
} from '../../../shared/api/generated/platform-operator-management-audit'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../shared/api/platformOperatorSession'

/**
 * 운영자 계정 조회·관리 API 경계.
 *
 * 화면은 이 모듈만 호출하고 `apiClient`를 직접 쓰지 않는다. 연동 담당자가
 * 요청 형태를 조정할 때 화면을 건드리지 않아도 되게 하려는 분리다.
 *
 * 응답 타입을 손으로 다시 선언하지 않는다. 전부 생성 타입에서 가져온다.
 * 손으로 적으면 계약이 바뀌어도 typecheck가 통과해 화면이 조용히 어긋난다.
 */

export type OperatorAccountStatus = components['schemas']['OperatorAccountStatus']
export type OperatorRole = components['schemas']['OperatorRole']
export type OperatorPermission = components['schemas']['OperatorPermission']
export type OperatorAccountSummary =
  components['schemas']['OperatorAccountSummary']
export type OperatorAccountPage = components['schemas']['OperatorAccountPage']
export type OperatorAccountDetail =
  components['schemas']['OperatorAccountDetail']
export type CurrentOperatorData = components['schemas']['CurrentOperatorData']

/**
 * 부여할 수 있는 역할·권한은 조회 결과보다 좁다.
 *
 * 계약이 생성·권한 교체 요청에 `NonSuperAdminRole`·`NonCorePermission`만
 * 받는다. `SUPER_ADMIN`과 핵심 권한은 API로 부여할 수 없다는 뜻이고,
 * 이 제약을 화면이 우회해선 안 된다. 그래서 폼은 이 두 타입만 쓴다.
 */
export type GrantableRole = components['schemas']['NonSuperAdminRole']
export type GrantablePermission = components['schemas']['NonCorePermission']

export type AuditReason = components['schemas']['AuditReason']
export type AccountCreateRequest = components['schemas']['AccountCreateRequest']
export type AuthorityReplaceRequest =
  components['schemas']['AuthorityReplaceRequest']
export type SuspensionRequest = components['schemas']['SuspensionRequest']

/**
 * 계약이 허용하는 정렬만 담는다. 임의 문자열을 보내면 서버가 거절한다.
 * 생성 타입의 query 선언에서 그대로 가져와 손으로 나열하지 않는다.
 */
export type OperatorAccountSort = NonNullable<
  NonNullable<
    operations['searchPlatformOperatorAccounts']['parameters']['query']
  >['sort']
>

export interface OperatorAccountListQuery {
  status?: OperatorAccountStatus
  role?: OperatorRole
  query?: string
  page: number
  size: number
  sort?: OperatorAccountSort
}

/**
 * 관리 명령이 요구하는 사건 맥락.
 *
 * 생성·권한 교체·중지 모두 `X-Admin-Case-Id`와 `X-Admin-Case-Version`을
 * 요구한다. 감사 조회와 달리 사유 코드 헤더는 없고, 사유는 본문의
 * `reason`으로 들어간다. 값을 화면이 지어내지 않고 운영자에게 받는다.
 */
export interface OperatorCommandContext {
  caseId: string
  caseVersion: number
}

export const operatorAccountQueryKeys = {
  list: (query: OperatorAccountListQuery) =>
    [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.operatorAccounts, query] as const,
  detail: (operatorId: string) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.operatorAccounts,
      operatorId,
    ] as const,
  me: () => [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.currentOperator] as const,
}

/**
 * 현재 운영자와 중앙 유효 권한.
 *
 * 권한은 JWT claims가 아니라 서버가 RBAC 원장에서 읽은 snapshot이다.
 * `capabilities.ts`가 이 결과를 소비하도록 연결하는 것이 후속 작업이다.
 */
export async function fetchCurrentOperator(
  apiClient: ApiClient,
  signal?: AbortSignal,
): Promise<CurrentOperatorData> {
  const response = await apiClient('/api/v1/platform-operators/me', {
    method: 'get',
    signal,
  })
  return response.data
}

export async function fetchOperatorAccounts(
  apiClient: ApiClient,
  query: OperatorAccountListQuery,
  signal?: AbortSignal,
): Promise<OperatorAccountPage> {
  const response = await apiClient('/api/v1/platform-operators/accounts', {
    method: 'get',
    query: {
      status: query.status,
      role: query.role,
      query: query.query,
      page: query.page,
      size: query.size,
      sort: query.sort,
    },
    signal,
  })
  return response.data
}

export async function fetchOperatorAccount(
  apiClient: ApiClient,
  operatorId: string,
  signal?: AbortSignal,
): Promise<OperatorAccountDetail> {
  const response = await apiClient(
    '/api/v1/platform-operators/accounts/{operatorId}',
    {
      method: 'get',
      pathParams: { operatorId },
      signal,
    },
  )
  return response.data
}

/**
 * 운영자 계정 생성.
 *
 * 임시 비밀번호는 요청에만 담기고 응답에는 오지 않는다. 화면도 발급 결과로
 * 다시 보여 주지 않는다. 전달은 콘솔 밖의 절차다.
 */
export async function createOperatorAccount(
  apiClient: ApiClient,
  input: {
    context: OperatorCommandContext
    reauthenticationApproval: string
    idempotencyKey: string
    body: AccountCreateRequest
  },
) {
  const response = await apiClient('/api/v1/platform-operators/accounts', {
    method: 'post',
    body: input.body,
    adminCaseRef: {
      caseId: input.context.caseId,
      caseVersion: input.context.caseVersion,
    },
    adminReauthentication: input.reauthenticationApproval,
    idempotencyKey: input.idempotencyKey,
  })
  return response.data
}

/**
 * 역할·직접 권한 전체 교체.
 *
 * 부분 갱신이 아니라 전체 교체다. 보내지 않은 항목은 제거된다.
 * 화면이 "추가"처럼 보이게 만들면 운영자가 기존 권한을 지우는 줄 모른다.
 */
export async function replaceOperatorAuthority(
  apiClient: ApiClient,
  input: {
    operatorId: string
    context: OperatorCommandContext
    reauthenticationApproval: string
    idempotencyKey: string
    body: AuthorityReplaceRequest
  },
) {
  const response = await apiClient(
    '/api/v1/platform-operators/accounts/{operatorId}/authority',
    {
      method: 'put',
      pathParams: { operatorId: input.operatorId },
      body: input.body,
      adminCaseRef: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
      },
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}

export async function suspendOperatorAccount(
  apiClient: ApiClient,
  input: {
    operatorId: string
    context: OperatorCommandContext
    reauthenticationApproval: string
    idempotencyKey: string
    body: SuspensionRequest
  },
) {
  const response = await apiClient(
    '/api/v1/platform-operators/accounts/{operatorId}/suspension',
    {
      method: 'put',
      pathParams: { operatorId: input.operatorId },
      body: input.body,
      adminCaseRef: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
      },
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}
