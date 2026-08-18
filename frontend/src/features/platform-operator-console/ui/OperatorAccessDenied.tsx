import { Alert } from '../../../shared/ui/Feedback'

/**
 * 운영자 계정 업무의 권한 없음 상태.
 *
 * 일반 조회 오류와 분리한다. 재시도해도 결과가 같으므로 "다시 시도" 버튼을 주면
 * 거부 요청만 반복되고, 그 거부는 감사 원장에 기록된다.
 *
 * 어떤 권한이 필요한지는 알려 준다. 운영자가 누구에게 무엇을 요청해야 하는지
 * 알 수 있어야 한다. 다만 다른 계정의 존재나 권한 보유 여부는 드러내지 않는다.
 */
export function OperatorAccessDenied() {
  return (
    <Alert tone="warning" title="이 업무를 수행할 권한이 없습니다.">
      운영자 계정 조회와 관리에는 <code>OPERATOR_AUTHORITY_MANAGE</code> 권한이
      필요합니다. 담당 업무에 이 권한이 필요하면 슈퍼관리자에게 요청해 주세요.
    </Alert>
  )
}
