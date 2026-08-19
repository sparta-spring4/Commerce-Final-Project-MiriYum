import type {
  AdminStoreDetail,
  StoreSanctionImpactPreview,
  AdminStorePage,
  AdminStoreSummary,
  StoreSanction,
  StoreSanctionCaseDetail,
} from '../api/storeAdminApi'

/**
 * 매장 관리 화면 테스트용 fixture.
 *
 * **테스트 전용이다.** 프로덕션 API 모듈(`api/storeAdminApi.ts`)에는 고정
 * 데이터를 두지 않는다. 화면이 이 값을 보는 경로는 MSW를 지나는 테스트뿐이다.
 *
 * 타입을 생성 계약에서 가져온다. 계약이 바뀌면 fixture가 먼저 컴파일에서
 * 깨지므로, 화면과 함께 조용히 낡지 않는다.
 */

export function adminStoreSummary(
  overrides: Partial<AdminStoreSummary> = {},
): AdminStoreSummary {
  return {
    storeId: 4001,
    name: '미리얌 강남점',
    storeOperatorAccountId: 9001,
    operationStatus: 'OPEN',
    reservationEnabled: true,
    menuHoldEnabled: true,
    pickupEnabled: false,
    enforcementVersion: 7,
    activeSanctionTypes: [],
    ...overrides,
  }
}

export function adminStorePage(
  overrides: Partial<AdminStorePage> = {},
): AdminStorePage {
  const content = overrides.content ?? [adminStoreSummary()]
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...overrides,
  }
}

export function adminStoreDetail(
  overrides: Partial<AdminStoreDetail> = {},
): AdminStoreDetail {
  return {
    ...adminStoreSummary(),
    createdAt: '2026-01-04T02:00:00Z',
    openCaseIds: ['case-7001'],
    ...overrides,
  }
}

export function storeSanction(
  overrides: Partial<StoreSanction> = {},
): StoreSanction {
  return {
    sanctionId: 5001,
    caseId: 'case-7001',
    storeId: 4001,
    type: 'FEATURE_RESTRICTION',
    status: 'ACTIVE',
    restrictedFeatures: ['RESERVATION'],
    sanctionVersion: 2,
    storeEnforcementVersion: 7,
    createdAt: '2026-08-17T04:00:00Z',
    ...overrides,
  }
}

export function storeSanctionCaseDetail(
  overrides: Partial<StoreSanctionCaseDetail> = {},
): StoreSanctionCaseDetail {
  return {
    caseId: 'case-7001',
    storeId: 4001,
    violationType: 'NO_SHOW_ABUSE',
    policyVersion: 'POLICY_2026_02',
    status: 'ASSIGNED',
    caseVersion: 3,
    createdBy: 9001,
    assignedOperatorId: null,
    createdAt: '2026-08-16T01:00:00Z',
    evidenceReferences: ['audit-9001'],
    sanctions: [],
    ...overrides,
  }
}

export function storeSanctionImpactPreview(
  overrides: Partial<StoreSanctionImpactPreview> = {},
): StoreSanctionImpactPreview {
  return {
    previewId: 3001,
    caseId: 'case-7001',
    storeId: 4001,
    caseVersion: 3,
    storeEnforcementVersion: 7,
    confirmedReservationCount: 12,
    activeWaitingTeamCount: 4,
    confirmedPickupCount: 2,
    unsettledPaymentCount: 1,
    digest: 'digest-abc',
    // 만료를 테스트가 조작할 수 있게 넉넉히 미래로 둔다.
    expiresAt: '2099-01-01T00:00:00Z',
    ...overrides,
  }
}
