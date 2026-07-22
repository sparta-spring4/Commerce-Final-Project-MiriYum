# Authentication and Authorization Policy Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Clarify account boundaries, Kakao account linking, central-session revocation and reuse handling in the MiriYum member authentication policy without changing the approved server-session architecture.

**Architecture:** Keep Spring Security and Spring Session Data Redis with Valkey as the central session authority, and continue to reject browser JWTs. Update only the member-authentication policy so account type, per-store authorization, provider identity linking, session invalidation and takeover-risk inputs form one consistent policy.

**Tech Stack:** Markdown policy documents, Git, ripgrep, PowerShell.

## Global Constraints

- The policy body change is limited to docs/service-policies/01-member-auth.md.
- docs/technical-architecture.md is a read-only consistency reference.
- Use 식당 대표자 consistently; do not introduce a store employee or shared store account.
- Public role values, Kakao login and login-method linking must never promote an account type.
- Browser authentication remains a central server session; Access and Refresh JWTs remain unused.
- Existing idle expiry, absolute expiry and concurrent-session limits remain unchanged.
- Do not create a separate review-report document; record final verification in the conversation and pull request.
- Preserve unrelated policy decisions and follow-up review boundaries.

---

## File Map

- Modify: docs/service-policies/01-member-auth.md
  - AUTH-001: account types, representative-login state and per-store authorization.
  - AUTH-002 and AUTH-005: Kakao login and safe existing-account linking.
  - AUTH-007: logout, revocation, revoked-session reuse and observable acceptance conditions.
  - AUTH-012: revoked-session reuse as an account-takeover risk input.
  - Whole file: duplicated AUTH-006 linkage block, terminology and typographical cleanup.
- Read only: docs/technical-architecture.md
  - Confirm Spring Security, Spring Session Data Redis, Valkey, secure session cookie and browser-JWT exclusion.
- Existing specification: docs/superpowers/specs/2026-07-22-auth-authorization-policy-review-design.md
  - Source of the approved scope, flows, failure behavior and acceptance conditions.

---

### Task 1: Clarify account-type and per-store authorization boundaries

**Files:**
- Modify: docs/service-policies/01-member-auth.md:19-85
- Test: docs/service-policies/01-member-auth.md

**Interfaces:**
- Consumes: AUTH-001 account types, STORE-003 and STORE-005 verification outcomes, central account and store-membership state.
- Produces: explicit representative login versus per-store authorization rules used by AUTH-005, AUTH-007 and all store-management requests.

- [ ] **Step 1: Run the policy-gap search**

Run:

~~~powershell
rg -n "가입·로그인 가능 상태와 매장별 운영 권한|클라이언트가 전달한 역할|모든 매장 관리 요청" docs/service-policies/01-member-auth.md
~~~

Expected: no matches, demonstrating that the current policy does not state these authorization boundaries explicitly.

- [ ] **Step 2: Replace the ambiguous representative-account bullet**

In AUTH-001 현재 확정 사항, replace the sentence saying that a representative account is simply a business-verified account with the following exact policy text:

~~~markdown
- 식당 대표자 계정의 가입·로그인 가능 상태와 매장별 운영 권한은 구분한다.
- 가입·본인 확인을 마친 식당 대표자 계정이라도 해당 매장의 사업자 검증과 입점 승인이 완료되고 유효한 매장 소속 관계가 생성되기 전에는 그 매장의 운영 기능을 사용할 수 없다.
- 클라이언트가 전달한 역할 값, 일반 사용자 계정의 로그인 수단 연결, 카카오 로그인 성공과 이메일·휴대전화 일치만으로 계정 유형이나 매장 운영 권한을 생성·변경하지 않는다.
- 모든 매장 관리 요청은 중앙의 계정 유형, 계정 상태, 매장 소속 관계와 해당 매장의 승인 상태를 함께 검증한다. 어느 하나라도 확인할 수 없거나 유효하지 않으면 실패 폐쇄한다.
~~~

Keep the existing rules that staff accounts and shared accounts are unsupported, that the representative account is one-person-one-account, and that account types never share or auto-promote authentication state.

- [ ] **Step 3: Tighten the multi-instance authorization rule**

Add the following exact bullets to AUTH-001 다중 서버·다중 인스턴스 원칙:

