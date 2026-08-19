import type {
  OperatorAccountDetail,
  OperatorAccountPage,
  OperatorAccountSummary,
} from '../api/operatorAccountApi'

/**
 * 운영자 계정 화면 테스트용 fixture.
 *
 * **테스트 전용이다.** 프로덕션 API 모듈(`api/operatorAccountApi.ts`)에는
 * 어떤 고정 데이터도 두지 않는다. 화면이 이 값을 보게 되는 경로는 MSW를
 * 통과하는 테스트뿐이다.
 *
 * 타입을 생성 계약에서 가져오므로, 계약이 바뀌면 fixture가 먼저 컴파일에서
 * 깨진다. 손으로 선언한 가짜 DTO였다면 화면과 함께 조용히 낡는다.
 *
 * 이메일은 서버가 마스킹한 형태를 흉내 낸다. 실제 마스킹 규칙은 서버가
 * 소유하므로 여기서 규칙을 재현하려 하지 않는다.
 */

export function operatorAccountSummary(
  overrides: Partial<OperatorAccountSummary> = {},
): OperatorAccountSummary {
  return {
    operatorId: 'op-1001',
    email: 'op***@miriyum.hq',
    displayName: '김운영',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    authorityVersion: 3,
    roles: ['MEMBER_SUPPORT_OPERATOR'],
    lastLoginAt: '2026-08-17T09:00:00Z',
    ...overrides,
  }
}

export function operatorAccountPage(
  overrides: Partial<OperatorAccountPage> = {},
): OperatorAccountPage {
  const content = overrides.content ?? [operatorAccountSummary()]
  return {
    content,
    page: {
      number: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      hasNext: false,
      ...overrides.page,
    },
  }
}

export function operatorAccountDetail(
  overrides: Partial<OperatorAccountDetail> = {},
): OperatorAccountDetail {
  return {
    operatorId: 'op-1001',
    email: 'op***@miriyum.hq',
    displayName: '김운영',
    status: 'ACTIVE',
    passwordChangeRequired: false,
    authorityVersion: 3,
    roles: ['MEMBER_SUPPORT_OPERATOR'],
    directPermissions: ['AUDIT_READ'],
    // 역할이 준 권한과 직접 권한이 섞인 상태. 화면이 출처를 갈라 보여 준다.
    effectivePermissions: [
      'MEMBER_READ_MINIMAL',
      'MEMBER_RECOVERY',
      'ACCOUNT_APPEAL_REVIEW',
      'AUDIT_READ',
    ],
    lastLoginAt: '2026-08-17T09:00:00Z',
    ...overrides,
  }
}
