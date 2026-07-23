# Payment Recovery Approval Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align PAY-015 and ADMIN-005 with the user-approved ADMIN-006 approval boundary while preserving assignment, authorization, immutable audit, idempotency, and reconciliation controls.

**Architecture:** Treat evidence-backed internal ledger synchronization as a one-operator recovery action, route new refunds and compensation through ADMIN-006, and continue to prohibit unsupported direct financial-state edits. Record the same account as requester and approver for a valid single-manager approval, while retaining distinct requester and super-administrator approval for higher-risk thresholds.

**Tech Stack:** Markdown policy documents, Git diff, ripgrep

## Global Constraints

- Only an operator assigned to the PAY-014 incident and holding the required `결제 복구` permission may perform manual recovery.
- A payment manager may singly request, approve, and execute a refund or compensation at or below KRW 200,000 when it does not exceed the original payment.
- Refund or compensation above KRW 200,000 requires a different super administrator's additional approval.
- Separate compensation above the original payment requires a different super administrator's approval regardless of amount.
- Evidence-backed ledger synchronization that creates no new external money movement is a one-operator recovery action.
- Unsupported direct amount, attribution, settlement-state, or ledger overwrites remain prohibited.
- All actions retain immutable audit, version, idempotency, external lookup, failure, retry, and reconciliation requirements.

---

### Task 1: Align detailed payment and administrator policies

**Files:**
- Modify: `docs/service-policies/08-payment-refund.md`
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `docs/technical-architecture.md`
- Modify: `tastelock-service-decisions.md`

**Interfaces:**
- Consumes: Existing PAY-014/PAY-015 recovery flow and ADMIN-005/ADMIN-006 authorization boundaries.
- Produces: One consistent approval matrix for manual payment recovery and an explicit user decision record.

- [x] **Step 1: Update PAY-015**

Replace the unconditional two-actor requirement with these three classes:

1. evidence-backed idempotent retry or ledger synchronization: assigned authorized operator;
2. new refund or compensation: ADMIN-006 threshold;
3. unsupported or discretionary direct overwrite: prohibited.

Update the acceptance criteria and policy history to use the same classes.

- [x] **Step 2: Update ADMIN-005 and ADMIN-006**

State that a manager assigned to a PAY-014 incident and holding both required permissions may singly request, approve, and execute an eligible transaction at or below KRW 200,000. For this single-approval type, record the same account in requester and approver audit fields. Keep distinct super-administrator approval at the existing higher-risk boundaries.

- [x] **Step 3: Align architecture and decision history**

Update the official technical architecture summary and append a 2026-07-23 user decision recording that evidence-backed ledger synchronization is a one-operator action and that new refunds or compensation follow ADMIN-006.

- [x] **Step 4: Verify semantic consistency**

Run:

```powershell
rg -n -S '금전 영향 실행은 분리된 두 행위자|재무 효과가 있거나 되돌리기 어려운 복구는 제안자와 다른|자기 승인.*차단' docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md
```

Expected: no stale unconditional two-actor rule.

Run:

```powershell
rg -n -S '200,000원|200,001원|원장 보정|단독 승인|요청자와 승인자|멱등 키|결과 불명' docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md docs/technical-architecture.md tastelock-service-decisions.md
```

Expected: the threshold, one-operator ledger synchronization, audit identity, idempotency, and unknown-result rules are present and mutually consistent.

- [x] **Step 5: Review the final diff**

Run:

```powershell
git diff --check
git diff -- docs/service-policies/08-payment-refund.md docs/service-policies/15-admin-operation.md docs/technical-architecture.md tastelock-service-decisions.md
```

Expected: no whitespace errors and only the approved policy alignment appears in the target files.
