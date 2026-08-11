# Frontend Foundation Issue #191 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이후 화면 Issue가 API 호출 방식, 응답 봉투 해석, 오류 코드 판정, 멱등 키, 비동기 상태, 라우팅, 테스트 하네스를 각자 다시 만들지 않게 하는 공통 실행 기반을 만든다.

**Architecture:** `src/app`이 라우트 표·레이아웃·오류 경계·목적지 보존을 소유하고, `src/shared/api`가 OpenAPI 생성 타입 위의 얇은 `fetch` 클라이언트와 오류 매핑·멱등 키를 소유한다. 인증은 구현하지 않고 토큰 공급과 401 후처리를 주입 이음새로만 남긴다. `src/features`는 화면 Issue가 소유하며 이 Issue는 그 아래에 파일을 만들지 않는다.

**Tech Stack:** React 19.2.8, TypeScript 7.0.2, Vite 8.1.5, React Router, TanStack Query, openapi-typescript, MSW, Vitest 4.1.10, React Testing Library

## Global Constraints

- Issue #191만 구현한다. 인증 판단(#192), 기능 화면(#37, #38, #193, #194, #195, #196)은 포함하지 않는다.
- 대상 브랜치는 `dev`다. PR #200(`frontend-ci`) 병합 후 최신 `dev`에서 분기해 첫 PR부터 CI를 받는다.
- `frontend/vite.config.ts`의 `/api` 프록시는 #197(PR #198)이 제공한다. 이 Issue는 프록시 설정을 바꾸지 않는다.
- `frontend/package.json`의 `engines`는 #199(PR #200)가 소유한다. 이 Issue는 `dependencies`·`devDependencies`·lockfile만 변경한다.
- OpenAPI에 없는 field·path·status를 만들지 않는다. 응답 타입을 수동으로 선언하거나 재정의하지 않는다.
- 미구현 backend 계약은 MSW로만 다룬다. production dummy API, 가짜 성공 응답, 빈 구현, `return null`을 만들지 않는다.
- 1차 MVP 범위 밖 SDK·환경변수·route·네비게이션 항목·자리표시자를 만들지 않는다.
- `src/features/**` 아래에 파일을 만들지 않는다.
- 디자인 시스템·테마·CSS 프레임워크를 도입하지 않는다.
- 토큰을 보관하거나 재발급을 판단하지 않는다. 이음새만 제공한다.
- Windows에서 작업할 경우 워크트리를 짧은 경로에 만든다. 긴 경로에서는 pnpm 의존성 트리가 `MAX_PATH`를 넘겨 Vitest가 `ERR_PACKAGE_IMPORT_NOT_DEFINED`로 기동 실패한다.
- 현재 작업 폴더의 미추적 파일과 다른 Issue 파일은 stage·commit하지 않는다.

## Execution Preflight

1. PR #200 병합을 확인한다.
2. 최신 `origin/dev`에서 `feature/191-frontend-foundation` 브랜치와 짧은 경로 worktree를 만든다.
3. `pnpm install --frozen-lockfile`로 기준 상태를 확인한다.
4. `deploy/local/docker-compose.dev.yml`로 backend를 띄우고 `curl http://127.0.0.1:8080/api/v1/store-categories`가 200을 반환하는지 확인한다.

## Task 1: 의존성 도입

- [ ] `react-router`, `@tanstack/react-query`를 `dependencies`에 추가한다.
- [ ] `openapi-typescript`, `msw`를 `devDependencies`에 추가한다.
- [ ] `pnpm install` 후 lockfile 변경을 확인한다.
- [ ] `pnpm run typecheck`, `pnpm test`, `pnpm run build`가 여전히 통과하는지 확인한다.

## Task 2: OpenAPI 타입 생성

- [ ] `docs/specs/auth-account/openapi.yaml`, `docs/specs/store-search/openapi.yaml`, `docs/specs/reservation/openapi.yaml`, `docs/specs/menu-hold-pickup/openapi.yaml`에서 타입을 생성하는 `generate:api` script를 추가한다.
- [ ] 생성 결과를 `src/shared/api/generated/` 아래에 두고 저장소에 커밋한다.
- [ ] 생성 파일을 직접 수정하지 않는다는 주석 또는 헤더를 남긴다.
- [ ] `pnpm run typecheck`가 생성 타입을 포함해 통과하는지 확인한다.

## Task 3: 응답 봉투와 오류 타입

- [ ] 성공 봉투(`code`·`message`·`data`)와 오류 본문(`code`·`message`·`details`)을 서로 다른 타입으로 선언한다.
- [ ] `ApiError`를 정의해 `status`·`code`·`message`·`details`를 보존한다.
- [ ] 네트워크·파싱 실패를 서버 코드가 있는 오류와 구분하는 판별자를 둔다.
- [ ] `CommonErrorCode` 값(`COMMON_001`~`COMMON_012`)을 상수로 두고 화면이 문자열 리터럴을 흩뿌리지 않게 한다.