~~~markdown
- 공개 가입·로그인·계정 연결 API는 요청 본문의 역할 값을 권한 근거로 사용하지 않으며, 서버가 허용한 계정 유형별 흐름과 중앙 상태만 신뢰한다.
- 매장 관리 요청은 매번 중앙 권한 버전과 매장별 소속·승인 상태를 확인한다. 권한 회수 또는 승인 상태 변경 뒤에는 캐시·기존 세션·인스턴스 로컬 상태로 이전 권한을 계속 인정하지 않는다.
~~~

- [ ] **Step 4: Add observable acceptance conditions and a decision record**

Add this subsection immediately before AUTH-001 결정 기록:

~~~markdown
### 관측 가능한 인수 조건

- 일반 사용자 가입·로그인·카카오 연결 요청에 식당 대표자나 플랫폼 운영자 역할 값을 넣어도 계정 유형과 권한이 바뀌지 않는다.
- 식당 대표자 계정으로 로그인했더라도 매장별 사업자 검증·입점 승인·유효한 소속 관계 가운데 하나라도 없으면 모든 인스턴스에서 해당 매장 관리 요청이 거부된다.
- 직원·공용 계정 생성 요청과 대표자 인증정보 공유를 전제로 한 권한 추가 요청은 거부되고 감사 가능한 보안 사건으로 남는다.
~~~

Append this decision-record row:

~~~markdown
| 2026-07-22 | AUTH-001 | 대표자 로그인 상태와 매장별 운영 권한을 분리하고 공개 역할 값·계정 연결·카카오 로그인에 의한 자가 승격 차단 | 확정 | 계정 유형과 매장 귀속을 중앙 상태로 검증하여 권한 탈취·승격 방지 |
~~~

- [ ] **Step 5: Verify Task 1**

Run:

~~~powershell
rg -n "가입·로그인 가능 상태와 매장별 운영 권한|클라이언트가 전달한 역할|모든 매장 관리 요청|관측 가능한 인수 조건" docs/service-policies/01-member-auth.md
git diff --check
~~~

Expected: all four policy phrases are present under AUTH-001 and git diff --check prints no errors.

- [ ] **Step 6: Commit Task 1**

Run:

~~~powershell
git add docs/service-policies/01-member-auth.md
git commit -m "docs: clarify representative authorization boundary"
~~~

Expected: one commit containing only the AUTH-001 policy change.

---

### Task 2: Make Kakao existing-account linking explicit and non-promoting

**Files:**
- Modify: docs/service-policies/01-member-auth.md:87-144, 252-325
- Test: docs/service-policies/01-member-auth.md

**Interfaces:**
- Consumes: authenticated existing general-user account, Kakao provider identifier, verified contact ownership, central account state.
- Produces: one immutable provider-to-account link for general-user authentication without account-type or permission changes.

- [ ] **Step 1: Run the Kakao-linking gap search**

Run:

~~~powershell
rg -n "카카오 제공자 식별자|제공자와 제공자 식별자|다른 계정에 이미 연결|대표자나 플랫폼 운영자 권한" docs/service-policies/01-member-auth.md
~~~

Expected: no complete set of matches; the current policy describes safe linking generally but does not specify provider-identifier uniqueness and non-promotion together.

- [ ] **Step 2: Resolve the AUTH-002 scope contradiction**

In AUTH-002 향후 계획, remove the two bullets that defer adding email-password login to a Kakao-only account, because AUTH-005 already confirms this linking flow. Preserve the future-provider review for Naver, Google and Apple and the current rule that individual login-method unlinking is not provided.

Add this bullet to AUTH-002 현재 확정 사항:

~~~markdown
- 카카오 로그인과 이메일·비밀번호 로그인 수단의 추가 연결은 AUTH-005의 기존 계정 재인증·중복 충돌·멱등 연결 기준을 따르며, 연결 결과는 일반 사용자 계정 유형과 권한을 바꾸지 않는다.
~~~

- [ ] **Step 3: Add exact Kakao provider-link rules to AUTH-005**

Add these bullets after the current rule requiring authentication of the existing account:

~~~markdown
- 카카오 로그인과 카카오 제공자 식별자 연결은 일반 사용자 계정에만 허용하며 식당 대표자·플랫폼 운영자 계정의 로그인 수단이나 권한 부여 경로로 사용하지 않는다.
- 카카오 제공자 식별자가 이미 연결되어 있으면 새 계정 생성이나 재연결을 시도하지 않고 연결된 일반 사용자 계정의 현재 상태·제재를 적용한다.
- 연결되지 않은 카카오 제공자 식별자를 기존 일반 사용자 계정에 추가하려면 해당 기존 계정의 유효한 재인증을 먼저 완료해야 한다. 카카오가 제공한 이메일과 등록 이메일의 일치만으로 자동 연결하지 않는다.
- 카카오 제공자 식별자가 다른 계정에 이미 연결되어 있으면 연결·이동·병합을 모두 중단하고 계정 복구를 안내한다. 관리자 재량으로 연결 소유 계정을 즉시 바꾸지 않는다.
- 로그인 수단 연결은 계정 유형과 서비스 권한을 변경하지 않으며, 일반 사용자 계정에 카카오를 연결한 결과로 식당 대표자·플랫폼 운영자 권한이 생기지 않는다.
~~~

