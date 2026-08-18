import { ROUTES } from '../../../app/routes'
import type { PlatformOperatorPermission } from '../../platform-operator-auth/model/capabilities'

/**
 * 운영 콘솔 좌측 내비게이션.
 *
 * 시안은 대시보드·입점 요청·매장 관리·예약 내역·웨이팅 모니터링·복구 및 환불까지
 * 9개 항목을 그린다. 그중 서버 계약이 있는 것만 여기 둔다. 나머지는 눌러도
 * 갈 곳이 없고, 자리표시자를 만들면 "곧 됩니다" 화면이 콘솔에 남는다.
 *
 * 각 항목의 `permissions`는 서버가 그 화면의 조회에 요구하는 권한이다.
 * 하나라도 있으면 항목을 노출한다. 회원 관리처럼 조회와 제재가 다른 권한인
 * 경우, 조회만 가진 운영자에게도 항목은 보여야 하기 때문이다.
 *
 * 실제 노출 판정은 `capabilities.ts`가 중앙 RBAC snapshot으로 수행한다.
 */
export interface ConsoleNavigationItem {
  label: string
  path: string
  permissions: readonly PlatformOperatorPermission[]
}

export const CONSOLE_NAVIGATION: readonly ConsoleNavigationItem[] = [
  {
    label: '회원 관리',
    path: ROUTES.platformOperatorMembers,
    permissions: ['MEMBER_READ_MINIMAL'],
  },
  {
    label: '회원지원 사건',
    path: ROUTES.platformOperatorSupportCases,
    permissions: ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW'],
  },
  {
    label: '매장 관리',
    path: ROUTES.platformOperatorStores,
    permissions: ['STORE_READ_MINIMAL'],
  },
  {
    /*
     * 영구 정지 승인. 승인 대기 목록을 조회하는 계약이 없어(#425) 제안자가
     * 전달한 값으로 진입하는 화면이지만, 주소를 손으로 치게 두지 않는다.
     */
    label: '영구 정지 승인',
    path: ROUTES.platformOperatorMemberSanctionApproval,
    permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
  },
  {
    label: '운영자 관리',
    path: ROUTES.platformOperatorOperators,
    permissions: ['OPERATOR_AUTHORITY_MANAGE'],
  },
  {
    label: '감사 이력',
    path: ROUTES.platformOperatorAudit,
    permissions: ['AUDIT_READ'],
  },
]
