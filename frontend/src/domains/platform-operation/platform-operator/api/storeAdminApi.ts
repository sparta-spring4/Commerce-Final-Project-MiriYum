import type { ApiClient } from '../../../../shared/api/client'
import type { components } from '../../../../shared/api/generated/admin-store'
import { PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS } from '../../../../app/shells/platform-operator/querySession'

/**
 * 매장 조회·제재 사건 API 경계.
 *
 * 화면은 이 모듈만 호출한다. 응답 타입은 전부 생성 타입에서 가져오며 손으로
 * 다시 선언하지 않는다.
 *
 * 이 계약의 헤더 구성은 세 갈래다. 목록·상세는 조회 사유 코드만, 사건 상세와
 * 미리보기는 사건 ID·version·사유를, 제재 명령은 거기에 멱등 키와 재인증까지
 * 요구한다. 세 갈래를 함수 시그니처로 드러내 호출부가 헷갈리지 않게 한다.
 */

export type StoreOperationStatus = components['schemas']['StoreOperationStatus']
export type StoreSanctionCaseStatus =
  components['schemas']['StoreSanctionCaseStatus']
export type StoreSanctionType = components['schemas']['StoreSanctionType']
export type StoreSanctionStatus = components['schemas']['StoreSanctionStatus']
export type StoreRestrictedFeature = components['schemas']['RestrictedFeature']

export type AdminStoreSummary = components['schemas']['AdminStoreSummaryResponse']
export type AdminStoreDetail = components['schemas']['AdminStoreDetailResponse']
export type AdminStorePage = components['schemas']['AdminStorePageResponse']
export type StoreSanctionCase = components['schemas']['StoreSanctionCaseResponse']
export type StoreSanctionCaseDetail =
  components['schemas']['StoreSanctionCaseDetailResponse']
export type StoreSanction = components['schemas']['StoreSanctionResponse']
export type SanctionShape = components['schemas']['SanctionShape']
export type StoreSanctionImpactPreview =
  components['schemas']['StoreSanctionImpactPreviewResponse']
export type ImpactConfirmation = components['schemas']['ImpactConfirmation']
export type StoreSanctionCaseCreateRequest =
  components['schemas']['StoreSanctionCaseCreateRequest']

/**
 * 사건 맥락. 사건 상세·미리보기·제재 명령이 공통으로 요구한다.
 * 값은 화면이 만들지 않고 운영자가 배정받은 사건에서 온다.
 */
export interface StoreCaseContext {
  caseId: string
  caseVersion: number
  reasonCode: string
}

export interface StoreListQuery {
  keyword?: string
  operationStatus?: StoreOperationStatus
  page: number
  size: number
}

export const storeQueryKeys = {
  list: (reasonCode: string, query: StoreListQuery) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.stores,
      // 사유 코드가 다르면 다른 조회다. 감사 원장에 별도로 기록된다.
      reasonCode,
      query,
    ] as const,
  detail: (reasonCode: string, storeId: number) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.stores,
      reasonCode,
      storeId,
    ] as const,
  sanctionCase: (context: StoreCaseContext, storeId: number) =>
    [
      ...PLATFORM_OPERATOR_PROTECTED_QUERY_ROOTS.stores,
      storeId,
      'sanction-cases',
      context.caseId,
      context.caseVersion,
      context.reasonCode,
    ] as const,
}

export async function fetchStores(
  apiClient: ApiClient,
  reasonCode: string,
  query: StoreListQuery,
  signal?: AbortSignal,
): Promise<AdminStorePage> {
  const response = await apiClient('/api/v1/platform-operators/stores', {
    method: 'get',
    adminReasonCode: reasonCode,
    query: {
      keyword: query.keyword,
      operationStatus: query.operationStatus,
      page: query.page,
      size: query.size,
    },
    signal,
  })
  return response.data
}

export async function fetchStore(
  apiClient: ApiClient,
  reasonCode: string,
  storeId: number,
  signal?: AbortSignal,
): Promise<AdminStoreDetail> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}',
    {
      method: 'get',
      pathParams: { storeId },
      adminReasonCode: reasonCode,
      signal,
    },
  )
  return response.data
}

/**
 * 제재 사건 생성.
 *
 * 생성 결과는 `SUBMITTED`다. 생성자에게 자동 배정되지 않으므로 화면이
 * "담당자로 지정됐다"고 표현하면 안 된다. 배정은 별도 명령이다.
 */
export async function createStoreSanctionCase(
  apiClient: ApiClient,
  input: {
    storeId: number
    reasonCode: string
    idempotencyKey: string
    body: StoreSanctionCaseCreateRequest
  },
): Promise<StoreSanctionCase> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases',
    {
      method: 'post',
      pathParams: { storeId: input.storeId },
      body: input.body,
      adminReasonCode: input.reasonCode,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}