- [ ] **Step 4: Add central uniqueness and idempotency rules**

Add these bullets to AUTH-005 다중 서버·다중 인스턴스 원칙:

~~~markdown
- 외부 로그인 연결은 제공자와 제공자 식별자의 복합 유일성 제약으로 보호하고, 같은 카카오 식별자가 둘 이상의 중앙 계정에 연결되지 않게 한다.
- 연결 요청 식별자, 대상 계정 버전과 기존 계정 재인증 상태를 하나의 처리 경계에서 검증한다. 중복·역순 콜백에는 이미 확정된 연결 결과를 반환하고 계정이나 연결 관계를 추가 생성하지 않는다.
- 카카오 연결 확정 전후에 계정 유형과 권한 버전이 같음을 검증하며, 불일치하거나 중앙 상태를 확인할 수 없으면 연결을 실패 폐쇄한다.
~~~

- [ ] **Step 5: Add acceptance conditions and a decision record**

Add this subsection before AUTH-005의 AUTH-006 연계 subsection:

~~~markdown
### 관측 가능한 인수 조건

- 같은 카카오 콜백과 연결 요청을 여러 인스턴스에 중복·역순 전달해도 일반 사용자 계정과 제공자 연결은 각각 하나만 존재한다.
- 카카오 이메일이 기존 이메일과 같아도 기존 계정 재인증 전에는 자동 연결되지 않는다.
- 다른 계정에 연결된 카카오 식별자는 이동·병합되지 않고, 일반 사용자 계정에 카카오를 연결해도 계정 유형과 식당 대표자·플랫폼 운영자 권한이 바뀌지 않는다.
~~~

Append this decision-record row:

~~~markdown
| 2026-07-22 | AUTH-002·AUTH-005 | 카카오 제공자 식별자의 중앙 유일성·기존 계정 재인증·추정 연결 금지와 계정 유형 불변 기준 확정 | 확정 | 계정 탈취·중복 연결·로그인 수단을 통한 권한 승격 방지 |
~~~

- [ ] **Step 6: Verify Task 2**

Run:

~~~powershell
rg -n "카카오 제공자 식별자|복합 유일성 제약|기존 일반 사용자 계정의 유효한 재인증|계정 유형과 서비스 권한을 변경하지" docs/service-policies/01-member-auth.md
git diff --check
~~~

Expected: all Kakao uniqueness, reauthentication and non-promotion rules are present and git diff --check prints no errors.

- [ ] **Step 7: Commit Task 2**

Run:

~~~powershell
git add docs/service-policies/01-member-auth.md
git commit -m "docs: secure Kakao account linking"
~~~

Expected: one commit containing the AUTH-002 and AUTH-005 policy changes.

---

### Task 3: Define idempotent logout and revoked-session reuse handling

**Files:**
- Modify: docs/service-policies/01-member-auth.md:529-589
- Test: docs/service-policies/01-member-auth.md

**Interfaces:**
- Consumes: Spring Session Data Redis session state, account session index or version, session revocation reason, common clock.
- Produces: deterministic logout, immediate denial of revoked IDs and privacy-preserving reuse security events.

- [ ] **Step 1: Run the session-reuse gap search**

Run:

~~~powershell
rg -n "폐기된 세션 ID|파생 식별값|반복 로그아웃|AUTH-012의 위험 판정 입력" docs/service-policies/01-member-auth.md
~~~

Expected: no matches, demonstrating that the current policy revokes sessions but does not fully define replay observation.

- [ ] **Step 2: Add logout and revoked-ID behavior to AUTH-007**

Add these bullets after the current logout rule:

