# MiriYum README and GitHub Wiki Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write an accurate root README and publish a 12-page GitHub Wiki that guide three reader groups to the version-controlled source documents without presenting unimplemented behavior as complete.

**Architecture:** The root `README.md` stays concise and links to canonical documents in the main repository. The GitHub Wiki is authored and committed in a temporary clone of the separate Wiki repository, with common navigation and source links, while product policy and technical decisions remain owned by the main repository.

**Tech Stack:** Markdown, Git, GitHub Wiki, PowerShell link-validation scripts

## Global Constraints

- Use `docs/superpowers/specs/2026-07-23-readme-github-wiki-design.md` at commit `5dbcd51` as the approved design.
- State clearly that the project is in the design and policy organization stage.
- Do not invent installation, run, test, deployment, CI, or implementation-completion claims.
- Treat `docs/`, `tastelock-service-decisions.md`, and the current service documents as canonical; the Wiki is explanatory navigation.
- Do not create a `wiki/` directory or implement automatic synchronization in the main repository.
- Do not modify or stage the existing user change in `docs/service-policies/01-member-auth.md`.
- Do not modify or stage the existing untracked file `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`.
- Keep the main-repository README commit and the Wiki-repository publication commit separate.
- If Wiki access or push fails, retain the local Wiki work and report the failure without claiming publication.

---

### Task 1: Record and protect the working baseline

**Files:**
- Create: `docs/superpowers/plans/2026-07-23-readme-github-wiki-implementation.md`
- Preserve: `docs/service-policies/01-member-auth.md`
- Preserve: `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`

**Interfaces:**
- Consumes: approved design commit `5dbcd51` and current branch `codex/miriyum-area3-mvp-docs`
- Produces: an explicit execution checklist and a recorded set of excluded user changes

- [ ] **Step 1: Confirm the branch and baseline changes**

  Run:

  ```powershell
  git status --short --branch
  git log -1 --oneline 5dbcd51
  ```

  Expected: the current branch is `codex/miriyum-area3-mvp-docs`, commit `5dbcd51` resolves, and the two declared user changes remain visible.

- [ ] **Step 2: Verify the plan covers every approved deliverable**

  Check that Tasks 2–5 cover canonical-source extraction, root README creation, 12 Wiki pages plus `_Sidebar.md` and `_Footer.md`, link validation, remote publication, published-path checks, and excluded-change checks.

- [ ] **Step 3: Commit only the implementation plan**

  Run:

  ```powershell
  git add -- docs/superpowers/plans/2026-07-23-readme-github-wiki-implementation.md
  git diff --cached --name-only
  git commit -m "docs: plan README and GitHub Wiki implementation"
  ```

  Expected: the staged list contains only the new 2026-07-23 plan, and the commit succeeds without including either pre-existing user change.

### Task 2: Extract only canonical facts needed by the two surfaces

**Files:**
- Read: `docs/service-definition.md`
- Read: `docs/technical-architecture.md`
- Read: `docs/service-policies/README.md`
- Read: `docs/service-policies/01-member-auth.md`
- Read: `docs/service-policies/02-store-onboarding.md`
- Read: `docs/service-policies/03-store-operation.md`
- Read: `docs/service-policies/04-reservation.md`
- Read: `docs/service-policies/05-waiting.md`
- Read: `docs/service-policies/06-menu-hold.md`
- Read: `docs/service-policies/07-course-tasting.md`
- Read: `docs/service-policies/08-payment-refund.md`
- Read: `docs/service-policies/09-checkin-noshow.md`
- Read: `docs/service-policies/10-waitlist-transfer.md`
- Read: `docs/service-policies/11-subscription.md`
- Read: `docs/service-policies/12-review-trust.md`
- Read: `docs/service-policies/13-ad-recommendation.md`
- Read: `docs/service-policies/14-analytics-report.md`
- Read: `docs/service-policies/15-admin-operation.md`
- Read: `docs/service-policies/16-notification.md`
- Read: `docs/service-policies/17-privacy-security.md`
- Read: `docs/service-policies/18-scale-reliability.md`
- Read: `tastelock-service-decisions.md`
- Read: `tastelock-service-blueprint.md`