/**
 * 사건 자기 배정.
 *
 * `expectedCaseVersion`을 본문으로 보낸다. 다른 운영자가 먼저 배정했으면
 * 서버가 409로 거절하고, 화면은 최신 사건을 다시 읽어야 한다.
 */
export async function assignStoreSanctionCase(
  apiClient: ApiClient,
  input: {
    storeId: number
    caseId: string
    reasonCode: string
    idempotencyKey: string
    expectedCaseVersion: number
  },
): Promise<StoreSanctionCase> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { storeId: input.storeId, caseId: input.caseId },
      body: { expectedCaseVersion: input.expectedCaseVersion },
      adminReasonCode: input.reasonCode,
      idempotencyKey: input.idempotencyKey,
    },
  )
  return response.data
}

export async function fetchStoreSanctionCase(
  apiClient: ApiClient,
  storeId: number,
  context: StoreCaseContext,
  signal?: AbortSignal,
): Promise<StoreSanctionCaseDetail> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}',
    {
      method: 'get',
      pathParams: { storeId, caseId: context.caseId },
      adminAuditContext: {
        caseId: context.caseId,
        caseVersion: context.caseVersion,
        reasonCode: context.reasonCode,
      },
      signal,
    },
  )
  return response.data
}

/**
 * 거래 영향 미리보기.
 *
 * **미리보기 생성이 취소·환불 실행을 뜻하지 않는다.** 제재를 적용했을 때
 * 영향을 받을 건수를 계산해 보여 줄 뿐이다.
 *
 * 결과의 `digest`와 version들을 제재 실행 때 `impactConfirmation`으로 되돌려
 * 보낸다. 그 사이 상태가 바뀌면 서버가 불일치로 거절하므로, 화면은 새
 * 미리보기를 만들게 안내해야 한다.
 */
export async function createStoreSanctionImpactPreview(
  apiClient: ApiClient,
  input: {
    storeId: number
    context: StoreCaseContext
    body: SanctionShape
  },
): Promise<StoreSanctionImpactPreview> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/impact-previews',
    {
      method: 'post',
      pathParams: { storeId: input.storeId, caseId: input.context.caseId },
      body: input.body,
      adminAuditContext: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
        reasonCode: input.context.reasonCode,
      },
    },
  )
  return response.data
}

/**
 * 제재 제안 또는 적용.
 *
 * 재인증은 계약상 optional이다. 고위험 제재(영구 퇴점·전체 기능 제한)에서만
 * 서버가 요구하므로 화면이 그 판단을 하고 값을 넘긴다.
 */
export async function createStoreSanction(
  apiClient: ApiClient,
  input: {
    storeId: number
    context: StoreCaseContext
    idempotencyKey: string
    reauthenticationApproval?: string
    body: components['schemas']['StoreSanctionCreateRequest']
  },
): Promise<StoreSanction> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions',
    {
      method: 'post',
      pathParams: { storeId: input.storeId, caseId: input.context.caseId },
      body: input.body,
      adminAuditContext: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
        reasonCode: input.context.reasonCode,
      },
      idempotencyKey: input.idempotencyKey,
      adminReauthentication: input.reauthenticationApproval,
    },
  )
  return response.data
}

/** 고위험 제재 추가 승인. 재인증이 필수다. */
export async function approveStoreSanction(
  apiClient: ApiClient,
  input: {
    storeId: number
    context: StoreCaseContext
    sanctionId: number
    idempotencyKey: string
    reauthenticationApproval: string
    body: components['schemas']['StoreSanctionApprovalRequest']
  },
): Promise<StoreSanction> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/approvals',
    {
      method: 'post',
      pathParams: {
        storeId: input.storeId,
        caseId: input.context.caseId,
        sanctionId: input.sanctionId,
      },
      body: input.body,
      adminAuditContext: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
        reasonCode: input.context.reasonCode,
      },
      idempotencyKey: input.idempotencyKey,
      adminReauthentication: input.reauthenticationApproval,
    },
  )
  return response.data
}

/** 비영구 제재 해제. 영구 퇴점은 이 경로로 되돌릴 수 없다. */
export async function releaseStoreSanction(
  apiClient: ApiClient,
  input: {
    storeId: number
    context: StoreCaseContext
    sanctionId: number
    idempotencyKey: string
    reauthenticationApproval: string
    body: components['schemas']['StoreSanctionReleaseRequest']
  },
): Promise<StoreSanction> {
  const response = await apiClient(
    '/api/v1/platform-operators/stores/{storeId}/sanction-cases/{caseId}/sanctions/{sanctionId}/releases',
    {
      method: 'post',
      pathParams: {
        storeId: input.storeId,
        caseId: input.context.caseId,
        sanctionId: input.sanctionId,
      },
      body: input.body,
      adminAuditContext: {
        caseId: input.context.caseId,
        caseVersion: input.context.caseVersion,
        reasonCode: input.context.reasonCode,
      },
      idempotencyKey: input.idempotencyKey,
      adminReauthentication: input.reauthenticationApproval,
    },
  )
  return response.data
}