~~~markdown
- 로그아웃은 현재 중앙 세션의 존재 여부와 관계없이 멱등하게 처리한다. 유효한 세션은 한 번만 폐기하고 이미 만료·폐기됐거나 존재하지 않는 세션도 새 세션을 만들지 않은 채 같은 종료 응답으로 수렴하며 브라우저 쿠키를 삭제한다.
- 폐기되거나 세션 ID 교체로 무효화된 세션 ID가 다시 제시되면 인증을 거부하고 브라우저 쿠키를 삭제한다. 폐기 전 권한이나 인스턴스 로컬 상태로 요청을 허용하지 않는다.
- 폐기 세션 재사용 보안 사건에는 세션 ID 원문을 남기지 않고 서버 비밀키 기반 파생 식별값, 계정 내부 식별자, 폐기 사유·시각, 재사용 시각과 요청 상관관계만 기록한다.
- 일반적인 세션 ID 교체 직후의 단발성 오래된 요청만으로 계정을 자동 잠그지 않는다. 비밀번호 재설정·수동 복구·탈퇴·제재·보안 잠금이나 사용자 전체 세션 종료 뒤 폐기 ID가 반복 사용되면 AUTH-012의 위험 판정 입력으로 연결한다.
~~~

- [ ] **Step 3: Add multi-instance and failure-closed rules**

Add these bullets to AUTH-007 다중 서버·다중 인스턴스 원칙:

~~~markdown
- 세션 폐기와 폐기 사유·파생 식별값 기록은 중앙 세션 상태를 기준으로 한 번만 확정한다. 여러 인스턴스의 반복 로그아웃·동시 요청은 폐기된 세션을 되살리지 않고 같은 종료 결과로 수렴한다.
- 폐기 세션이 다시 제시되면 모든 인스턴스가 동일하게 인증을 거부하고 재사용 보안 사건을 계정·파생 식별값·폐기 사건 기준으로 멱등 기록한다.
- 세션 폐기는 확정됐지만 감사·알림 같은 후속 처리가 실패한 경우에도 세션을 다시 유효하게 만들지 않는다. 후속 실패만 내구성 작업으로 재시도하고 장기 실패는 운영자 경보로 남긴다.
~~~

- [ ] **Step 4: Add observable acceptance conditions**

Add this subsection immediately before AUTH-007 결정 기록:

~~~markdown
### 관측 가능한 인수 조건

- 같은 로그아웃 요청을 반복하거나 여러 인스턴스에 동시에 보내도 중앙 세션은 한 번만 폐기되고 모든 응답에서 쿠키가 삭제된다.
- 로그인·권한 변경·추가 인증으로 교체된 이전 세션 ID와 복구·탈퇴·제재로 폐기된 세션 ID는 모든 인스턴스에서 인증이 거부된다.
- 폐기 세션 재사용 사건에는 세션 ID 원문이 없고 파생 식별값·폐기 사유·재사용 시각이 남으며, 후속 감사·알림 실패가 세션 폐기를 되돌리지 않는다.
- 세션 만료시간과 계정 유형별 동시 세션 상한은 기존 확정값을 유지한다.
~~~

Append this decision-record row:

~~~markdown
| 2026-07-22 | AUTH-007·AUTH-012 | 로그아웃 멱등성·폐기 세션 재사용 거부·원문 없는 보안 사건과 반복 재사용 위험 연계 확정 | 확정 | 중앙 세션 구조에서 Refresh Token 폐기·재사용 통제와 동등한 보안 경계 명시 |
~~~

- [ ] **Step 5: Verify Task 3**

Run:

~~~powershell
rg -n "로그아웃은 현재 중앙 세션의 존재 여부와 관계없이 멱등|폐기되거나 세션 ID 교체로 무효화|서버 비밀키 기반 파생 식별값|세션 만료시간과 계정 유형별 동시 세션 상한" docs/service-policies/01-member-auth.md
git diff --check
~~~

Expected: logout, reuse, privacy and unchanged-limit acceptance rules are present and git diff --check prints no errors.

- [ ] **Step 6: Commit Task 3**

Run:

~~~powershell
git add docs/service-policies/01-member-auth.md
git commit -m "docs: define revoked session reuse handling"
~~~

Expected: one commit containing only the AUTH-007 session-policy change.

---

### Task 4: Connect revoked-session reuse to account-takeover risk

**Files:**
- Modify: docs/service-policies/01-member-auth.md:797-850
- Test: docs/service-policies/01-member-auth.md

**Interfaces:**
- Consumes: AUTH-007 revoked-session reuse event, revocation reason, repetition, account security event.
- Produces: low-risk recording for ordinary stale requests and high-confidence escalation input after security-sensitive revocation.

- [ ] **Step 1: Run the AUTH-012 linkage gap search**

