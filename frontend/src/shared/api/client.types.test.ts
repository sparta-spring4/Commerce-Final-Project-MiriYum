import { describe, expect, expectTypeOf, test } from 'vitest'
import { createApiClient } from './client'
import type { ApiErrorBody, ValidationErrorDetail } from './envelope'
import type { components as CommonComponents } from './generated/common'

/**
 * 컴파일 시점 타입 경계 회귀 테스트. 런타임 값 판정은 envelope.test.ts가 담당한다.
 *
 * `@ts-expect-error`는 그 줄에 오류가 **없으면** 그 자체가 오류가 된다.
 * 따라서 아래 항목 중 하나라도 다시 허용되면 `pnpm run typecheck`가 실패한다.
 *
 * 아래 함수들은 호출하지 않는다. 타입 검사만 받으면 되고 실제로 실행하면
 * 네트워크 호출이 일어난다.
 */

const api = createApiClient()

/** 계약이 허용하는 호출과 그 추론 결과. */
export function allowedCalls() {
  const categories = api('/api/v1/store-categories', { method: 'get' })

  expectTypeOf(categories).resolves.toHaveProperty('code')
  expectTypeOf(categories).resolves.toHaveProperty('message')
  expectTypeOf(categories).resolves.toHaveProperty('data')

  // 응답 data는 OpenAPI가 정의한 모양이다. 호출자가 지정하지 않는다.
  type Data = Awaited<typeof categories>['data']
  expectTypeOf<Data>().toHaveProperty('items')
  expectTypeOf<Data['items'][number]>().toHaveProperty('code')
  expectTypeOf<Data['items'][number]>().toHaveProperty('displayName')

  // 경로 변수와 필수 본문·멱등 키를 갖춘 호출
  void api('/api/v1/consumers/me/reservations/{reservationId}', {
    method: 'get',
    pathParams: { reservationId: 1 },
  })
  void api('/api/v1/consumers/me', {
    method: 'patch',
    body: { nickname: '미리' },
    idempotencyKey: 'key-1',
  })
}

/** 타입이 막아야 하는 호출. 각 줄에 실제로 오류가 나야 typecheck가 통과한다. */
export function rejectedCalls() {
  // @ts-expect-error OpenAPI에 없는 경로는 허용하지 않는다
  void api('/api/v1/not-a-real-path', { method: 'get' })

  // @ts-expect-error /api/v1/store-categories는 get만 선언한다
  void api('/api/v1/store-categories', { method: 'post' })

  // @ts-expect-error get 요청에는 body가 없다
  void api('/api/v1/store-categories', { method: 'get', body: { any: true } })

  // @ts-expect-error 로그인은 requestBody가 필수다
  void api('/api/v1/consumers/auth/sessions', { method: 'post' })

  void api('/api/v1/consumers/auth/sessions', {
    method: 'post',
    // @ts-expect-error 생성 타입에 없는 필드는 허용하지 않는다
    body: { email: 'a@b.com', password: 'x', notInContract: true },
  })

  // @ts-expect-error reservationId가 필요하다
  void api('/api/v1/consumers/me/reservations/{reservationId}', { method: 'get' })

  // @ts-expect-error PATCH /consumers/me는 Idempotency-Key가 필수다
  void api('/api/v1/consumers/me', { method: 'patch', body: { nickname: '미리' } })

  // @ts-expect-error 계약이 요구하지 않는 곳에는 멱등 키를 넣을 수 없다
  void api('/api/v1/store-categories', { method: 'get', idempotencyKey: 'k' })

  // 이전 구현은 client()<{ items: ... }>(path) 처럼 임의 T를 받았다.
  // 이제 타입 인자는 경로와 method로 고정되므로 수동 응답 타입을 넣을 수 없다.
  // @ts-expect-error 응답 타입은 호출자가 지정하지 않는다
  void api<{ anything: true }>('/api/v1/store-categories', { method: 'get' })
}

export function paymentRecoveryHeaderCalls() {
  void api(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { caseId: 'case-1' },
      body: { expectedCaseVersion: 1 },
      idempotencyKey: 'key-1',
      adminReauthentication: 'approval-1',
      correlationId: 'correlation-1',
    },
  )

  void api(
    '/api/v1/platform-operators/payment-recovery-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { caseId: 'case-1' },
      body: { expectedCaseVersion: 1 },
      idempotencyKey: 'key-1',
      adminReauthentication: 'approval-1',
      // @ts-expect-error 결제 복구 명령은 상관관계 ID가 필수다
      correlationId: undefined,
    },
  )
}

/**
 * 플랫폼 운영자 고위험 명령과 감사 조회의 헤더 조건.
 *
 * 재인증 헤더는 생성 타입에서 파생되지 않는다. `member-support` 문서가 이
 * parameter를 다른 문서로 가는 `$ref`로 정의해서, openapi-typescript 6.7.6이
 * 두 단계 참조에서 헤더 이름을 복원하지 못하고 operation에서 빠뜨린다.
 * `paths.ts`가 경로 목록으로 닫아 두었고, 아래가 그 장치의 회귀 테스트다.
 *
 * 이 테스트가 깨지는 경우는 둘이다. 문서가 고쳐져 생성 타입에 헤더가 들어왔거나,
 * 누군가 목록에서 경로를 지웠거나. 앞이면 `paths.ts`의 목록을 지우면 되고,
 * 뒤면 제재가 재인증 없이 나가게 된 것이므로 되돌려야 한다.
 */
