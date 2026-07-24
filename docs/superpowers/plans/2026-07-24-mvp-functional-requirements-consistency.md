# MiriYum MVP 기능 요구사항 정합성 구현 계획

> **에이전트 작업자 안내:** 이 계획은 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`를 사용해 작업별로 실행한다. 진행 상태는 체크박스(`- [ ]`)로 추적한다.

**목표:** PR #19 이후 남은 MVP 범위와 기능 요구사항 정합성 오류를 고치고 재현 가능한 문서 검증으로 회귀를 방지한다.

**구조:** 제품 비전과 상세 서비스 정책을 정본으로 유지하고, 기능 요구사항 색인은 기능 그룹의 MVP 관계와 경계만 요약한다. 정책 상태·중요도와 MVP 제공 여부를 분리하며, 정책 마스터 집계는 실제 217개 행에서 계산한 값과 일치시킨다.

**기술 스택:** Markdown, PowerShell 기반 일회성 문서 검증, Git

## 전역 제약

- 새 제품 기능이나 정책 결정을 추가하지 않는다.
- PR #19에서 확정한 `AUTH-004`·`STORE-014`의 현재 MVP 제외 경계를 유지한다.
- 상세 정책 문서를 규칙의 단일 원본으로 유지한다.
- 현재 작업 브랜치의 사용자 변경을 수정·스테이징·커밋하지 않는다.
- 변경 허용 목록 밖의 파일은 수정하지 않는다.

---

### 작업 1: 정합성 회귀 검증 작성

**파일:**
- 추적 제외 검증기 생성: `.superpowers/sdd/verify-mvp-document-consistency.ps1`
- 확인: `docs/05-functional-requirements.md`
- 확인: `docs/service-policies/README.md`
- 확인: `docs/service-policies/17-privacy-security.md`
- 확인: `miriyum-service-decisions.md`

**입출력:**
- 입력: UTF-8 Markdown 정본과 정책 마스터의 표 행
- 출력: 정합성 위반 시 종료 코드 1, 모든 검증 통과 시 종료 코드 0

- [x] **단계 1: 실패 검증 작성**

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

- [x] **단계 2: 검증이 기존 문서에서 실패하는지 확인**

실행:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

예상 결과: 종료 코드 1이며 자동 승계 오분류, 정책 집계, AUTH-004 개인정보 경계 중 하나 이상을 보고한다.

### 작업 2: 기능 요구사항과 도메인 경계 정렬

**파일:**
- 수정: `docs/05-functional-requirements.md`
- 수정: `docs/03-domain-model.md`
- 수정: `docs/04-user-flows.md`

**입출력:**
- 입력: `docs/01-product-vision.md`의 초기 범위와 관련 서비스 정책의 MVP 경계
- 출력: 네 단계 MVP 관계와 기능 그룹별 경계 설명

- [x] **단계 1: 기능 요구사항 분류 체계 변경**

`MVP 관계`에는 다음 네 값만 사용한다.

```text
초기 핵심
부분 초기
초기 공통 기준
비초기
```

- [x] **단계 2: 자동 승계와 혼합 범위 정렬**

자동 승계는 `초기 핵심`, 광고·추천과 분석은 `부분 초기`, 운영·개인정보·신뢰성은 `초기 공통 기준`으로 기록한다. `AUTH-004`와 `STORE-013·014`는 각 부분 초기 경계에서 제외한다.

- [x] **단계 3: 도메인 경계 문구 정렬**

`docs/03-domain-model.md`에서 비초기 대상을 코스·테이스팅, 구독, 광고 상품, Pro·고급 분석으로 한정하고 무료 기본 추천과 Free 기본 통계의 초기 책임을 보존한다.

- [x] **단계 3a: 가입 사용자 흐름 정렬**

`docs/04-user-flows.md`의 현재 가입 흐름은 `AUTH-009`의 만 14세 이상 가입 가능 여부 확인으로 명시하고, 비초기 `AUTH-004`·조건부 `PRIV-001`을 현재 관련 정책에서 제외한다.

- [x] **단계 4: 검증 재실행**

실행:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

예상 결과: 기능 요구사항 관련 검증은 통과하고 정책 집계·개인정보 관련 검증은 계속 실패한다.

### 작업 3: 정책 집계와 개인정보 경계 정렬

**파일:**
- 수정: `docs/service-policies/00-policy-template.md`
- 수정: `docs/service-policies/README.md`
- 수정: `docs/service-policies/17-privacy-security.md`
- 수정: `miriyum-service-decisions.md`

**입출력:**
- 입력: 정책 마스터의 217개 행과 PR #19의 현재 경계
- 출력: 계산 가능한 현재 집계, 조건부 주류 개인정보 정책, 최신 결정 스냅샷

- [x] **단계 1: 정책 마스터와 템플릿의 현재 집계 갱신**

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

- [x] **단계 2: AUTH-004 TODO 경계 전파**

TODO 목록에 `AUTH-004`를 추가하고 현재 MVP에서는 연령 파생값을 생성·저장·조회하지 않는다는 조건을 `PRIV-001`에 기록한다.

- [x] **단계 3: 결정 기록 현재 스냅샷 추가**

PR #19 이후 현재 상태와 중요도, TODO 목록, MVP 경계를 `miriyum-service-decisions.md`의 새 변경 기록으로 추가한다.

- [x] **단계 4: 검증 재실행**

실행:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
```

예상 결과: 내용 검증은 통과하고 남은 인코딩 위반만 보고한다.

### 작업 4: 인코딩과 끝 개행 정리

**파일:**
- 형식만 수정: `docs/service-policies/02-store-onboarding.md`
- 형식만 수정: `docs/service-policies/03-store-operation.md`
- 형식만 수정: `docs/service-policies/04-reservation.md`
- 형식과 정책 경계 수정: `docs/service-policies/17-privacy-security.md`

**입출력:**
- 입력: PR #19 병합 결과의 UTF-8 Markdown
- 출력: BOM 없는 UTF-8과 파일당 끝 개행 하나

- [x] **단계 1: BOM 제거**

대상 파일 첫 줄의 UTF-8 BOM을 제거하되 문구는 바꾸지 않는다.

- [x] **단계 2: 끝 개행 정규화**

문서 끝의 여러 빈 줄을 제거하고 끝 개행 하나만 유지한다.

- [x] **단계 3: 전체 정합성 검증**

실행:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/verify-mvp-document-consistency.ps1
git diff --check
```

예상 결과: 두 명령 모두 종료 코드 0.

### 작업 5: 범위 검토와 게시

**파일:**
- 고정된 변경 허용 목록의 모든 파일 검토

**입출력:**
- 입력: 검증된 문서 diff
- 출력: `main` 대상 Draft PR

- [x] **단계 1: 변경 범위 확인**

실행:

```powershell
git status --short
git diff --stat
git diff -- docs/03-domain-model.md docs/05-functional-requirements.md docs/service-policies/README.md docs/service-policies/17-privacy-security.md miriyum-service-decisions.md
```

예상 결과: 허용 목록 밖의 변경이 없다.

- [x] **단계 2: 명시적 파일만 스테이징하고 커밋**

커밋 메시지:

```text
docs: align MVP requirement boundaries
```

- [x] **단계 3: 원격 브랜치 푸시**

실행:

```powershell
git push -u origin codex/mvp-functional-requirements-consistency
```

예상 결과: 원격 추적 브랜치가 생성된다.

- [x] **단계 4: Draft PR 생성**

PR 제목:

```text
docs: align MVP requirement boundaries
```

PR 본문에는 변경 이유, PR #19 이후 남은 공백, 검증 명령과 결과를 기록한다.
