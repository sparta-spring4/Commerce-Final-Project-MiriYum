import type { ReactNode } from 'react'
import { Alert, ErrorState, Loading } from '../../../../shared/ui/Feedback'
import {
  decideCapability,
  usePlatformOperatorAuth,
  type PlatformOperatorPermission,
} from '../../../account/platform-operator/auth'

/**
 * 운영자 계정 업무의 권한 없음 상태.
 *
 * 일반 조회 오류와 분리한다. 재시도해도 결과가 같으므로 "다시 시도" 버튼을 주면
 * 거부 요청만 반복되고, 그 거부는 감사 원장에 기록된다.
 *
 * 어떤 권한이 필요한지는 알려 준다. 운영자가 누구에게 무엇을 요청해야 하는지
 * 알 수 있어야 한다. 다만 다른 계정의 존재나 권한 보유 여부는 드러내지 않는다.
 */
export function OperatorAccessDenied({
  requiredPermission,
}: {
  requiredPermission?: PlatformOperatorPermission
}) {
  return (
    <Alert tone="warning" title="이 업무를 수행할 권한이 없습니다.">
      {requiredPermission !== undefined && (
        <>
          이 업무에는 <code>{requiredPermission}</code> 권한이 필요합니다.{' '}
        </>
      )}
      담당 업무에 권한이 필요하면 슈퍼관리자에게 요청해 주세요.
    </Alert>
  )
}

/**
 * 업무 페이지가 mount되기 전에 중앙 capability snapshot으로 접근을 닫는다.
 *
 * 메뉴 숨김은 탐색 편의일 뿐 보안 경계가 아니다. 주소를 직접 입력해도 이
 * 경계를 먼저 지나므로 권한 없는 query가 mount되어 감사 거부 기록을 남기지
 * 않는다. snapshot 조회 실패도 허용으로 기울이지 않는다.
 */
export function OperatorCapabilityGate({
  permission,
  children,
}: {
  permission: PlatformOperatorPermission
  children: ReactNode
}) {
  const { capabilities, retryCapabilities } = usePlatformOperatorAuth()
  const decision = decideCapability(capabilities, permission)

  if (decision === 'allowed') {
    return children
  }
  if (decision === 'denied') {
    return <OperatorAccessDenied requiredPermission={permission} />
  }
  if (
    capabilities.status === 'unknown' &&
    capabilities.reason === 'loadFailed'
  ) {
    return <ErrorState error={capabilities.error} onRetry={retryCapabilities} />
  }
  return <Loading label="업무 권한을 확인하는 중입니다." />
}