export function platformOperatorCommandHeaders() {
  // 계약이 요구하는 것을 모두 갖춘 제재 호출
  void api(
    '/api/v1/platform-operators/members/{accountType}/{accountId}/sanctions',
    {
      method: 'post',
      pathParams: { accountType: 'CONSUMER', accountId: 'op-1' },
      body: {
        level: 'WARNING',
        reasonCode: 'ABUSE_REPORT',
        policyVersion: 'SANCTION_POLICY_V1',
      },
      idempotencyKey: 'key-1',
      ifMatch: 3,
      adminReauthentication: 'approval-1',
    },
  )

  void api(
    '/api/v1/platform-operators/members/{accountType}/{accountId}/sanctions',
    {
      method: 'post',
      pathParams: { accountType: 'CONSUMER', accountId: 'op-1' },
      body: {
        level: 'WARNING',
        reasonCode: 'ABUSE_REPORT',
        policyVersion: 'SANCTION_POLICY_V1',
      },
      idempotencyKey: 'key-1',
      ifMatch: 3,
      // @ts-expect-error 제재는 재인증 승인 없이 보낼 수 없다
      adminReauthentication: undefined,
    },
  )

  void api('/api/v1/platform-operators/member-support-cases/{caseId}/decisions', {
    method: 'post',
    pathParams: { caseId: 'case-1' },
    body: { decision: 'APPROVE', reasonCode: 'VERIFIED' },
    idempotencyKey: 'key-2',
    ifMatch: 1,
    // @ts-expect-error 사건 결정도 재인증 승인이 필요하다
    adminReauthentication: undefined,
  })

  // 감사 조회는 사건·version·사유 코드를 함께 보내야 한다.
  void api('/api/v1/platform-operators/audit-events', {
    method: 'get',
    adminAuditContext: {
      caseId: 'audit-case-1',
      caseVersion: 2,
      reasonCode: 'AUDIT_REVIEW',
    },
  })

  // @ts-expect-error 감사 조회는 사건·사유 맥락 없이 보낼 수 없다
  void api('/api/v1/platform-operators/audit-events', { method: 'get' })

  // 자기 배정은 version만 요구하고 재인증은 요구하지 않는다.
  void api(
    '/api/v1/platform-operators/member-support-cases/{caseId}/assignments',
    {
      method: 'post',
      pathParams: { caseId: 'case-1' },
      ifMatch: 1,
    },
  )

  void api('/api/v1/consumers/auth/csrf-tokens/current', {
    method: 'get',
    // @ts-expect-error 일반 사용자 경로에는 운영자 명령 헤더를 붙일 수 없다
    adminReauthentication: 'approval-1',
  })
}

/**
 * 오류 본문 타입이 공통 OpenAPI 생성 타입에서 파생되는지 **컴파일 시점에** 고정한다.
 *
 * 손으로 선언하면 계약과 어긋나도 typecheck가 통과한다. 실제로 필드 이름을
 * `reason`이 아니라 `message`로 잘못 적어 검증 오류 사유가 사라질 수 있었다.
 *
 * 이 단언은 우리 코드가 계약과 같은 모양을 쓰는지만 본다. 서버에서 온 값은
 * unknown이라 타입이 보호하지 못하므로, 실제 값 판정은 `envelope.test.ts`의
 * `isApiErrorBody`·`isValidationErrorDetail` 런타임 가드 테스트가 담당한다.
 */
export function errorBodyContract() {
  expectTypeOf<ValidationErrorDetail>().toEqualTypeOf<
    CommonComponents['schemas']['ValidationErrorDetail']
  >()
  expectTypeOf<ApiErrorBody>().toEqualTypeOf<
    CommonComponents['schemas']['ErrorResponse']
  >()

  expectTypeOf<ValidationErrorDetail>().toHaveProperty('field')
  expectTypeOf<ValidationErrorDetail>().toHaveProperty('reason')

  // @ts-expect-error 계약에 message 필드는 없다. reason이다
  expectTypeOf<ValidationErrorDetail>().toHaveProperty('message')

  const detail: ValidationErrorDetail = {
    field: 'menuSelections[0].quantity',
    reason: '1 이상이어야 합니다.',
  }
  void detail

  // @ts-expect-error reason 없이 message로는 만들 수 없다
  const wrong: ValidationErrorDetail = { field: 'nickname', message: '틀림' }
  void wrong
}

describe('컴파일 시점 타입 경계', () => {
  test('오류 본문 타입이 공통 생성 타입에서 파생된다 (런타임 검증은 envelope.test.ts)', () => {
    expect(typeof errorBodyContract).toBe('function')
  })

  test('타입 단언은 typecheck가 검증한다', () => {
    // 실행하면 네트워크 호출이 일어나므로 존재만 확인한다.
    expect(typeof allowedCalls).toBe('function')
    expect(typeof rejectedCalls).toBe('function')
  })
})
