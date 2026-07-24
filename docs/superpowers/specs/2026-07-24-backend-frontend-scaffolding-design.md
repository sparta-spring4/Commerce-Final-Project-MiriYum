# 백엔드(Backend)와 프런트엔드(Frontend) 스캐폴딩 설계

- 상태: Approved
- 승인일: 2026-07-24
- 구현 계획: `docs/superpowers/plans/2026-07-23-miriyum-backend-frontend-scaffolding.md`
- 정본 결정: `docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md`

## 목적

1단계(Phase 1) 문서 구조 위에 실행 가능한 최소 Spring Boot와 React/Vite 스캐폴드를 함께 만든다. 각 엔드는 자신의 구현·명령·검증 계약만 소유하고, 루트 AI 계층은 백엔드(backend)↔프런트엔드(frontend) 라우팅과 결합 증거만 소유한다.

이 단계는 제품 기능을 만들지 않는다. 빈 도메인 패키지, 예제 컨트롤러(controller)·API 클라이언트(client)·화면, Docker·Compose, CI 워크플로(workflow), Terraform, 실행기(runner), 스키마(schema), 스킬(skill), 캐시(cache)와 Git 훅(hook)을 추가하지 않는다.

## 검토한 접근

### 선택: 최소 파일 직접 작성

정확히 고정한 버전과 명령에 필요한 파일만 작성한다. 생성기 기본 예제나 선택하지 않은 린트(lint) 도구가 범위에 들어오는 것을 막고, 작업(Task)별 허용 목록(allowlist)과 실행 증거를 명확하게 유지할 수 있다.

### 대안: 공식 생성기 출력 후 정리

Spring Initializr와 create-vite는 초기 구성을 빠르게 만들지만, 생성 시점의 기본 버전·예제·설정이 승인된 범위를 벗어날 수 있다. 생성 결과를 삭제·재작성하는 과정도 검토 범위를 늘리므로 선택하지 않는다.

### 대안: 한 세대 낮은 버전 사용

보수적인 생태계 조합을 사용할 수 있지만, 공식 현재/LTS(current/LTS) 메타데이터를 기준으로 고정한다는 승인과 맞지 않아 선택하지 않는다.

## 고정 도구 기준

공식 메타데이터를 2026-07-24에 확인한 값이다. 버전 범위나 `latest` 태그(tag)를 저장소 설정에 사용하지 않는다.

### 백엔드(Backend)

| 항목 | 고정 값 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Gradle 래퍼(Wrapper) | 9.6.1 `bin` 배포판(distribution) |
| Gradle 배포판(distribution) SHA-256 | `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` |
| Gradle 래퍼(Wrapper) JAR SHA-256 | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| 패키지 네임스페이스(package namespace) | `com.miriyum` |
| 애플리케이션 클래스(application class) | `MiriyumApplication` |
| 작업 디렉터리(working directory) | `backend/` |

Spring MVC, Spring Data JPA, Spring Security, MySQL Connector/J, Flyway Core와 Flyway MySQL 모듈은 Spring Boot 의존성 관리(dependency management)가 관리하는 버전을 사용한다. 각 라이브러리 버전을 별도 중복 고정하지 않는다.

### 프런트엔드(Frontend)

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
| 작업 디렉터리(working directory) | `frontend/` |

`package.json`은 정확한 의존성(dependency) 버전과 `packageManager: pnpm@11.17.0`을 기록하고 `pnpm-lock.yaml`을 커밋한다. TypeScript 엄격 모드(strict mode)를 사용한다. ESLint와 `typescript-eslint`은 이 최소 스캐폴드의 필수 실행 명령이 아니므로 추가하지 않는다.

## 백엔드(Backend) 구조

단일 Gradle Kotlin DSL 모듈을 만든다. `com.miriyum.MiriyumApplication`과 최소 애플리케이션 구성(application configuration), 애플리케이션 컨텍스트(application context) 기동 테스트만 둔다. 실제 기능(feature)이 없는 상태에서 `auth`, `store`, `booking`, `payment`, `review`, `notification`, `global` 빈 패키지를 미리 만들지 않는다.

데이터베이스 연결값은 환경 입력으로만 받으며 자격증명이나 로컬 기본 비밀번호를 저장소에 넣지 않는다. 스캐폴드 빌드(build)는 데이터베이스가 없어도 재현되어야 한다.

## 프런트엔드(Frontend) 구조

