import type { ApiClient } from '../../../shared/api/client'
import type { components } from '../../../shared/api/generated/member-support'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../shared/api/platformOperatorSession'

export type AccountType = components['schemas']['AccountType']
export type MemberStatus = components['schemas']['MemberStatus']
export type Member = components['schemas']['Member']
export type MemberPageData = components['schemas']['MemberPageData']
export type SupportCase = components['schemas']['SupportCase']
export type CasePageData = components['schemas']['CasePageData']
export type CaseType = components['schemas']['CaseType']
export type CaseStatus = components['schemas']['CaseStatus']
export type SanctionLevel = components['schemas']['SanctionLevel']
export type RestrictedFeature = components['schemas']['RestrictedFeature']
export type Sanction = components['schemas']['Sanction']
export type ActiveSanctionSummary =
  components['schemas']['ActiveSanctionSummary']

/**
 * 회원 목록 조회 조건.
 *
 * 계약이 허용하는 필터만 있다. 시안의 "ID 또는 이름 검색" 자유형 입력은
 * 계약에 없어서 넣지 않았다. 이름·이메일은 응답에도 없다. 이 화면은
 * 최소 식별정보만 다루는 조회다.
 */
export interface MemberListQuery {
  accountType?: AccountType
  status?: MemberStatus
  joinedFrom?: string
  joinedTo?: string
  page: number
  size: number
}

/**
 * query key.
 *
 * 뿌리를 `platformOperatorSession`에서 가져온다. 여기서 새로 적으면 세션이
 * 끝날 때 이 캐시만 남아 다음 운영자에게 이전 조회 결과가 보인다.
 *
 * 조건 객체를 마지막 칸에 둔다. 페이지만 바뀐 재조회인지 판정하는 공통 규칙이
 * 그 위치를 본다.
 */
export const memberQueryKeys = {
  list: (query: MemberListQuery) =>
    [...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.members, query] as const,
  detail: (accountType: AccountType, accountId: string) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.members,
      accountType,
      accountId,
    ] as const,
}

export const supportCaseQueryKeys = {
  list: (query: SupportCaseListQuery) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.memberSupportCases,
      query,
    ] as const,
  detail: (caseId: string) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.memberSupportCases,
      caseId,
    ] as const,
}

export interface SupportCaseListQuery {
  caseType?: CaseType
  status?: CaseStatus
  page: number
  size: number
}

export async function fetchMembers(
  apiClient: ApiClient,
  query: MemberListQuery,
  signal?: AbortSignal,
): Promise<MemberPageData> {
  const response = await apiClient('/api/v1/platform-operators/members', {
    method: 'get',
    query: {
      accountType: query.accountType,
      status: query.status,
      joinedFrom: query.joinedFrom,
      joinedTo: query.joinedTo,
      page: query.page,
      size: query.size,
    },
    signal,
  })
  return response.data
}

/**
 * 회원 상세.
 *
 * 404를 다른 계정 유형의 존재 추측에 쓰지 않는다. 서버가 권한 범위 밖의
 * 대상을 404로 감추기 때문에, "없음"과 "볼 수 없음"을 화면이 구분해 말하면
 * 존재 여부가 새어 나간다.
 */
export async function fetchMember(
  apiClient: ApiClient,
  accountType: AccountType,
  accountId: string,
  signal?: AbortSignal,
): Promise<Member> {
  const response = await apiClient(
    '/api/v1/platform-operators/members/{accountType}/{accountId}',
    {
      method: 'get',
      pathParams: { accountType, accountId },
      signal,
    },
  )
  return response.data
}

export async function fetchSupportCases(
  apiClient: ApiClient,
  query: SupportCaseListQuery,
  signal?: AbortSignal,
): Promise<CasePageData> {
  const response = await apiClient(
    '/api/v1/platform-operators/member-support-cases',
    {
      method: 'get',
      query: {
        caseType: query.caseType,
        status: query.status,
        page: query.page,
        size: query.size,
      },
      signal,
    },
  )
  return response.data
}

export async function fetchSupportCase(
  apiClient: ApiClient,
  caseId: string,
  signal?: AbortSignal,
): Promise<SupportCase> {
  const response = await apiClient(
    '/api/v1/platform-operators/member-support-cases/{caseId}',
    {
      method: 'get',
      pathParams: { caseId },
      signal,
    },
  )
  return response.data
}

/**
 * 사건 자기 배정.
 *
 * `If-Match`에 현재 사건 version을 보낸다. 다른 운영자가 먼저 배정했으면
 * 서버가 409로 거절하고, 화면은 최신 상세를 다시 읽어야 한다.
 * 재인증은 요구하지 않는다.
 */
export async function assignSupportCase(
  apiClient: ApiClient,
  caseId: string,
  version: number,
): Promise<void> {
  await apiClient(
    '/api/v1/platform-operators/member-support-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { caseId },
      ifMatch: version,
    },
  )
}

export type CaseDecisionRequest = components['schemas']['CaseDecisionRequest']
export type SanctionRequest = components['schemas']['SanctionRequest']
export type AdditionalApprovalRequest =
  components['schemas']['AdditionalApprovalRequest']

/**
 * 사건 결정.
 *
 * 고위험 명령이라 version·재인증 승인·멱등 키를 모두 요구한다.
 * 세 값 중 하나라도 화면이 지어내면 안 된다. 재인증 승인은 운영자가 비밀번호를
 * 다시 입력해 받은 것이고, 멱등 키는 이 시도를 식별한다.
 */
export async function decideSupportCase(
  apiClient: ApiClient,
  input: {
    caseId: string
    version: number
    reauthenticationApproval: string
    idempotencyKey: string
    body: CaseDecisionRequest
  },
): Promise<void> {
  await apiClient(
    '/api/v1/platform-operators/member-support-cases/{caseId}/decisions',
    {
      method: 'post',
      pathParams: { caseId: input.caseId },
      body: input.body,
      ifMatch: input.version,
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
}

export async function createMemberSanction(
  apiClient: ApiClient,
  input: {
    accountType: AccountType
    accountId: string
    supportVersion: number
    reauthenticationApproval: string
    idempotencyKey: string
    body: SanctionRequest
  },
): Promise<Sanction> {
  const response = await apiClient(
    '/api/v1/platform-operators/members/{accountType}/{accountId}/sanctions',
    {
      method: 'post',
      pathParams: {
        accountType: input.accountType,
        accountId: input.accountId,
      },
      body: input.body,
      ifMatch: input.supportVersion,
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}

/**
 * 영구 정지 추가 승인.
 *
 * 제안한 운영자와 다른 슈퍼관리자가 승인해야 확정된다. 화면이 한 사람의
 * 클릭으로 확정된 것처럼 보이게 하지 않는다.
 */
export async function approvePermanentSanction(
  apiClient: ApiClient,
  input: {
    sanctionId: string
    version: number
    reauthenticationApproval: string
    idempotencyKey: string
    body: AdditionalApprovalRequest
  },
): Promise<Sanction> {
  const response = await apiClient(
    '/api/v1/platform-operators/member-sanctions/{sanctionId}/additional-approvals',
    {
      method: 'post',
      pathParams: { sanctionId: input.sanctionId },
      body: input.body,
      ifMatch: input.version,
      adminReauthentication: input.reauthenticationApproval,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}
