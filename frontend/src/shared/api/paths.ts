import type { paths as AdminStorePaths } from './generated/admin-store'
import type { paths as AdminMonitoringPaths } from './generated/admin-monitoring'
import type { paths as AnalyticsPaths } from './generated/analytics'
import type { paths as AuthAccountPaths } from './generated/auth-account'
import type { paths as MemberSupportPaths } from './generated/member-support'
import type { paths as MenuHoldPickupPaths } from './generated/menu-hold-pickup'
import type { paths as NotificationPaths } from './generated/notification'
import type { paths as PaymentPaths } from './generated/payment'
import type { paths as PaymentRecoveryPaths } from './generated/payment-recovery'
import type { paths as PlatformOperatorAuthPaths } from './generated/platform-operator-auth'
import type { paths as PlatformOperatorAuthorizationPaths } from './generated/platform-operator-authorization'
import type { paths as PlatformOperatorCapabilitiesPaths } from './generated/platform-operator-capabilities'
import type { paths as PlatformOperatorManagementAuditPaths } from './generated/platform-operator-management-audit'
import type { paths as ReservationPaths } from './generated/reservation'
import type { paths as StoreSearchPaths } from './generated/store-search'
import type { paths as StoreOnboardingPaths } from './generated/store-onboarding'
import type { paths as WaitingPaths } from './generated/waiting'

/**
 * 기능별 OpenAPI 문서에서 생성한 paths를 하나의 API 표면으로 합친다.
 * 생성 파일은 직접 수정하지 않고 여기서 타입으로만 조합한다.
 */

/**
 * 문서 하나하나가 아니라 목록으로 둔다.
 *
 * 문서가 늘어날 때마다 짝을 손으로 적으면 개수가 제곱으로 늘고, 한 줄만 빠져도
 * 빠졌다는 사실이 드러나지 않는다. 아래 판정이 이 목록을 훑으므로 문서를
 * 여기 한 번만 추가하면 검사와 합류가 함께 따라간다.
 */
type StoreCollectionPath = '/api/v1/store-operators/stores'
type StoreOnboardingDisjointPaths = Omit<
  StoreOnboardingPaths,
  StoreCollectionPath
>

type PathDocs = [
  AuthAccountPaths,
  StoreSearchPaths,
  StoreOnboardingDisjointPaths,
  ReservationPaths,
  PaymentPaths,
  MenuHoldPickupPaths,
  NotificationPaths,
  PlatformOperatorAuthPaths,
  PlatformOperatorAuthorizationPaths,
  PlatformOperatorCapabilitiesPaths,
  MemberSupportPaths,
  PlatformOperatorManagementAuditPaths,
  AdminStorePaths,
  AdminMonitoringPaths,
  AnalyticsPaths,
  PaymentRecoveryPaths,
  WaitingPaths,
]

/**
 * union의 `keyof`는 공통 키만 준다. 각 구성원의 키를 모두 모으려면 분배해야 한다.
 */
type KeysOfEach<T> = T extends unknown ? keyof T : never

/** 앞 문서와 뒤 문서들 사이의 겹치는 경로를 재귀로 모은다. */
type OverlappingPaths<Docs extends readonly unknown[]> = Docs extends readonly [
  infer Head,
  ...infer Rest,
]
  ? Extract<keyof Head, KeysOfEach<Rest[number]>> | OverlappingPaths<Rest>
  : never

/**
 * 두 문서가 같은 경로를 선언하면 컴파일이 실패한다.
 *
 * 교집합 타입(&)은 겹치는 키를 조용히 합쳐 버려서, 서로 다른 operation이
 * 하나로 뭉개져도 알 수 없다. 아래 제약이 그 상황을 typecheck에서 드러낸다.
 */
type AssertNoOverlap<T extends never> = T

export type NoPathOverlap = AssertNoOverlap<OverlappingPaths<PathDocs>>

/** 목록의 모든 문서를 하나의 표면으로 합친다. */
type MergeAll<Docs extends readonly unknown[]> = Docs extends readonly [
  infer Head,
  ...infer Rest,
]
  ? Head & MergeAll<Rest>
  : unknown

export type ApiPaths = MergeAll<PathDocs>

/** OpenAPI에 존재하는 경로만 허용한다. */
export type ApiPath = keyof ApiPaths & string

export type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete'

/** 해당 경로가 실제로 선언한 method만 허용한다. */
export type MethodOf<P extends ApiPath> = Extract<keyof ApiPaths[P], HttpMethod>

export type OperationOf<
  P extends ApiPath,
  M extends MethodOf<P>,
> = ApiPaths[P][M]

/** 경로 템플릿의 {name} 자리를 이름으로 뽑는다. */
type PathParamName<P extends string> =
  P extends `${string}{${infer Name}}${infer Rest}`
    ? Name | PathParamName<Rest>
    : never

