# 영어 설명 문서 한글화 구현 계획

> **에이전트 작업자용:** REQUIRED SUB-SKILL: 이 계획은 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`를 사용해 Task별로 실행한다. 진행 상태는 체크박스(`- [ ]`)로 추적한다.

**목표:** 팀원이 저장소 문서의 목적과 규칙을 한글로 바로 이해할 수 있도록 영어 설명문을 번역하되, 실행 계약과 기술 식별자는 바꾸지 않는다.

**아키텍처:** 문서를 독자와 소유권 기준으로 나누어 번역한다. 번역 전후의 Markdown 링크 대상, fenced code block, inline code 식별자, 명령어, 경로, 상태값을 비교하여 설명만 바뀌었음을 증명한다.

**기술 스택:** Markdown, Git, PowerShell 7.4 이상

## Global Constraints

- 번역 대상은 아래 Task에 명시된 tracked Markdown 34개 파일뿐이다.
- 제목, 본문, 목록, 표의 설명 열, 체크리스트 문구와 사용자에게 표시되는 템플릿 문구는 자연스러운 한글로 번역한다.
- `backend`, `frontend`, `runtime`, `scaffold`, `endpoint`, `wrapper`, `lockfile`처럼 계약에서 반복되는 기술 용어는 필요한 경우 한글 설명 뒤에 원어를 괄호 또는 inline code로 남긴다.
- fenced code block의 내용, shell 명령, Gradle·pnpm script, Java·TypeScript 코드, Mermaid 식별자와 문법은 변경하지 않는다. Mermaid의 사람이 읽는 label만 번역할 수 있다.
- inline code의 command ID, 상태값(`ACTIVE`, `CONFIGURED`, `NOT CONFIGURED`, `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`, `NOT APPLICABLE`), 파일 경로, URL, 환경 변수, 클래스·패키지·라이브러리 이름과 버전은 변경하지 않는다.
- Markdown 링크의 target은 변경하지 않는다. 링크 label만 번역할 수 있다.
- GitHub template front matter의 key는 유지하고, 사용자에게 표시되는 `name`과 `about` 값만 번역한다.
- 정책 ID, ADR 번호, Issue·Pull Request·GitHub Project·CI·QA·API·DB·HTTP·JSON 같은 고유 약어는 유지한다.
- 번역 과정에서 요구사항, 상태, 범위, 소유권, 명령 순서, 실패 의미를 추가·삭제·완화하지 않는다.
- 원래 한글인 문장은 단순 문체 취향으로 다시 쓰지 않는다.
- 각 Task는 정확한 파일 목록, 링크 target, fenced code block, inline 식별자와 상태 어휘 보존을 검증한 뒤 종료한다.
- stage, commit, push, PR 생성은 별도 사용자 승인 전까지 수행하지 않는다.

---

### Task 1: 루트 협업·AI 문서를 한글화한다

**Files:**

- Modify: `AGENTS.md`
- Modify: `CONTRIBUTING.md`
- Modify: `.github/ISSUE_TEMPLATE/work-item.md`
- Modify: `.github/pull_request_template.md`
- Modify: `ai/document-routing.md`
- Modify: `ai/command-registry.md`
- Modify: `ai/integration-contracts.md`
- Modify: `ai/verification-and-completion.md`

**Interfaces:**

- Consumes: 현재 root route, command composition, integration, verification 계약
- Produces: 식별자와 상태 어휘는 동일하고 설명만 한글인 루트 협업 진입점

- [ ] **Step 1: 번역 전 불변 증거를 고정한다**

  각 파일의 Markdown 링크 target, fenced code block SHA-256, inline code token 목록과 현재 Git SHA를 ignored `.superpowers/sdd/korean-docs/` 아래에 기록한다.

- [ ] **Step 2: 사용자에게 보이는 설명을 번역한다**

  문서 제목, 설명 문단, 목록, 표 header·설명 cell과 template 입력 안내를 한글로 번역한다. `Answer Mode`, `Light Route`, `Work Route`는 각각 `답변 모드(Answer Mode)`, `간단 경로(Light Route)`, `작업 경로(Work Route)`처럼 원 식별자를 함께 남긴다.

- [ ] **Step 3: 루트 계약 불변성을 검증한다**

  링크 target과 fenced code block hash가 번역 전과 같고, root command ID 3개와 허용 상태값이 그대로인지 확인한다. `git diff --check`가 exit code 0이어야 한다.

---

### Task 2: backend·frontend 로컬 AI 문서를 한글화한다

**Files:**

- Modify: `backend/AGENTS.md`
- Modify: `backend/ai/document-routing.md`
- Modify: `backend/ai/implementation-guardrails.md`
- Modify: `backend/ai/command-registry.md`
- Modify: `backend/ai/verification-gates.md`
- Modify: `frontend/AGENTS.md`
- Modify: `frontend/ai/document-routing.md`
- Modify: `frontend/ai/implementation-guardrails.md`
- Modify: `frontend/ai/command-registry.md`
- Modify: `frontend/ai/verification-gates.md`

**Interfaces:**

- Consumes: Task 1의 한글 root route·통합·검증 계약
- Produces: endpoint command ID와 실행 의미는 동일하고 설명만 한글인 로컬 AI 계약

- [ ] **Step 1: endpoint 불변 증거를 고정한다**

  backend 3개, frontend 6개 command ID와 각 상태, 명령 문자열, 링크 target과 fenced code block을 번역 전 증거로 기록한다.

- [ ] **Step 2: backend 문서를 번역한다**

  Spring Boot·Gradle·MySQL·Flyway·Spring Security 이름은 유지하고 소유권, 활성화 조건, 금지 범위, 증거 의미를 한글로 번역한다.

- [ ] **Step 3: frontend 문서를 번역한다**

  React·TypeScript·Vite·Vitest·pnpm 이름은 유지하고 접근성, client 경계, 명령 상태와 완료 검사를 한글로 번역한다.

- [ ] **Step 4: endpoint 계약 불변성을 검증한다**

  모든 ID·상태·명령 순서·링크 target이 동일하고 root composition이 참조하는 8개 `CONFIGURED` ID가 그대로인지 확인한다. `git diff --check`가 exit code 0이어야 한다.

---

### Task 3: 정책·권한 관련 과거 실행 계획을 한글화한다

**Files:**

- Modify: `docs/superpowers/plans/2026-07-22-auth-authorization-policy-review.md`
- Modify: `docs/superpowers/plans/2026-07-22-miriyum-auto-recommendation-finalization.md`
- Modify: `docs/superpowers/plans/2026-07-22-miriyum-policy-decisions-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-22-miriyum-policy-reclassification.md`

**Interfaces:**

- Consumes: 기존 역사 기록의 Task 순서, 파일 목록, 명령과 완료 증거
- Produces: 역사적 사실과 실행 명령은 동일하고 작업 설명만 한글인 계획

- [ ] **Step 1: 역사 기록 불변 기준을 기록한다**

  각 파일의 Task 개수, checkbox 개수, fenced code block, 링크 target, 커밋 SHA·명령 문자열을 기록한다.

- [ ] **Step 2: 제목·제약·Task 설명·검증 문구를 번역한다**

  `Task N` 번호와 정책 ID, 파일 경로, 명령, 기대 결과, 역사적 commit·Issue·PR 식별자는 유지한다.

- [ ] **Step 3: 역사 기록 구조를 검증한다**

  Task·checkbox·code block·링크 target 개수가 번역 전과 같고 `git diff --check`가 exit code 0인지 확인한다.

---

### Task 4: 협업·정합성 관련 과거 실행 계획을 한글화한다

**Files:**

- Modify: `docs/superpowers/plans/2026-07-23-miriyum-admin-domain-policy-alignment.md`
- Modify: `docs/superpowers/plans/2026-07-23-miriyum-mvp-document-consistency.md`
- Modify: `docs/superpowers/plans/2026-07-23-payment-recovery-approval-alignment.md`
- Modify: `docs/superpowers/plans/2026-07-23-readme-github-wiki-implementation.md`

**Interfaces:**

- Consumes: 기존 문서 정합성 작업의 allowlist, 명령, 증거와 완료 기록
- Produces: 실행 의미를 바꾸지 않은 한글 계획

- [ ] **Step 1: 계획 구조 증거를 기록한다**

  Task·checkbox·fenced code block·링크 target·명령 문자열을 번역 전 기준으로 고정한다.

- [ ] **Step 2: 영어 설명과 표 header를 번역한다**

  이미 한글인 정책 본문은 유지하고 영어로 남은 전역 제약, 파일 설명, 단계 설명, checkpoint와 완료 증거 문구만 번역한다.

- [ ] **Step 3: 계획 구조를 검증한다**

  Task 순서, 정확한 파일 목록, 명령, 링크 target과 완료 상태가 바뀌지 않았는지 확인하고 `git diff --check`를 실행한다.

---

### Task 5: 팀 탐색용 혼합 문서를 한글화한다

**Files:**

- Modify: `docs/flowcharts/flowchart.md`
- Modify: `docs/superpowers/specs/2026-07-22-ai-workflow-documentation-contract-design.md`
- Modify: `docs/superpowers/specs/2026-07-24-backend-frontend-scaffolding-design.md`

**Interfaces:**

- Consumes: 현재 flow ID, Mermaid 구조, AI route 이름, scaffold 도구·버전 계약
- Produces: 팀원이 목적을 한글로 이해하면서도 기술 식별자를 그대로 사용할 수 있는 설계·탐색 문서

- [ ] **Step 1: 혼합 문서 불변 기준을 기록한다**

  Mermaid node ID·edge, 코드 블록, 링크 target, 버전과 명령 문자열을 번역 전 증거로 고정한다.

- [ ] **Step 2: 영어 제목·설명·사람이 읽는 diagram label을 번역한다**

  Mermaid 문법과 node ID는 유지하고 label만 번역한다. `Backend`, `Frontend`, `fail-closed` 같은 제목은 한글을 앞세우고 필요한 원어를 남긴다.

- [ ] **Step 3: 설계 의미와 diagram 구문을 검증한다**

  Mermaid fenced block 수, node ID·edge, scaffold 버전, 명령, 링크 target이 동일한지 확인하고 `git diff --check`를 실행한다.

---

### Task 6: 전수 조사에서 발견한 혼합 문서를 한글화한다

**Files:**

- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/adr/ADR-002-staged-technology-adoption.md`
- Modify: `docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md`
- Modify: `docs/superpowers/specs/2026-07-23-readme-github-wiki-design.md`
- Modify: `miriyum-service-blueprint.md`
- Revisit: `.github/pull_request_template.md`

