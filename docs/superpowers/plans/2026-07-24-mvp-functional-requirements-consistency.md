# MiriYum MVP Functional Requirements Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #19 이후 남은 MVP 범위와 기능 요구사항 정합성 오류를 고치고 재현 가능한 문서 검증으로 회귀를 방지한다.

**Architecture:** 제품 비전과 상세 서비스 정책을 정본으로 유지하고, 기능 요구사항 색인은 기능 그룹의 MVP 관계와 경계만 요약한다. 정책 상태·중요도와 MVP 제공 여부를 분리하며, 정책 마스터 집계는 실제 217개 행에서 계산한 값과 일치시킨다.

**Tech Stack:** Markdown, PowerShell 기반 일회성 문서 검증, Git

## Global Constraints

- 새 제품 기능이나 정책 결정을 추가하지 않는다.
- PR #19에서 확정한 `AUTH-004`·`STORE-014`의 현재 MVP 제외 경계를 유지한다.
- 상세 정책 문서를 규칙의 단일 원본으로 유지한다.
- 현재 작업 브랜치의 사용자 변경을 수정·스테이징·커밋하지 않는다.
- 변경 허용 목록 밖의 파일은 수정하지 않는다.

---

### Task 1: 정합성 회귀 검증 작성

**Files:**
- Create ignored verifier: `.superpowers/sdd/verify-mvp-document-consistency.ps1`
- Inspect: `docs/05-functional-requirements.md`
- Inspect: `docs/service-policies/README.md`
- Inspect: `docs/service-policies/17-privacy-security.md`
- Inspect: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: UTF-8 Markdown 정본과 정책 마스터의 표 행
- Produces: 정합성 위반 시 종료 코드 1, 모든 검증 통과 시 종료 코드 0

- [x] **Step 1: 실패 검증 작성**

검증기는 다음 조건을 독립적으로 검사한다.

```text
TRANSFER 그룹은 초기 핵심이다.
AUTH-004와 STORE-014는 기능 요구사항에서 부분 초기 제외 경계를 가진다.
기본 추천과 Free 통계는 MVP에 포함되고 광고와 Pro 분석은 제외된다.
ADMIN, PRIV, SCALE 그룹은 초기 공통 기준이다.
정책 행 집계와 README 현재 요약이 일치한다.
AUTH-004가 TODO 목록과 현재 결정 스냅샷에 포함된다.
PRIV-001은 주류 기능 도입 전 연령 파생값을 생성·저장·조회하지 않는다.
대상 파일에 UTF-8 BOM과 여러 끝 개행이 없다.
```

- [x] **Step 2: 검증이 기존 문서에서 실패하는지 확인**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

Expected: exit 1이며 자동 승계 오분류, 정책 집계, AUTH-004 개인정보 경계 중 하나 이상을 보고한다.

### Task 2: 기능 요구사항과 도메인 경계 정렬

**Files:**
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/03-domain-model.md`
- Modify: `docs/04-user-flows.md`

**Interfaces:**
- Consumes: `docs/01-product-vision.md`의 초기 범위와 관련 서비스 정책의 MVP 경계
- Produces: 네 단계 MVP 관계와 기능 그룹별 경계 설명

- [x] **Step 1: 기능 요구사항 분류 체계 변경**

`MVP relation`에는 다음 네 값만 사용한다.

```text
초기 핵심
부분 초기
초기 공통 기준
비초기
```

- [x] **Step 2: 자동 승계와 혼합 범위 정렬**

자동 승계는 `초기 핵심`, 광고·추천과 분석은 `부분 초기`, 운영·개인정보·신뢰성은 `초기 공통 기준`으로 기록한다. `AUTH-004`와 `STORE-013·014`는 각 부분 초기 경계에서 제외한다.

- [x] **Step 3: 도메인 경계 문구 정렬**

`docs/03-domain-model.md`에서 비초기 대상을 코스·테이스팅, 구독, 광고 상품, Pro·고급 분석으로 한정하고 무료 기본 추천과 Free 기본 통계의 초기 책임을 보존한다.

- [x] **Step 3a: 가입 사용자 흐름 정렬**

`docs/04-user-flows.md`의 현재 가입 흐름은 `AUTH-009`의 만 14세 이상 가입 가능 여부 확인으로 명시하고, 비초기 `AUTH-004`·조건부 `PRIV-001`을 현재 관련 정책에서 제외한다.

- [x] **Step 4: 검증 재실행**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

Expected: 기능 요구사항 관련 검증은 통과하고 정책 집계·개인정보 관련 검증은 계속 실패한다.

### Task 3: 정책 집계와 개인정보 경계 정렬

**Files:**
- Modify: `docs/service-policies/00-policy-template.md`
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/17-privacy-security.md`
- Modify: `miriyum-service-decisions.md`

