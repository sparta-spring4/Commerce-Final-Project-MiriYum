# Backend and Frontend Scaffolding Design

- 상태: Approved
- 승인일: 2026-07-24
- 구현 계획: `docs/superpowers/plans/2026-07-23-miriyum-backend-frontend-scaffolding.md`
- 정본 결정: `docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md`

## 목적

Phase 1 문서 구조 위에 실행 가능한 최소 Spring Boot와 React/Vite 스캐폴드를 함께 만든다. 각 엔드는 자신의 구현·명령·검증 계약만 소유하고, 루트 AI 계층은 backend↔frontend 라우팅과 결합 증거만 소유한다.

이 단계는 제품 기능을 만들지 않는다. 빈 도메인 패키지, 예제 controller·API client·화면, Docker·Compose, CI workflow, Terraform, runner, schema, skill, cache와 Git hook을 추가하지 않는다.

## 검토한 접근

### 선택: 최소 파일 직접 작성

정확히 고정한 버전과 명령에 필요한 파일만 작성한다. 생성기 기본 예제나 선택하지 않은 lint 도구가 범위에 들어오는 것을 막고, Task별 allowlist와 실행 증거를 명확하게 유지할 수 있다.

### 대안: 공식 생성기 출력 후 정리

Spring Initializr와 create-vite는 초기 구성을 빠르게 만들지만, 생성 시점의 기본 버전·예제·설정이 승인된 범위를 벗어날 수 있다. 생성 결과를 삭제·재작성하는 과정도 검토 범위를 늘리므로 선택하지 않는다.

### 대안: 한 세대 낮은 버전 사용

보수적인 생태계 조합을 사용할 수 있지만, 공식 current/LTS 메타데이터를 기준으로 고정한다는 승인과 맞지 않아 선택하지 않는다.

## 고정 도구 기준

공식 메타데이터를 2026-07-24에 확인한 값이다. 버전 범위나 `latest` tag를 저장소 설정에 사용하지 않는다.

### Backend

| 항목 | 고정 값 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Gradle Wrapper | 9.6.1 `bin` distribution |
| Gradle distribution SHA-256 | `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` |
| Gradle Wrapper JAR SHA-256 | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| package namespace | `com.miriyum` |
| application class | `MiriyumApplication` |
| working directory | `backend/` |

Spring MVC, Spring Data JPA, Spring Security, MySQL Connector/J, Flyway Core와 Flyway MySQL 모듈은 Spring Boot dependency management가 관리하는 버전을 사용한다. 각 라이브러리 버전을 별도 중복 고정하지 않는다.

### Frontend

| 항목 | 고정 값 |
|---|---|
| Node.js | 24.18.0 LTS |
| pnpm | 11.17.0 |
| React / React DOM | 19.2.8 |
| TypeScript | 7.0.2 |
| Vite | 8.1.5 |
| `@vitejs/plugin-react` | 6.0.4 |
| Vitest | 4.1.10 |
| React Testing Library | 16.3.2 |
| Testing Library DOM | 10.4.1 |
| jest-dom | 7.0.0 |
| jsdom | 29.1.1 |
| `@types/react` | 19.2.17 |
| `@types/react-dom` | 19.2.3 |
| `@types/node` | 24.13.3 |
| working directory | `frontend/` |

`package.json`은 정확한 dependency 버전과 `packageManager: pnpm@11.17.0`을 기록하고 `pnpm-lock.yaml`을 커밋한다. TypeScript strict mode를 사용한다. ESLint와 `typescript-eslint`은 이 최소 스캐폴드의 필수 실행 명령이 아니므로 추가하지 않는다.

## Backend 구조

단일 Gradle Kotlin DSL 모듈을 만든다. `com.miriyum.MiriyumApplication`과 최소 application configuration, application context 기동 테스트만 둔다. 실제 feature가 없는 상태에서 `auth`, `store`, `booking`, `payment`, `review`, `notification`, `global` 빈 패키지를 미리 만들지 않는다.