Run:

~~~powershell
rg -n "폐기 세션 재사용|세션 교체 직후|전체 세션 철회 뒤 반복" docs/service-policies/01-member-auth.md
~~~

Expected: AUTH-012 has no explicit rules matching all three concepts.

- [ ] **Step 2: Add risk-classification inputs**

Add these bullets to AUTH-012 현재 확정 사항:

~~~markdown
- AUTH-007에서 전달한 폐기 세션 재사용 사건을 위험 판정 입력으로 사용하되 세션 ID 원문은 사용하지 않는다.
- 로그인·권한 변경·추가 인증에 따른 세션 교체 직후의 단발성 오래된 요청은 낮은 위험으로 기록하고 그것만으로 계정을 잠그지 않는다.
- 비밀번호 재설정·수동 복구·탈퇴 취소 후 재인증·제재·보안 잠금·사용자 전체 세션 종료처럼 보안상 전체 세션을 철회한 뒤 같은 폐기 세션 파생 식별값이 반복 사용되면 높은 위험 후보로 판정한다.
- 높은 위험 후보는 기존 로그인 실패·연락처 변경·사용자 신고와 함께 중앙 위험 사건에서 평가하며, 정책 버전의 판정 조건을 충족하면 기존 높은 위험 절차에 따라 모든 세션 철회와 보안 잠금을 적용한다.
~~~

- [ ] **Step 3: Add multi-instance event convergence**

Add these bullets to AUTH-012 다중 서버·다중 인스턴스 원칙:

~~~markdown
- 폐기 세션 재사용 사건은 계정, 세션 파생 식별값, 원 폐기 사건과 정책 버전을 기준으로 중앙 위험 사건에 멱등 연결한다. 여러 인스턴스가 같은 재사용을 감지해도 위험 횟수와 잠금 전이가 중복 반영되지 않는다.
- 중앙 폐기 사유나 위험 상태를 확인할 수 없으면 재사용 요청은 계속 인증 거부하되 근거 없이 자동 잠금을 확정하지 않고 위험 사건을 보류·경보 상태로 남긴다.
~~~

- [ ] **Step 4: Add acceptance conditions and a decision record**

Add this subsection before AUTH-012 향후 계획:

~~~markdown
### 관측 가능한 인수 조건

- 일반 세션 교체 직후의 단발성 이전 ID 요청은 인증이 거부되고 낮은 위험으로 기록되지만 그것만으로 계정이 잠기지 않는다.
- 복구·제재·보안 잠금·전체 세션 종료 뒤 같은 폐기 세션의 반복 사용은 하나의 중앙 위험 사건에 합쳐지고 조건 충족 시 높은 위험 절차로 전환된다.
- 다중 인스턴스가 같은 재사용을 동시에 감지해도 보안 잠금과 전체 세션 철회는 한 번만 확정되며 세션 ID 원문은 위험·감사 기록에 남지 않는다.
~~~

Append this decision-record row:

~~~markdown
| 2026-07-22 | AUTH-012 | 단발성 교체 세션은 낮은 위험 기록, 보안상 전체 철회 뒤 반복 재사용은 높은 위험 후보로 분리 | 확정 | 정상 동시 요청 오탐을 줄이면서 탈취 세션의 반복 사용을 탐지 |
~~~

- [ ] **Step 5: Verify Task 4**

Run:

~~~powershell
rg -n "AUTH-007에서 전달한 폐기 세션 재사용|단발성 오래된 요청은 낮은 위험|같은 폐기 세션 파생 식별값이 반복|중앙 위험 사건에 멱등 연결" docs/service-policies/01-member-auth.md
git diff --check
~~~

Expected: all four risk-classification and convergence phrases are present and git diff --check prints no errors.

- [ ] **Step 6: Commit Task 4**

Run:

~~~powershell
git add docs/service-policies/01-member-auth.md
git commit -m "docs: link revoked sessions to takeover risk"
~~~

Expected: one commit containing only the AUTH-012 policy change.

---

### Task 5: Remove duplication, normalize terminology and complete verification

**Files:**
- Modify: docs/service-policies/01-member-auth.md:1-850
- Read only: docs/technical-architecture.md:162-309, 443-453
- Test: repository policy-document diff

**Interfaces:**
- Consumes: completed AUTH-001, AUTH-002, AUTH-005, AUTH-007 and AUTH-012 changes from Tasks 1-4.
- Produces: one internally consistent member-auth policy aligned with the approved technical architecture.