**Interfaces:**

- Consumes: 전체 tracked Markdown 잔여 영어 조사 결과
- Produces: 기술 식별자는 보존하고 일반 설명과 표 머리글은 한글인 혼합 문서

- [ ] **Step 1: 추가 혼합 문서의 불변 기준을 기록한다**

  링크 target, fenced code block, inline code token, 정책·ADR 식별자, Wiki 페이지 이름, 버전과 명령 문자열을 번역 전 증거로 기록한다.

- [ ] **Step 2: 일반 설명용 영어를 한글화한다**

  기능 요구사항 표 머리글, ADR의 일반 개념어, 설계 문서의 일반 설명과 blueprint의 기술 설명 label을 한글 우선 표현으로 바꾼다. 라이브러리·도구·제품명, GitHub Wiki 페이지 식별자와 브랜드명은 유지한다.

- [ ] **Step 3: 구조와 계약 보존을 검증한다**

  링크, fenced code block, inline token, 버전, 명령, 식별자와 의미가 동일한지 확인하고 `git diff --check`를 실행한다.

---

### Task 7: 전체 번역 품질과 저장소 무결성을 검증한다

**Files:**

- Modify only when a translation defect is found: Tasks 1–6의 34개 파일
- Read only: 나머지 tracked Markdown

**Interfaces:**

