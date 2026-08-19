/**
 * 플랫폼 운영자 권한 노출 판정.
 *
 * 권한은 `GET /api/v1/platform-operators/me`가 중앙 RBAC 원장에서 계산한 값을
 * 사용한다. 로그인·회전 응답이나 Access Token claims에서 추정하지 않는다.
 *
 * ## 하지 않는 것
 *
 * - **Access Token 디코딩**: 토큰 구조에 화면이 결합되고, 권한을 회수해도
 *   토큰이 만료될 때까지 화면이 옛 권한을 믿는다.
 * - **역할→권한 표를 프론트에 복제**: backend `PlatformOperatorRole`이 이미
 *   그 표를 갖고 있다. 복제하면 두 벌이 어긋나는 순간 화면이 서버보다 넓은
 *   권한을 그린다. 이 파일에 역할 상수가 없는 건 그래서다.
 * - **`SUPER_ADMIN`을 만능으로 취급**: backend에서 `SUPER_ADMIN`은 운영자 생성·
 *   권한 관리·추가 승인 묶음일 뿐 심사·회원지원·감사 권한을 포함하지 않는다.
 *   "슈퍼관리자면 다 보여 준다"는 판정은 서버와 어긋난다.
 * - **403을 받아 보고 메뉴 구성**: 거부될 걸 알면서 보내는 요청은 감사 원장에
 *   거부 기록을 쌓는다. 감사 조회는 거부도 append하는 계약이다.
 *
 * 권한 조회 중이거나 실패하면 판정을 내리지 않는다. 그 구간에 업무 요청을
 * 보내면 권한 없는 요청이 감사 원장에 쌓이므로 화면은 실패 폐쇄한다.
 */

/**
 * backend `PlatformOperatorPermission` enum의 이름들.
 *
 * 값이 아니라 이름만 옮긴 것이라 역할→권한 표를 복제하는 것과 다르다.
 * 어떤 운영자가 무엇을 갖는지는 전적으로 서버가 정한다.
 */
export type PlatformOperatorPermission =
  | 'OPERATOR_CREATE'
  | 'OPERATOR_AUTHORITY_MANAGE'
  | 'OPERATOR_SUSPEND'
  | 'ONBOARDING_REVIEW'
  | 'ONBOARDING_EVIDENCE_READ'
  | 'MEMBER_READ_MINIMAL'
  | 'MEMBER_RECOVERY'
  | 'ACCOUNT_SANCTION'
  | 'ACCOUNT_PERMANENT_SANCTION_APPROVE'
  | 'ACCOUNT_APPEAL_REVIEW'
  | 'STORE_READ_MINIMAL'
  | 'STORE_SANCTION'
  | 'OPERATIONS_MONITOR_READ'
  | 'PAYMENT_RECOVERY_EXECUTE'
  | 'PAYMENT_RECOVERY_HIGH_VALUE_APPROVE'
  | 'AUDIT_READ'
  | 'INCIDENT_RESPOND'
  | 'BREAK_GLASS_APPROVE'

/**
 * 권한 조회 결과.
 *
 * `unknown`은 "권한이 없다"가 아니다. 둘을 합치면 계약이 생긴 뒤에도
 * 화면이 조용히 빈 상태로 남는다.
 */
export type CapabilityState =
  | { status: 'unknown'; reason: 'loading' }
  | { status: 'unknown'; reason: 'loadFailed'; error: unknown }
  | { status: 'loaded'; permissions: ReadonlySet<PlatformOperatorPermission> }

/** 한 기능을 노출할지에 대한 판정. */
export type CapabilityDecision = 'allowed' | 'denied' | 'undetermined'

/**
 * 서버 snapshot을 화면 판정용 집합으로 바꾼다.
 */
export function resolveCapabilities(current: {
  permissions: readonly PlatformOperatorPermission[]
}): CapabilityState {
  return { status: 'loaded', permissions: new Set(current.permissions) }
}

/**
 * 권한 하나에 대한 노출 판정.
 *
 * 조회 중·실패를 `allowed`로 기울이면 없는 권한의 버튼이 그려진다. 어느
 * 쪽으로도 추정하지 않고 `undetermined`를 돌려 화면이 실패 폐쇄하게 한다.
 */
export function decideCapability(
  state: CapabilityState,
  permission: PlatformOperatorPermission,
): CapabilityDecision {
  if (state.status === 'unknown') {
    return 'undetermined'
  }
  return state.permissions.has(permission) ? 'allowed' : 'denied'
}

/**
 * 여러 권한 중 하나라도 있으면 노출하는 판정.
 *
 * 내비게이션 항목 하나가 여러 endpoint를 묶는 경우에 쓴다. 예를 들어 회원
 * 관리는 조회와 제재가 서로 다른 권한이라, 조회만 가진 운영자에게도 항목은
 * 보여야 한다.
 */
export function decideAnyCapability(
  state: CapabilityState,
  permissions: readonly PlatformOperatorPermission[],
): CapabilityDecision {
  if (state.status === 'unknown') {
    return 'undetermined'
  }
  return permissions.some((permission) => state.permissions.has(permission))
    ? 'allowed'
    : 'denied'
}