/** 템플릿에 자리가 없으면 pathParams를 받지 않는다. */
export type PathParamsOf<P extends ApiPath> = [PathParamName<P>] extends [never]
  ? { pathParams?: never }
  : { pathParams: Record<PathParamName<P>, string | number> }

type JsonRequestBody<Op> = Op extends {
  requestBody: { content: { 'application/json': infer Body } }
}
  ? Body
  : never

/** 요청 본문은 해당 operation의 생성 타입으로만 받는다. */
export type RequestBodyOf<Op> = [JsonRequestBody<Op>] extends [never]
  ? { body?: never }
  : { body: JsonRequestBody<Op> }

type MultipartRequestBody<Op> = Op extends {
  requestBody: { content: { 'multipart/form-data': infer Body } }
}
  ? Body
  : never

/** multipart 요청도 OpenAPI에 선언된 operation에서만 허용한다. */
export type MultipartOf<Op> = [MultipartRequestBody<Op>] extends [never]
  ? { multipart?: never }
  : { multipart: FormData }

/**
 * client가 기본으로 좁혀 주는 성공 status다. 전부 application/json 본문을 가지며
 * 204를 선언한 operation은 없다.
 *
 * 202는 요청을 성공적으로 접수했지만 아직 종결되지 않은 상태다. 200·201과 함께
 * 성공 본문에 포함하되, 여러 성공 모양을 선언한 호출부가 반드시 합집합을 좁혀
 * 완료와 진행 중을 구분하게 한다.
 */
type SuccessStatus = 200 | 201 | 202

/**
 * 성공 응답 타입. 생성 타입이 이미 {code, message, data} 봉투를 담고 있으므로
 * 응답 필드를 손으로 다시 정의하지 않는다.
 */
export type SuccessBodyOf<Op> = Op extends { responses: infer Responses }
  ? {
      [S in Extract<keyof Responses, SuccessStatus>]: Responses[S] extends {
        content: { 'application/json': infer Body }
      }
        ? Body
        : never
    }[Extract<keyof Responses, SuccessStatus>]
  : never

/** operation이 Idempotency-Key 헤더를 요구하면 호출에서도 필수로 만든다. */
export type IdempotencyOf<Op> = Op extends {
  parameters: { header: { 'Idempotency-Key': unknown } }
}
  ? { idempotencyKey: string }
  : { idempotencyKey?: never }

/** operation이 X-Correlation-Id를 요구하면 호출에서도 필수로 만든다. */
export type CorrelationIdOf<Op> = Op extends {
  parameters: { header: { 'X-Correlation-Id': unknown } }
}
  ? { correlationId: string }
  : { correlationId?: never }

/**
 * operation이 X-CSRF-TOKEN 헤더를 요구하면 호출에서도 필수로 만든다.
 *
 * CSRF double-submit은 로그아웃처럼 Refresh 쿠키로 동작하는 요청에만 걸린다.
 * 어떤 경로가 요구하는지 손으로 기억하지 않고 계약이 정하게 한다.
 */
export type CsrfOf<Op> = Op extends {
  parameters: { header: { 'X-CSRF-TOKEN': unknown } }
}
  ? { csrfToken: string }
  : { csrfToken?: never }

/**
 * operation이 If-Match를 요구하면 호출에서도 필수로 만든다.
 *
 * 플랫폼 운영자 명령은 대상의 현재 version을 함께 보내야 한다. 값이 낡으면
 * 서버가 409로 거절한다. 화면은 그때 최신 상세를 다시 읽어야 하며,
 * version을 빼고 보내 마지막 쓰기가 이기게 두지 않는다.
 */
export type IfMatchOf<Op> = Op extends {
  parameters: { header: { 'If-Match': unknown } }
}
  ? { ifMatch: number }
  : { ifMatch?: never }

/**
 * 감사 조회가 요구하는 사건·사유 헤더다.
 *
 * 이 세 값은 조회 자체의 인가 조건이라 화면이 운영자에게서 받아야 한다.
 * 임의로 만들어 채우면 감사 원장에 거짓 사유가 남는다.
 */
export type AdminAuditContextOf<Op> = Op extends {
  parameters: {
    header: {
      'X-Admin-Case-Id': unknown
      'X-Admin-Case-Version': unknown
      'X-Admin-Reason-Code': unknown
    }
  }
}
  ? {
      adminAuditContext: {
        caseId: string
        caseVersion: number
        reasonCode: string
      }
    }
  : { adminAuditContext?: never }

/**
 * 사건만 참조하는 명령의 헤더.
 *
 * 보정은 조회와 달리 `X-Admin-Reason-Code`를 요구하지 않는다. 사유가 본문의
 * `reason: RECORD_CORRECTION` 고정값으로 들어가기 때문이다. 조회용 세 헤더를
 * 그대로 재사용하면 계약에 없는 헤더를 보내게 되므로 분리한다.
 */