**RED:** 오류 본문을 성공 봉투로 파싱하려 하면 실패하는 테스트, `data: null`과 빈 배열을 오류로 만들지 않는 테스트를 먼저 작성한다.

## Task 4: HTTP 클라이언트

- [ ] `requestApi`를 구현한다. 성공 시 `data`를 반환하고 실패 시 `ApiError`를 던진다.
- [ ] 토큰 공급 함수와 401 후처리 함수를 주입받는다. 기본값은 각각 토큰 없음과 그대로 던지기다.
- [ ] 요청 본문 타입을 생성 타입으로 제한한다.
- [ ] `Idempotency-Key` 헤더를 붙일 수 있게 하되 키 생성은 호출자가 소유한다.

**RED:** 200/400/401/403/404/409/429/503 각각에 대해 기대하는 결과를 MSW로 고정하는 테스트를 먼저 작성한다.

## Task 5: 멱등 키

- [ ] `crypto.randomUUID()` 기반 키 생성 수단을 만든다.
- [ ] 하나의 작업 시도 동안 키를 보존하고, 입력이 바뀌면 새 키를 쓰는 사용 방식을 테스트로 고정한다.

**RED:** 같은 작업의 재시도가 같은 키를 보내는지 확인하는 테스트를 먼저 작성한다.

## Task 6: TanStack Query 연결

- [ ] QueryClient를 앱 셸에 연결한다.
- [ ] 재시도 기본값을 정한다. 4xx는 재시도하지 않고 네트워크 실패만 재시도한다.
- [ ] 멱등 키가 필요한 변경 요청은 자동 재시도하지 않는다. 재시도 판단은 화면이 소유한다.

## Task 7: 공통 비동기 상태 표현

- [ ] `초기·로딩·빈 상태·성공·검증 오류·권한 없음·충돌·결과 불명·재시도 가능·복구 대기`를 구분하는 표현 수단을 만든다.
- [ ] 상태 이름을 시안 문구가 아니라 `docs/08-ui-and-frontend-guidelines.md`의 목록에서 가져온다.
- [ ] 색상만으로 상태를 구분하지 않는다. 의미에 맞는 HTML과 레이블을 사용한다.

## Task 8: 앱 셸과 라우팅

- [ ] 라우트 표를 `src/app`에 두고 화면 Issue가 자기 route만 등록하는 형태로 만든다.
- [ ] 공통 레이아웃(헤더·네비게이션·본문)을 만들고 계정 shell별로 네비게이션 항목을 분리한다.
- [ ] 없는 경로와 권한 없는 접근에 서로 구분된 화면을 제공한다.
- [ ] 오류 경계를 두어 렌더링 예외가 빈 화면으로 끝나지 않게 한다.
- [ ] 원래 목적지를 보존하고 조회하는 수단을 제공한다. 복귀 판단은 #192가 소유한다.
- [ ] 1차 MVP에 없는 기능의 네비게이션 항목을 만들지 않는다.

**RED:** 없는 경로가 404로, 권한 없는 접근이 그와 다른 화면으로 가는지, 오류 경계가 렌더링 예외를 잡는지 확인하는 테스트를 먼저 작성한다.

## Task 9: MSW 하네스

- [ ] 테스트 setup에 MSW 서버를 연결한다.
- [ ] 생성 타입을 사용해 응답을 만드는 핸들러 작성 규약을 정하고 예시를 남긴다.
- [ ] 성공·빈 결과·`data: null`·400·401·403·404·409·429·503 시나리오를 재현한다.
- [ ] backend 미구현 경로를 MSW로만 다룬다는 주석을 남긴다.

## Task 10: CI에 생성 타입 drift 검사 추가

- [ ] `.github/workflows/frontend-ci.yml`에 `generate:api` 재실행 후 diff가 없는지 확인하는 step을 추가한다.
- [ ] OpenAPI를 고치고 타입을 다시 만들지 않은 상태가 실패하는지 확인한다.

## Task 11: 통합 확인

- [ ] backend를 띄운 상태에서 `pnpm run dev`로 `/api/v1/store-categories`가 same-origin으로 도달하는지 확인한다.
- [ ] `pnpm run typecheck`, `pnpm test`, `pnpm run build`를 실행한다.
- [ ] 빌드 산출물에 1차 MVP 범위 밖 SDK·환경변수·route가 포함되지 않았는지 확인한다.
- [ ] 실행한 명령과 결과를 PR에 기록하고, 실행하지 않은 검증은 `NOT RUN`·`NOT CONFIGURED` 사유와 함께 남긴다.
