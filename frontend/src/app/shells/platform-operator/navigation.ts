import type { PlatformOperatorPermission } from '../../../domains/account/platform-operator/auth/model/capabilities'
import { PLATFORM_OPERATOR_PATHS } from '../../routes/paths/platformOperatorPaths'

export interface PlatformOperatorNavigationItem {
  label: string
  path: string
  permissions: readonly PlatformOperatorPermission[]
}

export const PLATFORM_OPERATOR_NAVIGATION: readonly PlatformOperatorNavigationItem[] = [
  {
    label: '회원 관리',
    path: PLATFORM_OPERATOR_PATHS.members,
    permissions: ['MEMBER_READ_MINIMAL'],
  },
  {
    label: '회원지원 사건',
    path: PLATFORM_OPERATOR_PATHS.supportCases,
    permissions: ['MEMBER_RECOVERY', 'ACCOUNT_APPEAL_REVIEW'],
  },
  {
    label: '매장 관리',
    path: PLATFORM_OPERATOR_PATHS.stores,
    permissions: ['STORE_READ_MINIMAL'],
  },
  {
    label: '영구 정지 승인',
    path: PLATFORM_OPERATOR_PATHS.memberSanctionApproval,
    permissions: ['ACCOUNT_PERMANENT_SANCTION_APPROVE'],
  },
  {
    label: '운영자 관리',
    path: PLATFORM_OPERATOR_PATHS.operators,
    permissions: ['OPERATOR_AUTHORITY_MANAGE'],
  },
  {
    label: '감사 이력',
    path: PLATFORM_OPERATOR_PATHS.audit,
    permissions: ['AUDIT_READ'],
  },
]

export const CONSOLE_NAVIGATION = PLATFORM_OPERATOR_NAVIGATION