export type AdminCaseRefOf<Op> = Op extends {
  parameters: {
    header: {
      'X-Admin-Case-Id': unknown
      'X-Admin-Case-Version': unknown
      'X-Admin-Reason-Code': unknown
    }
  }
}
  ? { adminCaseRef?: never }
  : Op extends {
        parameters: {
          header: {
            'X-Admin-Case-Id': unknown
            'X-Admin-Case-Version': unknown
          }
        }
      }
    ? { adminCaseRef: { caseId: string; caseVersion: number } }
    : { adminCaseRef?: never }

/**
 * 사유 코드만 요구하는 조회의 헤더.
 *
 * 매장 목록·상세는 `X-Admin-Reason-Code` 하나만 요구한다. 감사 조회처럼
 * 사건 ID·version까지 묶인 맥락이 아니라, 조회 사유만 남기는 형태다.
 *
 * 사건 맥락을 함께 요구하는 operation에서는 `adminAuditContext`가 사유까지
 * 담으므로 여기서는 막는다. 두 옵션이 같은 헤더를 두 번 쓰게 두면 어느 쪽이
 * 실제로 전송됐는지 호출부에서 알 수 없다.
 */
export type AdminReasonOnlyOf<Op> = Op extends {
  parameters: { header: { 'X-Admin-Case-Id': unknown } }
}
  ? { adminReasonCode?: never }
  : Op extends {
        parameters: { header: { 'X-Admin-Reason-Code': unknown } }
      }
    ? { adminReasonCode: string }
    : { adminReasonCode?: never }

/**
 * 재인증 승인 헤더를 요구하는 operation.
 *
 * 원칙은 다른 헤더와 같이 생성 타입에서 파생하는 것이다. 그런데
 * `member-support` 문서는 이 parameter를 `platform-operator-authorization`
 * 문서로 가는 `$ref`로 정의하고, 각 operation은 그 지역 이름을 다시 `$ref`한다.
 * openapi-typescript 6.7.6은 이 두 단계 참조에서 헤더 *이름*을 복원하지 못해
 * operation의 header에서 통째로 빠뜨린다. `platform-operator-management-audit`은
 * 같은 헤더를 직접 선언해서 정상 생성된다.
 *
 * 생성 타입만 믿으면 제재·사건 결정·영구 정지 승인이 재인증 없이 나가도
 * typecheck가 통과한다. 계약이 요구하는 쪽으로 닫아 둔다. 문서가 고쳐져
 * 생성 타입에 헤더가 들어오면 아래 분기가 먼저 걸리므로 목록은 그때 지운다.
 *
 * 생성 타입에는 operationId가 남지 않으므로 경로로 지목한다.
 * 원본: `docs/specs/member-support/openapi.yaml`의 decisions·sanctions·
 * additional-approvals operation과 같은 문서 components.parameters.AdminReauthentication
 */
type ReauthenticationRequiredPath =
  | '/api/v1/platform-operators/member-support-cases/{caseId}/decisions'
  | '/api/v1/platform-operators/members/{accountType}/{accountId}/sanctions'
  | '/api/v1/platform-operators/member-sanctions/{sanctionId}/additional-approvals'

/**
 * 목록이 실제 경로를 가리키는지 확인한다.
 * 문서가 경로를 바꾸면 여기서 먼저 컴파일이 깨져, 재인증 요구가 조용히 사라지지 않는다.
 */
type AssertKnownPaths<T extends ApiPath> = T
export type ReauthenticationPathsExist =
  AssertKnownPaths<ReauthenticationRequiredPath>

export type AdminReauthenticationOf<P extends ApiPath, Op> = Op extends {
  parameters: { header: { 'X-Admin-Reauthentication': unknown } }
}
  ? { adminReauthentication: string }
  : P extends ReauthenticationRequiredPath
    ? { adminReauthentication: string }
    : /*
       * 계약이 조건부로 요구하는 경우다. 매장 제재 생성은 고위험 제재일 때만
       * 재인증을 요구하므로 헤더가 optional로 선언된다. 항상 필수로 만들면
       * 경고 제재까지 불필요한 재인증을 거치고, 아예 막으면 고위험 제재를
       * 보낼 수 없다. 어느 쪽인지는 화면이 제재 유형을 보고 판단한다.
       */
      Op extends {
          parameters: { header: { 'X-Admin-Reauthentication'?: unknown } }
        }
      ? { adminReauthentication?: string }
      : { adminReauthentication?: never }

/** 본문 없는 204 성공은 해당 operation이 OpenAPI에 선언한 경우에만 허용한다. */
export type NoContentOf<Op> = Op extends { responses: infer Responses }
  ? 204 extends keyof Responses
    ? { allowNoContent?: true }
    : { allowNoContent?: never }
  : { allowNoContent?: never }
