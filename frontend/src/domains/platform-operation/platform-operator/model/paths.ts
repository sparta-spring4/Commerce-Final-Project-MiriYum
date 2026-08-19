import { PLATFORM_OPERATOR_PATHS } from '../../../../app/routes/paths/platformOperatorPaths'
import type { AccountType } from '../api/memberSupportApi'

/**
 * route 템플릿의 자리를 채운다.
 *
 * 화면마다 문자열을 이어 붙이면 `/admin/members/` 같은 접두사가 흩어져,
 * route 표를 바꿔도 링크가 따라오지 않는다. 템플릿은 `routes.ts`가 소유하고
 * 여기서는 치환만 한다.
 */
export function memberDetailPath(
  accountType: AccountType,
  accountId: string,
): string {
  return PLATFORM_OPERATOR_PATHS.memberDetail
    .replace(':accountType', encodeURIComponent(accountType))
    .replace(':accountId', encodeURIComponent(accountId))
}

export function supportCaseDetailPath(caseId: string): string {
  return PLATFORM_OPERATOR_PATHS.supportCaseDetail.replace(
    ':caseId',
    encodeURIComponent(caseId),
  )
}

export function operatorDetailPath(operatorId: string): string {
  return PLATFORM_OPERATOR_PATHS.operatorDetail.replace(
    ':operatorId',
    encodeURIComponent(operatorId),
  )
}

export function storeDetailPath(storeId: number): string {
  return PLATFORM_OPERATOR_PATHS.storeDetail.replace(
    ':storeId',
    encodeURIComponent(String(storeId)),
  )
}

export function storeSanctionCasePath(
  storeId: number,
  caseId: string,
): string {
  return PLATFORM_OPERATOR_PATHS.storeSanctionCase
    .replace(':storeId', encodeURIComponent(String(storeId)))
    .replace(':caseId', encodeURIComponent(caseId))
}

export function auditEventDetailPath(eventKey: string): string {
  return PLATFORM_OPERATOR_PATHS.auditDetail.replace(
    ':eventKey',
    encodeURIComponent(eventKey),
  )
}