**Interfaces:**
- Consumes: current working-tree content of the required canonical files
- Produces: facts grouped into project status, MVP boundary, user journeys, domain map, architecture direction, policy ownership, terminology, and historical context

- [ ] **Step 1: Read document outlines and source metadata as UTF-8**

  Run PowerShell that prints each required file name plus its Markdown headings, then read the sections selected by those headings in full.

  Expected: all 22 required files are readable; policy files `01` through `18` are present; no facts are taken from a missing or inferred source.

- [ ] **Step 2: Resolve source priority before drafting**

  Apply this order whenever documents differ: current service definition and policy documents for current product behavior, technical architecture for current technical direction, decision history for change context, and the blueprint only for explicitly labeled historical context.

  Expected: no Wiki or README statement promotes a historical blueprint idea over a current canonical decision.

- [ ] **Step 3: Separate stable explanations from mutable policy state**

  Use stable domain purposes and flow explanations in the Wiki. Link mutable status, priority, quantity, timeout, fee, and rollout values to `docs/service-policies/README.md` or the owning policy file instead of copying a policy-state table.

  Expected: the Wiki remains an onboarding and navigation layer rather than a second policy registry.

### Task 3: Write and commit the root README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: facts extracted in Task 2 and the approved README information architecture
- Produces: a concise repository landing page with verified relative links and an actual Wiki URL

- [ ] **Step 1: Create the README with the approved sections**

  Add:

  - title and one-line definition;
  - a prominent `현재 상태: 설계·정책 정리 단계` notice;
  - the user/store problem and intended solution flow;
  - core experiences, explicitly distinguishing the first MVP boundary from later candidates;
  - current technical direction, described as direction rather than completed implementation;
  - a canonical-document map with relative links;
  - paths for external visitors, new developers, and current team members;
  - a staged roadmap with no unsupported commands or completion badges;
  - the actual Wiki Home link.

- [ ] **Step 2: Validate every local Markdown target**

  Parse Markdown links in `README.md`, ignore `https://` targets and anchors, URL-decode local paths, and assert that every resolved path exists under the repository root.

  Expected: the validator reports zero missing local targets.

- [ ] **Step 3: Check content constraints and whitespace**

  Run:

  ```powershell
  rg -n "설계·정책 정리 단계|평가자|신규 개발자|현재 팀원|github\.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki" README.md
  git diff --check -- README.md
  ```

  Expected: project stage, all three reader paths, and the Wiki URL are present; `git diff --check` exits successfully; there are no fabricated installation, run, test, deployment, or success-badge sections.

- [ ] **Step 4: Commit only the README**

  Run:

  ```powershell
  git add -- README.md
  git diff --cached --name-only
  git commit -m "docs: add MiriYum project README"
  ```

  Expected: the staged list contains only `README.md`, and the commit succeeds without including existing user changes.

### Task 4: Author, validate, commit, and publish the GitHub Wiki

**Files in the temporary Wiki clone:**
- Create or update: `Home.md`
- Create or update: `Project-Overview.md`
- Create or update: `Core-User-Journeys.md`
- Create or update: `Getting-Started-for-Developers.md`
- Create or update: `Architecture-Guide.md`
- Create or update: `Domain-Guide.md`
- Create or update: `Policy-Guide.md`
- Create or update: `Documentation-Map.md`
- Create or update: `Decision-and-Change-History.md`
- Create or update: `Team-Workflow.md`
- Create or update: `Glossary.md`
- Create or update: `FAQ.md`
- Create or update: `_Sidebar.md`
- Create or update: `_Footer.md`

**Interfaces:**
- Consumes: canonical facts from Task 2, repository default branch, existing Wiki history, and the approved 12-page structure
- Produces: one Wiki commit pushed to `https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum.wiki.git`

- [ ] **Step 1: Clone and inspect the current Wiki without touching the main repository**

  Clone the Wiki into a uniquely named system temporary directory, list tracked files and recent commits, and preserve any existing page content for comparison.

  Expected: the clone succeeds and existing Wiki state is known before files are replaced or merged.

