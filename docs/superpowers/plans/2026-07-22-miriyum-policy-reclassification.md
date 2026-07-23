# MiriYum Policy Reclassification Implementation Plan (historical completion record)

> **Historical snapshot (2026-07-22):** This completed plan records the prior reclassification state; current policy totals and queues are maintained in `docs/service-policies/README.md`.

**Goal:** Preserve the 31 explicitly confirmed policies and re-audit all 186 unconfirmed MiriYum policies so every policy document uses the same defensible status and importance classification.

**Architecture:** Treat `docs/service-policies/README.md` as the canonical 217-row classification ledger. Build an evidence-backed audit matrix before editing, then synchronize domain policy documents and the three cross-cutting service documents from that ledger. Use independent reviews after each task and a final repository-wide consistency check; do not implement code or promote `자동 추천 예정` items to `확정`.

**Tech Stack:** Markdown, PowerShell, ripgrep, Git read-only inspection.

## Global Constraints

- Preserve all 31 existing `확정` policy IDs without changing their status, importance, or content except to correct an unmistakable cross-document contradiction.
- Reclassify every one of the remaining 186 policy IDs using the user's five decision criteria and additional review principles, without trusting the current status.
- Keep product-direction, primary-flow, financial-liability, fairness-allocation, and hard-to-reverse promises in `팀원 상의 필요` only when a reasonable default cannot be selected safely.
- Keep standard security, authorization, idempotency, concurrency, state-transition, failure, retry, notification, and audit details in `자동 추천 예정` when they do not alter product direction.
- Keep paid-provider selection, final legal/privacy/terms review, future expansion, empirically tuned numbers, and out-of-scope complexity in `TODO`.
- Do not convert any `자동 추천 예정` item to `확정` during this task.
- Use `MiriYum` as the project name; at the time of this plan, do not modify `miriyum-service-blueprint.md`; do not write implementation code; do not stage, commit, or push.
- Preserve pre-existing user changes and work on the current `dev`-based `codex/` feature branch.
- Assume large traffic and multi-server/multi-instance operation, while avoiding paid services, unnecessarily complex infrastructure, and additional personal-data collection in recommendations.

---

### Task 1: Canonical 217-Policy Audit Matrix

**Files:**
- Read: `miriyum-service-decisions.md`
- Read: `docs/service-definition.md`
- Read: `docs/technical-architecture.md`
- Read: `docs/service-policies/README.md`
- Read: `docs/service-policies/00-policy-template.md`
- Read: `docs/service-policies/01-member-auth.md` through `docs/service-policies/18-scale-reliability.md`
- Create: `.superpowers/sdd/miriyum-policy-audit.md`

**Interfaces:**
- Consumes: the user's classification rules and the current working-tree versions of every policy document.
- Produces: exactly 217 unique policy IDs with original status, proposed status, importance, concise reason, confirmed-preservation flag, plus before/after totals and explicit final lists for `팀원 상의 필요` and `TODO`.

- [x] Read every required document completely and inventory all policy rows.
- [x] Identify exactly 31 current `확정` IDs and mark them immutable.
- [x] Reassess each of the other 186 IDs independently against the supplied criteria, including dependency collapsing from derived decisions into `자동 추천 예정`.
- [x] Verify the matrix has 217 unique IDs, totals sum to 217, confirmed remains 31, and reassessed rows sum to 186.
- [x] Record cross-document contradictions and stale queue/summary language requiring later synchronization.

### Task 2: Canonical Checklist and Governance Rules

**Files:**
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/00-policy-template.md`

**Interfaces:**
- Consumes: `.superpowers/sdd/miriyum-policy-audit.md`.
- Produces: the canonical 217-row ledger, corrected importance/status counts, revised core-discussion queue, state definitions, and operating rules that all later documents follow.

- [x] Apply the audit matrix status and importance values to all 217 checklist rows without altering the 31 confirmed policies.
- [x] Rewrite status definitions and governance rules to encode the supplied classification criteria and the rule that importance alone does not imply team discussion.
- [x] Replace the core-discussion queue with only the final representative product decisions and remove derivative technical details.
- [x] Update totals, next-step text, and summary wording; confirm no `자동 추천 예정` row was promoted to `확정`.
- [x] Run mechanical uniqueness and count checks against the README tables.

### Task 3: Domain Policy Documents

**Files:**
- Modify as needed: `docs/service-policies/01-member-auth.md` through `docs/service-policies/18-scale-reliability.md`

**Interfaces:**
- Consumes: the updated canonical README ledger and the audit matrix.
- Produces: domain documents whose policy status, importance, TODO/team-discussion distinctions, next steps, and summaries agree with the canonical ledger.

- [x] Inspect each domain document in full, including short placeholder documents.
- [x] Synchronize every explicit policy status and importance marker with the canonical README.
- [x] Reword TODO and team-discussion sections so they use the correct classification reason and do not imply automatic confirmation.
- [x] Preserve confirmed policy substance and all unrelated pre-existing user edits.
- [x] Verify all 18 domain documents contain no status contradiction with the canonical ledger.

### Task 4: Cross-Cutting Service Documents

**Files:**
- Modify: `docs/service-definition.md`
- Modify: `docs/technical-architecture.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: the canonical README ledger and synchronized domain documents.
- Produces: consistent state definitions, decision summaries, next actions, and references across the service definition, technical architecture, and decision history.

- [x] Replace stale counts, queue descriptions, and status semantics with the final canonical classification.
- [x] Correct policy references that confuse `TODO`, `팀원 상의 필요`, and `자동 추천 예정` while retaining all confirmed decisions.
- [x] Ensure MiriYum naming, large-scale/multi-instance assumptions, and no-paid/no-extra-personal-data constraints remain explicit where relevant.
- [x] Keep the decision log historical record accurate without claiming that automatic recommendations were confirmed in this task.
- [x] Compare all three documents against the README and domain documents for contradictions.

### Task 5: Final Integrity and Scope Verification

**Files:**
- Verify: all files above
- Verify unchanged: `miriyum-service-blueprint.md`

**Interfaces:**
- Consumes: the complete working-tree result.
- Produces: evidence for the final report, including counts, retained core/TODO IDs with reasons, major automatic-recommendation moves, inconsistency fixes, `git diff --check`, branch name, and blueprint unchanged status.

- [x] Recompute the 217-row status totals and before/after transition matrix from source text.
- [x] Compare the final 31 confirmed IDs and their status/importance/content against the Task 1 immutable set.
- [x] Scan every policy ID and every status-bearing sentence for cross-document mismatch.
- [x] Run `git diff --check`, report the current branch, and verify `miriyum-service-blueprint.md` has no tracked diff caused by this task.
- [x] Conduct a broad independent review of scope, classification correctness, confirmed-policy preservation, and final-report evidence.
