# MVP2 Dev Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 최신 `dev`를 `mvp2`에 통합하고 PR #182를 검증된 Ready-for-review 상태로 전환한다.

**Architecture:** 기존 `mvp2` 기능 동작은 유지하되 최신 `dev`의 패키지 구조와 공개 API 경로를 기준선으로 사용한다. 충돌은 두 파일에서만 해결하고, 자동 병합 결과와 전체 backend/frontend 검증을 감사한 뒤 merge commit을 `mvp2`에 게시한다.

**Tech Stack:** Git, Java 21, Spring Boot 4.1.0, Gradle 9.6.1, Flyway/MySQL Testcontainers, OpenAPI YAML, React/TypeScript/Vite/pnpm, GitHub Actions

## Global Constraints

- 주 Issue/PR은 GitHub PR #182이며 대상은 `mvp2 -> dev`다.
- 허용 경로는 병합으로 생성되는 변경, 충돌 파일 2개, 이 계획 파일, PR #182 메타데이터로 제한한다.
- `dev`의 최신 구조인 `MenuTransactionFacade`와 복수형 `/api/v1/store-operators/**` 경로를 유지한다.
- `mvp2`의 지오코딩 mock/검증 및 `503 ServiceUnavailable` 공개 계약을 유지한다.
- 기존 사용자 변경과 다른 worktree를 수정, stage, commit하지 않는다.
- API smoke/E2E는 실행 surface가 없어 `NOT CONFIGURED`로 보고한다.

---

### Task 1: 최신 기준선과 사전 검증 확정

**Files:**
- Inspect: `backend/**`
- Inspect: `frontend/**`
- Inspect: `docs/specs/store-search/openapi.yaml`

**Interfaces:**
- Consumes: `origin/mvp2` commit `629854d7be34cb8bb146c5815c2d9a94158bbfd8`, 최신 `origin/dev`
- Produces: 병합 전 backend/frontend 기준선 검증 결과

- [x] **Step 1: 작업 트리와 참조 확인**

Run: `git status --short --branch && git rev-list --left-right --count origin/dev...HEAD`

Expected: 깨끗한 `mvp2`, `dev` 대비 42 ahead / 14 behind.

- [x] **Step 2: backend 기준선 검증**

Run in `backend/`: `./gradlew.bat --version`, `./gradlew.bat test`, `./gradlew.bat integrationTestShardA`, `./gradlew.bat integrationTestShardB`, `./gradlew.bat assemble`

Expected: 모든 명령 exit code 0.

- [x] **Step 3: frontend 기준선 검증**

Run in `frontend/`: `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm typecheck`, `pnpm test -- --run`, `pnpm build`

Expected: 모든 명령 exit code 0.

### Task 2: 최신 dev 병합과 두 충돌 해결

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`
- Modify: `docs/specs/store-search/openapi.yaml`

**Interfaces:**
- Consumes: 최신 `origin/dev`와 현재 `mvp2`
- Produces: conflict marker가 없고 두 브랜치 의도를 모두 보존한 merge result

- [x] **Step 1: dev 병합을 시작**

Run: `git merge --no-ff origin/dev`

Expected: 위 두 파일에 content conflict, 나머지는 자동 병합.

- [x] **Step 2: StoreServiceTest 충돌 해결**

`setUp()`에서 아래 두 초기화 흐름을 함께 보존한다.

```java
geocodingValidator = new StoreGeocodingValidator();
lenient().when(geocodingPort.geocode(any())).thenAnswer(invocation ->
        geocodingResult(invocation.getArgument(0), "서울"));
lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
    Supplier<?> work = invocation.getArgument(0);
    return work.get();
});
menuTransactionFacade = new MenuTransactionFacade(
        new StoreTransactionEligibilityService(storeRepository),
        menuRepository);
```

`StoreService` 생성자는 병합 후 production signature와 정확히 일치시킨다. 모든 테스트 호출은 `MenuTransactionService`가 아니라 `MenuTransactionFacade`를 사용한다.

- [x] **Step 3: OpenAPI 충돌 해결**

복수형 운영자 경로를 선택하고 직전 매장 수정 응답의 503 계약을 유지한다.

```yaml
        "503":
          $ref: "../mvp1-common/openapi.yaml#/components/responses/ServiceUnavailable"
  /api/v1/store-operators/stores/{storeId}/operating-hours:
```

- [x] **Step 4: 충돌 종료 확인**

Run: `git diff --name-only --diff-filter=U` and `rg -n "^(<<<<<<<|=======|>>>>>>>)" backend docs frontend`

Expected: 두 명령 모두 충돌 없음.

### Task 3: 통합 Head 검증과 범위 감사

**Files:**
- Verify: `backend/**`
- Verify: `frontend/**`
- Verify: `.github/workflows/**`
- Verify: `docs/**`

**Interfaces:**
- Consumes: 충돌 해결된 merge result
- Produces: PR #182에 기록할 로컬 검증 증거와 잔여 위험

- [x] **Step 1: 관련 backend 테스트를 먼저 실행**

Run in `backend/`: `./gradlew.bat test --tests "com.miriyum.domain.store.service.StoreServiceTest"`

Expected: exit code 0.

- [x] **Step 2: 전체 backend gate 실행**

Run in `backend/`: `./gradlew.bat test`, `./gradlew.bat integrationTestShardA`, `./gradlew.bat integrationTestShardB`, `./gradlew.bat assemble`

Expected: 모든 명령 exit code 0.

- [x] **Step 3: 전체 frontend gate 실행**

Run in `frontend/`: `pnpm typecheck`, `pnpm test -- --run`, `pnpm build`

Expected: 모든 명령 exit code 0.

- [x] **Step 4: diff와 계약 범위 검사**

Run: `git diff --check`, `git diff --name-only --diff-filter=U`, `git diff --stat origin/dev...HEAD`

Expected: whitespace error와 unresolved conflict 없음. 변경은 `mvp2` 누적 범위와 병합 해소 범위에 한정.

### Task 4: 통합 결과 게시와 PR #182 전환

**Files:**
- Commit: merge result and `docs/superpowers/plans/2026-08-11-mvp2-dev-integration.md`
- Update: GitHub PR #182 body/status/reviewers

**Interfaces:**
- Consumes: 전체 로컬 gate PASS와 깨끗한 diff audit
- Produces: 원격 `mvp2` merge commit, 최신 증거를 담은 Ready PR #182

- [ ] **Step 1: 허용 파일만 stage하고 merge commit 생성**

Run: `git add -- <conflict files> <plan file>` then `git commit`

Expected commit title: `chore(release): 최신 dev를 mvp2에 통합`

- [ ] **Step 2: mvp2 push**

Run: `git push origin mvp2`

Expected: fast-forward push succeeds; force push 금지.

- [ ] **Step 3: PR #182 본문 갱신**

현재 ahead/behind, 실제 명령 결과, migration/API/보안 검토, `NOT CONFIGURED` smoke/E2E, rollback 및 리뷰 포인트를 반영한다.

- [ ] **Step 4: CI 확인**

GitHub Actions의 `backend-ci`와 적용 가능한 frontend check가 현재 PR commit에서 성공했는지 확인한다. 실패 시 Draft를 유지하고 원인 조사로 돌아간다.

- [ ] **Step 5: Draft 해제와 리뷰 요청**

CI가 PASS인 경우에만 PR #182를 Ready로 전환하고 작성자를 제외한 2명 이상의 reviewer를 요청한다. reviewer 후보를 저장소에서 확정할 수 없으면 Ready 전환까지만 수행하고 사용자에게 reviewer 지정을 요청한다.