- [ ] **Step 2: Write all pages with common source and status sections**

  Each detailed page must contain:

  1. `이 페이지에서 알 수 있는 것`;
  2. its subject explanation and navigation;
  3. `공식 기준 문서`;
  4. `페이지 상태`;
  5. `마지막 확인일: 2026-07-23`.

  `Home.md` must expose all three reader routes. `_Sidebar.md` must use the approved six groups in order. `_Footer.md` must link the repository and README and state: `Wiki는 안내 문서이며 제품·정책의 정본은 메인 저장소입니다.`

- [ ] **Step 3: Validate Wiki page inventory and internal links**

  Assert that the temporary clone contains exactly the required 12 content pages plus `_Sidebar.md` and `_Footer.md` for this information architecture. Parse relative Wiki links and assert that every page-name target resolves to a corresponding `.md` file.

  Expected: all 14 required files exist and all internal links resolve.

- [ ] **Step 4: Validate main-repository links and policy-copy constraints**

  Parse all `https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/blob/<default-branch>/...` links, map their paths to the local main repository, URL-decode them, and assert that each target exists. Review Wiki diffs to confirm that mutable policy status tables, unsupported commands, and invented implementation claims are absent.

  Expected: zero broken repository links and no duplicated policy status registry.

- [ ] **Step 5: Commit only the Wiki files**

  Run in the temporary Wiki clone:

  ```powershell
  git status --short
  git diff --check
  git add -- Home.md Project-Overview.md Core-User-Journeys.md Getting-Started-for-Developers.md Architecture-Guide.md Domain-Guide.md Policy-Guide.md Documentation-Map.md Decision-and-Change-History.md Team-Workflow.md Glossary.md FAQ.md _Sidebar.md _Footer.md
  git diff --cached --name-only
  git commit -m "docs: build MiriYum project wiki"
  ```

  Expected: exactly the 14 Wiki files are staged, whitespace validation succeeds, and one Wiki commit is created.

- [ ] **Step 6: Publish the Wiki commit**

  Run:

  ```powershell
  git push origin HEAD:master
  ```

  Expected: the remote accepts the commit. If it does not, keep the temporary clone and report the exact failure and recovery path without claiming success.

### Task 5: Verify published routes and final change isolation

**Files:**
- Verify: `README.md`
- Verify: the 14 published Wiki files
- Preserve: all pre-existing user changes

**Interfaces:**
- Consumes: committed README and pushed Wiki revision
- Produces: evidence that local links, remote Wiki paths, reader routes, commits, and excluded changes are correct

- [ ] **Step 1: Fetch the Wiki revision back from the remote**

  Run `git fetch origin` in the temporary Wiki clone and compare the local Wiki commit with `origin/master`.

  Expected: both commit IDs match.

- [ ] **Step 2: Check published Home and reader routes**

  Open or request the published `Home`, then verify these paths return successfully and link onward:

  - external visitor: `Home` → `Project-Overview` → `Core-User-Journeys`;
  - new developer: `Home` → `Getting-Started-for-Developers` → `Architecture-Guide` → `Domain-Guide`;
  - current team member: `Home` → `Policy-Guide` → `Documentation-Map` → `Team-Workflow`.

  Expected: Home and every route page are available from the published Wiki; Sidebar and Footer navigation are present.

- [ ] **Step 3: Re-run final main-repository checks**

  Run:

  ```powershell
  git diff --check
  git status --short --branch
  git show --stat --oneline HEAD
  git show --stat --oneline HEAD~1
  ```

  Expected: the plan and README commits have isolated file scopes; the two user changes remain uncommitted and unchanged by this task; no `wiki/` directory exists in the main repository.

- [ ] **Step 4: Report exact outcomes**

  Report the created or modified main-repository files, the main commit IDs, the Wiki commit ID and push result, link-validation results, published-route results, and preserved user changes. Do not describe any remote action as successful unless its verification passed.