- Consumes: Tasks 1–6의 번역 결과와 불변 증거
- Produces: 영어 설명 잔여와 계약 변형이 없는 최종 검증 보고서

- [ ] **Step 1: 영어 설명 잔여를 전수 조사한다**

  코드 fence 밖에서 한글 없이 영어 단어가 연속되는 행을 추출한다. 경로, URL, 명령, ID, 상태값, 라이브러리명, front matter key와 고유명사만 예외로 분류하고 일반 설명문은 모두 번역한다.

- [ ] **Step 2: 구조 불변성을 비교한다**

  34개 파일의 링크 target, fenced code block, inline code token, Task·checkbox 개수, command ID·상태·명령을 번역 전 증거와 비교한다.

- [ ] **Step 3: 저장소 문서 게이트를 실행한다**

  모든 tracked Markdown 링크가 존재하고 저장소 밖으로 traversal하지 않는지 검사한다. `git diff --check`와 정확한 35개 변경 경로(번역 대상 34개와 이 계획 1개) 비교가 모두 통과해야 한다.

- [ ] **Step 4: 최종 보고서를 작성한다**

  번역한 파일, 보존한 영어 예외 범주, 실행한 검증, 남은 위험과 stage·commit 상태를 ignored `.superpowers/sdd/korean-docs/final-report.md`에 기록한다.