최소 HTML 진입점(entry), React 진입점(entry), `App` 컴포넌트(component)와 하나의 사용자 관찰 가능 동작 테스트(behavior test)만 둔다. 제품 화면, 라우팅(routing), 상태 관리, API 클라이언트(client), 기능(feature) 폴더, UI 라이브러리(library)와 분석(analytics)은 추가하지 않는다.

필수 스크립트(script)는 `dev`, `typecheck`, `test`, `build`다. 각 스크립트(script)는 실제 파일이 생긴 뒤에만 엔드포인트 명령 레지스트리(endpoint command registry)에 등록한다.

## 데이터베이스 테스트 정책

H2를 MySQL 대체물로 추가하지 않는다. 최초 컨텍스트 테스트(context test)는 영속성(persistence)과 Flyway 실행을 분리한 테스트 구성(test configuration)으로 애플리케이션 기본 연결(wiring)만 검증한다.

MySQL 고유 동작, 첫 Flyway 마이그레이션(migration), 트랜잭션/격리(transaction/isolation) 또는 실제 DB 제약을 검증해야 할 때 별도 승인된 변경으로 Testcontainers를 활성화한다. 그 전까지 DB 종속 통합 테스트 명령(DB-dependent integration test command)은 `NOT CONFIGURED`이며 컨텍스트 테스트(context test)나 컴파일(compile) 성공을 DB 통합 성공으로 표현하지 않는다.

## AI 계약 경계

- `backend/AGENTS.md`는 `backend/ai/document-routing.md`를 첫 진입점으로 삼는다.
- `frontend/AGENTS.md`는 `frontend/ai/document-routing.md`를 첫 진입점으로 삼는다.
- 엔드별 AI 문서는 해당 엔드의 구현 가드레일(guardrail), 실제 명령(command)과 검증 증거(evidence)만 소유한다.
- 루트 `ai/command-registry.md`는 양쪽에서 `CONFIGURED`로 검증된 명령 ID(command ID)의 실행 순서와 결합 증거만 소유한다.
- 원시 엔드포인트(raw endpoint) 명령을 루트에 복제하지 않는다.

## 오류 처리와 실패 시 차단(fail-closed) 규칙

의존성 다운로드(dependency download), 래퍼 체크섬(Wrapper checksum), 잠금 파일 설치(lockfile install) 또는 실행 명령이 실패하면 해당 결과를 `FAIL`로 기록하고 다음 작업(Task)으로 진행하지 않는다. 실행 파일이나 스크립트(script)가 없으면 `NOT CONFIGURED`다. 명령·종료 코드(exit code)·출력 증거가 모두 확인되기 전에는 `PASS`나 `CONFIGURED`를 사용하지 않는다.

예상 밖 생성 파일, 허용 목록(allowlist) 밖 변경, 기존 사용자 변경의 SHA-256 드리프트(drift) 또는 활성(active) 2단계(Phase 2) 금지 아티팩트(artifact)가 발견되면 `BLOCKED`로 중단한다.

## 검증 계약

백엔드(Backend)에서 다음 명령을 `backend/` 기준으로 실행한다.

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat build
```

프런트엔드(Frontend)에서 다음 명령을 `frontend/` 기준으로 실행한다.

```powershell
pnpm --version
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm run test
pnpm run build
```

마지막 클린 체크아웃(clean-checkout) 게이트(gate)는 래퍼(Wrapper)와 잠금 파일(lockfile)만으로 의존성을 복원하고 같은 명령을 다시 실행한다. CI, E2E, API 스모크(smoke)와 DB 통합(integration)은 실행 표면(surface)이 생기기 전까지 `NOT CONFIGURED`다.

## 공식 버전 출처

- Spring Boot Initializr 메타데이터(metadata): <https://start.spring.io/metadata/client>
- Spring Boot 시스템 요구사항(system requirements): <https://docs.spring.io/spring-boot/system-requirements.html>
- Gradle 현재 릴리스 메타데이터(current release metadata): <https://services.gradle.org/versions/current>
- Gradle 래퍼 체크섬 안내(checksum guidance): <https://docs.gradle.org/current/userguide/gradle_wrapper.html>
- Node.js 릴리스 색인(release index): <https://nodejs.org/dist/index.json>
- npm 레지스트리 패키지 메타데이터(registry package metadata): <https://registry.npmjs.org/>

## 명시적 후속 범위

제품 기능, DB 마이그레이션(migration), Testcontainers, 린트 도구 체인(lint toolchain), API 스모크(smoke), E2E, Docker·Compose, CI, AWS·Terraform과 실행 자동화는 이 설계의 완료 조건이 아니다. 각 활성화 조건(activation condition)이 생긴 뒤 별도 계획과 증거로 추가한다.
