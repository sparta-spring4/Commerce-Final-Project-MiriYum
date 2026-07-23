# ADR-004: 스캐폴드 도구 버전과 초기 테스트 기준

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

backend·frontend 스캐폴딩 계획은 재현 가능한 Wrapper와 lockfile을 만들기 위해 exact patch version을 activation input으로 요구한다. 기존 아키텍처 문서는 기술 계열과 Java 21만 확정했고 Spring Boot, Gradle, Node.js, pnpm과 frontend package 버전은 고정하지 않았다.

또한 Spring Data JPA, MySQL과 Flyway 의존성을 추가하더라도 아직 migration이나 DB 고유 동작이 없으므로, 초기 context test가 무엇을 증명하는지와 DB integration을 언제 활성화할지 구분해야 한다.

## 결정

2026-07-24 공식 current/LTS 메타데이터를 기준으로 다음 baseline을 채택한다.

- Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.6.1
- Gradle `bin` distribution SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`
- Gradle Wrapper JAR SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- Java package `com.miriyum`, application class `MiriyumApplication`
- Node.js 24.18.0 LTS와 pnpm 11.17.0
- React·React DOM 19.2.8, TypeScript 7.0.2, Vite 8.1.5, `@vitejs/plugin-react` 6.0.4
- Vitest 4.1.10, React Testing Library 16.3.2, Testing Library DOM 10.4.1, jest-dom 7.0.0, jsdom 29.1.1
- React type packages 19.2.17/19.2.3과 Node 24 type package 24.13.3

Spring dependency versions은 Spring Boot dependency management가 단일 소유한다. frontend dependency는 정확한 version과 `pnpm-lock.yaml`로 고정한다.

초기 테스트에는 H2와 Testcontainers를 추가하지 않는다. context test는 persistence와 Flyway가 없는 환경에서 기본 application wiring만 검증한다. MySQL 고유 동작, 첫 Flyway migration, transaction/isolation 또는 실제 DB 제약이 생길 때 ADR-002의 trigger에 따라 Testcontainers를 검토한다. 그 전까지 DB integration은 `NOT CONFIGURED`다.

## 검토한 대안

- 생성기의 시점별 기본 버전 사용: 같은 계획을 다시 실행할 때 결과가 달라지므로 거절한다.
- 모든 dependency에 범위 version 사용: lockfile과 Wrapper의 재현 가능성을 약화하므로 거절한다.
- H2 기반 context test: MySQL과 다른 동작을 DB 호환성 증거로 오해할 수 있어 거절한다.
- 스캐폴드부터 Testcontainers 사용: 검증할 migration이나 DB 고유 요구가 아직 없어 ADR-002의 단계적 도입 원칙에 어긋난다.
- ESLint toolchain 동시 활성화: 최소 scaffold의 필수 gate가 아니며 TypeScript compiler, Vitest와 build로 현재 범위를 검증할 수 있어 후속으로 미룬다.

## 결과

- Wrapper, lockfile과 엔드별 command registry가 exact input을 사용할 수 있다.
- context test와 DB integration evidence의 의미가 분리된다.
- version upgrade는 이 ADR과 실행 증거를 함께 갱신해야 한다.
- lint와 실제 DB integration은 현재 `NOT CONFIGURED`로 남는다.

## 재검토 조건

지원 종료, 보안 수정, Spring Boot·Gradle·Node·Vite 호환성 변화, 첫 DB migration 또는 lint가 필수 gate가 되는 변경이 발생하면 exact baseline과 검증 명령을 다시 검토한다.

## 관련 문서

- [Backend and Frontend Scaffolding Design](../superpowers/specs/2026-07-24-backend-frontend-scaffolding-design.md)
- [시스템 아키텍처](../06-system-architecture.md)
- [UI 및 프론트엔드 가이드라인](../08-ui-and-frontend-guidelines.md)
- [품질 운영 및 규칙](../09-quality-operations-and-rules.md)
- [ADR-002](ADR-002-staged-technology-adoption.md)