데이터베이스 연결값은 환경 입력으로만 받으며 자격증명이나 로컬 기본 비밀번호를 저장소에 넣지 않는다. 스캐폴드 build는 데이터베이스가 없어도 재현되어야 한다.

## Frontend 구조

최소 HTML entry, React entry, `App` component와 하나의 사용자 관찰 가능 behavior test만 둔다. 제품 화면, routing, 상태 관리, API client, feature 폴더, UI library와 analytics는 추가하지 않는다.

필수 script는 `dev`, `typecheck`, `test`, `build`다. 각 script는 실제 파일이 생긴 뒤에만 endpoint command registry에 등록한다.

## 데이터베이스 테스트 정책

H2를 MySQL 대체물로 추가하지 않는다. 최초 context test는 persistence와 Flyway 실행을 분리한 test configuration으로 애플리케이션 기본 wiring만 검증한다.

MySQL 고유 동작, 첫 Flyway migration, transaction/isolation 또는 실제 DB 제약을 검증해야 할 때 별도 승인된 변경으로 Testcontainers를 활성화한다. 그 전까지 DB-dependent integration test command는 `NOT CONFIGURED`이며 context test나 compile 성공을 DB 통합 성공으로 표현하지 않는다.

## AI 계약 경계

- `backend/AGENTS.md`는 `backend/ai/document-routing.md`를 첫 진입점으로 삼는다.
- `frontend/AGENTS.md`는 `frontend/ai/document-routing.md`를 첫 진입점으로 삼는다.
- 엔드별 AI 문서는 해당 엔드의 구현 guardrail, 실제 command와 검증 evidence만 소유한다.
- 루트 `ai/command-registry.md`는 양쪽에서 `CONFIGURED`로 검증된 command ID의 실행 순서와 결합 증거만 소유한다.
- raw endpoint 명령을 루트에 복제하지 않는다.

## 오류 처리와 fail-closed 규칙

dependency download, Wrapper checksum, lockfile install 또는 실행 명령이 실패하면 해당 결과를 `FAIL`로 기록하고 다음 Task로 진행하지 않는다. 실행 파일이나 script가 없으면 `NOT CONFIGURED`다. 명령·exit code·출력 증거가 모두 확인되기 전에는 `PASS`나 `CONFIGURED`를 사용하지 않는다.

예상 밖 생성 파일, allowlist 밖 변경, 기존 사용자 변경의 SHA-256 drift 또는 active Phase 2 금지 artifact가 발견되면 `BLOCKED`로 중단한다.

## 검증 계약

Backend에서 다음 명령을 `backend/` 기준으로 실행한다.

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat build
```

Frontend에서 다음 명령을 `frontend/` 기준으로 실행한다.

```powershell
pnpm --version
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm run test
pnpm run build
```

마지막 clean-checkout gate는 Wrapper와 lockfile만으로 의존성을 복원하고 같은 명령을 다시 실행한다. CI, E2E, API smoke와 DB integration은 실행 surface가 생기기 전까지 `NOT CONFIGURED`다.

## 공식 버전 출처

- Spring Boot Initializr metadata: <https://start.spring.io/metadata/client>
- Spring Boot system requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Gradle current release metadata: <https://services.gradle.org/versions/current>
- Gradle Wrapper checksum guidance: <https://docs.gradle.org/current/userguide/gradle_wrapper.html>
- Node.js release index: <https://nodejs.org/dist/index.json>
- npm registry package metadata: <https://registry.npmjs.org/>

## 명시적 후속 범위

제품 기능, DB migration, Testcontainers, lint toolchain, API smoke, E2E, Docker·Compose, CI, AWS·Terraform과 실행 자동화는 이 설계의 완료 조건이 아니다. 각 activation condition이 생긴 뒤 별도 계획과 증거로 추가한다.