**Interfaces:**
- Consumes: 정책 마스터의 217개 행과 PR #19의 현재 경계
- Produces: 계산 가능한 현재 집계, 조건부 주류 개인정보 정책, 최신 결정 스냅샷

- [x] **Step 1: 정책 마스터와 템플릿의 현재 집계 갱신**

다음 현재 값을 문서화한다.

```text
확정 193
팀원 상의 필요 8
자동 추천 예정 0
TODO 16
핵심 19
필수 179
권장 18
검토 1
```

- [x] **Step 2: AUTH-004 TODO 경계 전파**

TODO 목록에 `AUTH-004`를 추가하고 현재 MVP에서는 연령 파생값을 생성·저장·조회하지 않는다는 조건을 `PRIV-001`에 기록한다.

- [x] **Step 3: 결정 기록 현재 스냅샷 추가**

PR #19 이후 현재 상태와 중요도, TODO 목록, MVP 경계를 `miriyum-service-decisions.md`의 새 변경 기록으로 추가한다.

- [x] **Step 4: 검증 재실행**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

Expected: 내용 검증은 통과하고 남은 인코딩 위반만 보고한다.

### Task 4: 인코딩과 끝 개행 정리

**Files:**
- Modify formatting only: `docs/service-policies/02-store-onboarding.md`
- Modify formatting only: `docs/service-policies/03-store-operation.md`
- Modify formatting only: `docs/service-policies/04-reservation.md`
- Modify formatting and policy boundary: `docs/service-policies/17-privacy-security.md`

**Interfaces:**
- Consumes: PR #19 병합 결과의 UTF-8 Markdown
- Produces: BOM 없는 UTF-8과 파일당 끝 개행 하나

- [x] **Step 1: BOM 제거**

대상 파일 첫 줄의 UTF-8 BOM을 제거하되 문구는 바꾸지 않는다.

- [x] **Step 2: 끝 개행 정규화**

문서 끝의 여러 빈 줄을 제거하고 끝 개행 하나만 유지한다.

- [x] **Step 3: 전체 정합성 검증**

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
git diff --check
```

Expected: 두 명령 모두 exit 0.

### Task 5: 범위 검토와 게시

**Files:**
- Review all files in the frozen allowlist

**Interfaces:**
- Consumes: 검증된 문서 diff
- Produces: `main` 대상 draft PR

- [x] **Step 1: 변경 범위 확인**

Run:

```powershell
git status --short
git diff --stat
git diff -- docs/03-domain-model.md docs/05-functional-requirements.md docs/service-policies/README.md docs/service-policies/17-privacy-security.md miriyum-service-decisions.md
```

Expected: 허용 목록 밖의 변경이 없다.

- [ ] **Step 2: 명시적 파일만 스테이징하고 커밋**

커밋 메시지:

```text
docs: align MVP requirement boundaries
```

- [ ] **Step 3: 원격 브랜치 푸시**

Run:

```powershell
git push -u origin codex/mvp-functional-requirements-consistency
```

Expected: 원격 추적 브랜치가 생성된다.

- [ ] **Step 4: draft PR 생성**

PR 제목:

```text
docs: align MVP requirement boundaries
```

PR 본문에는 변경 이유, PR #19 이후 남은 공백, 검증 명령과 결과를 기록한다.