- [ ] **Step 1: Prove the duplicated AUTH-006 linkage block exists**

Run:

~~~powershell
rg -n "^## AUTH-006 연계" docs/service-policies/01-member-auth.md
~~~

Expected before cleanup: one match at the current line 326. The intended AUTH-005 linkage is the level-three heading at the current line 298 and must remain.

- [ ] **Step 2: Remove the repeated AUTH-005 tail**

Delete the complete repeated block from the current line 326 level-two heading AUTH-006 연계 — 확정 through the final repeated AUTH-005 decision-record row at the current line 352. Stop immediately before the real top-level AUTH-006 비밀번호·복구·계정 잠금 policy heading at the current line 354.

Retain the intended level-three AUTH-006 연계 — 확정 subsection at the current line 298, its four follow-up review entries and the first AUTH-005 decision-record table ending at the current line 324.

- [ ] **Step 3: Normalize terminology and correct known sentence defects**

Across docs/service-policies/01-member-auth.md:

- Replace 매장 대표자 with 식당 대표자 where it refers to the account type or person.
- Preserve 매장 when it refers to a physical store, store membership or per-store approval.
- Correct 공용 게정 to 공용 계정.
- Correct 앙니라 to 아니라.
- Correct 카카오 로그인을ㄹ to 카카오 로그인을.
- Correct 안전정인 to 안전한.
- Correct 소셜 로그인을 기본으로 씌되 to 소셜 로그인을 기본으로 쓰되.
- Remove the incomplete AUTH-002 sentence 현재 로그인 방식은 카카오와 이메일·비밀번호로 확정했고, because the surrounding bullets already state the complete policy.
- Keep policy identifiers, status values, timeouts, session limits and approved numeric values unchanged.

- [ ] **Step 4: Run structural and terminology checks**

Run:

~~~powershell
rg -n "^## AUTH-[0-9]{3}" docs/service-policies/01-member-auth.md
rg -n "매장 대표자|공용 게정|앙니라|로그인을ㄹ|안전정인|기본으로 씌되|확정했고,$" docs/service-policies/01-member-auth.md
rg -n "식당 대표자|카카오 제공자 식별자|폐기 세션|AUTH-012" docs/service-policies/01-member-auth.md
~~~

Expected:

- Each top-level AUTH-001 through AUTH-012 heading appears once.
- The malformed or obsolete terminology search prints no matches.
- The required representative, Kakao, revoked-session and takeover-risk terms are present.

- [ ] **Step 5: Compare against the technical architecture**

Run:

~~~powershell
rg -n "Spring Security|Spring Session Data Redis|JWT를 사용하지|로그아웃|세션 ID 재발급|계정 유형|직원용 계정" docs/technical-architecture.md docs/service-policies/01-member-auth.md
~~~

Expected: both documents consistently state central Spring sessions, no browser JWT, ID rotation and invalidation, independent account types, and no staff account.

- [ ] **Step 6: Run final repository verification**

Run:

~~~powershell
git diff --check origin/main...HEAD
git diff --name-only origin/main...HEAD
git status --short
~~~

Expected:

- git diff --check prints no errors.
- Changed policy body is limited to docs/service-policies/01-member-auth.md.
- Other changed files are only the approved design and implementation-plan documents.
- No unstaged or untracked implementation files remain.

- [ ] **Step 7: Review the final policy diff**

Run:

~~~powershell
git diff origin/main...HEAD -- docs/service-policies/01-member-auth.md
~~~

Expected: the diff contains the approved authorization, Kakao, session and risk changes plus duplicate/typographical cleanup, with no changes to session timeout values or concurrent-session limits.

- [ ] **Step 8: Commit final cleanup if Step 2 or Step 3 changed the file**

Run:

~~~powershell
git add docs/service-policies/01-member-auth.md
git commit -m "docs: finalize auth policy consistency"
~~~

Expected: one cleanup commit; if no cleanup remains after prior task commits, record that no additional commit was necessary.

---

## Completion Evidence for Conversation and Pull Request

Report these facts without creating a separate report file:

- Base commit and working branch.
- Policy sections changed: AUTH-001, AUTH-002, AUTH-005, AUTH-007 and AUTH-012.
- Confirmation that staff/shared accounts remain unsupported.
- Confirmation that account linking cannot promote account type or store permissions.
- Confirmation that browser JWT remains unused and central-session revocation/reuse behavior is defined.
- Duplicate-block and terminology cleanup performed.
- Exact final verification commands and their outputs.
- Final changed-file list and commit list.
