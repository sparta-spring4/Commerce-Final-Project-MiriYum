# 결제 복구 승인 정합성 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용하여 이 계획을 작업 단위로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 문법을 사용한다.

**목표:** 배정, 인가, 변경 불가능한 감사, 멱등성, 대사 통제를 보존하면서 PAY-015와 ADMIN-005를 사용자가 승인한 ADMIN-006 승인 경계에 맞춘다.

**아키텍처:** 증거 기반 내부 원장 동기화는 단일 운영자 복구 작업으로 취급하고, 신규 환불과 보상은 ADMIN-006으로 경유시키며, 근거 없는 직접 금전 상태 수정은 계속 금지한다. 유효한 단일 관리자 승인에서는 요청자와 승인자 감사 필드에 동일 계정을 기록하되, 고위험 임계값에서는 별도의 요청자와 슈퍼관리자 승인을 유지한다.

**기술 스택:** Markdown 정책 문서, Git diff, ripgrep

## 전역 제약

- PAY-014 사건에 배정되고 필요한 `결제 복구` 권한을 가진 운영자만 수동 복구를 수행할 수 있다.
- 원 결제금액을 초과하지 않는 KRW 200,000 이하의 환불 또는 보상은 결제 관리자 한 명이 요청·승인·실행할 수 있다.
- KRW 200,000을 초과하는 환불 또는 보상에는 다른 슈퍼관리자의 추가 승인이 필요하다.
- 원 결제금액을 초과하는 별도 보상에는 금액과 관계없이 다른 슈퍼관리자의 승인이 필요하다.
- 새로운 외부 자금 이동을 만들지 않는 증거 기반 원장 동기화는 단일 운영자 복구 작업이다.
- 근거 없는 직접 금액, 귀속, 정산 상태 또는 원장 덮어쓰기는 계속 금지한다.
- 모든 작업에는 변경 불가능한 감사, 버전, 멱등성, 외부 조회, 실패, 재시도, 대사 요구사항을 유지한다.

---

### 작업 1: 상세 결제 및 관리자 정책을 일치시킨다

**파일:**
- 수정: `docs/service-policies/08-payment-refund.md`
- 수정: `docs/service-policies/15-admin-operation.md`
- 수정: `docs/technical-architecture.md`
- 수정: `tastelock-service-decisions.md`

**인터페이스:**
- 입력: 기존 PAY-014/PAY-015 복구 흐름과 ADMIN-005/ADMIN-006 인가 경계
- 출력: 수동 결제 복구를 위한 일관된 승인 매트릭스와 명시적인 사용자 결정 기록

- [x] **단계 1: PAY-015를 갱신한다**

무조건적인 두 행위자 요구사항을 다음 세 분류로 대체한다.

1. 증거 기반 멱등 재시도 또는 원장 동기화: 배정된 인가 운영자
2. 신규 환불 또는 보상: ADMIN-006 임계값
3. 근거 없거나 재량적인 직접 덮어쓰기: 금지

인수 조건과 정책 이력도 같은 분류를 사용하도록 갱신한다.

- [x] **단계 2: ADMIN-005와 ADMIN-006을 갱신한다**

PAY-014 사건에 배정되고 두 필수 권한을 모두 가진 관리자는 KRW 200,000 이하의 적격 거래를 단독으로 요청·승인·실행할 수 있다고 명시한다. 이 단일 승인 유형에서는 요청자와 승인자 감사 필드에 동일 계정을 기록한다. 기존 고위험 경계에서는 별도의 슈퍼관리자 승인을 유지한다.

- [x] **단계 3: 아키텍처와 결정 이력을 일치시킨다**

공식 기술 아키텍처 요약을 갱신하고, 증거 기반 원장 동기화가 단일 운영자 작업이며 신규 환불 또는 보상은 ADMIN-006을 따른다는 2026-07-23 사용자 결정을 추가한다.

- [x] **단계 4: 의미 정합성을 검증한다**

실행:

```powershell
rg -n -S '금전 영향 실행은 분리된 두 행위자|재무 효과가 있거나 되돌리기 어려운 복구는 제안자와 다른|자기 승인.*차단' docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md
```

예상: 오래된 무조건적 두 행위자 규칙이 없다.

실행:

```powershell
rg -n -S '200,000원|200,001원|원장 보정|단독 승인|요청자와 승인자|멱등 키|결과 불명' docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md docs/technical-architecture.md tastelock-service-decisions.md
```

예상: 임계값, 단일 운영자 원장 동기화, 감사 식별, 멱등성, 결과 불명 규칙이 존재하며 서로 일치한다.

- [x] **단계 5: 최종 diff를 검토한다**

실행:

```powershell
git diff --check
git diff -- docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md docs/technical-architecture.md tastelock-service-decisions.md
```

예상: 공백 오류가 없고 대상 파일에는 승인된 정책 정합성 변경만 나타난다.
