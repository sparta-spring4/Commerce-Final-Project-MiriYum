# ADR-004: 스캐폴드 도구 버전과 초기 테스트 기준

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

백엔드·프런트엔드 스캐폴딩 계획은 재현 가능한 Wrapper와 잠금 파일을 만들기 위해 정확한 패치 버전을 활성화 입력값으로 요구한다. 기존 아키텍처 문서는 기술 계열과 Java 21만 확정했고 Spring Boot, Gradle, Node.js, pnpm과 프런트엔드 패키지 버전은 고정하지 않았다.

또한 Spring Data JPA, MySQL과 Flyway 의존성을 추가하더라도 아직 마이그레이션이나 DB 고유 동작이 없으므로, 초기 컨텍스트 테스트가 무엇을 증명하는지와 DB 통합을 언제 활성화할지 구분해야 한다.

## 결정

2026-07-24 공식 최신/LTS 메타데이터를 기준으로 다음 기준을 채택한다.

- Java 21, Spring Boot 4.1.0, Gradle Wrapper 9.6.1
- Gradle `bin` 배포판 SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`
- Gradle Wrapper JAR SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- Java 패키지 `com.miriyum`, 애플리케이션 클래스 `MiriyumApplication`
- Node.js 24.18.0 LTS와 pnpm 11.17.0
- React·React DOM 19.2.8, TypeScript 7.0.2, Vite 8.1.5, `@vitejs/plugin-react` 6.0.4
- Vitest 4.1.10, React Testing Library 16.3.2, Testing Library DOM 10.4.1, jest-dom 7.0.0, jsdom 29.1.1
- React 타입 패키지 19.2.17/19.2.3과 Node 24 타입 패키지 24.13.3

Spring 의존성 버전은 Spring Boot 의존성 관리가 단일 소유한다. 프런트엔드 의존성은 정확한 버전과 `pnpm-lock.yaml`로 고정한다.

초기 테스트에는 H2와 Testcontainers를 추가하지 않는다. 컨텍스트 테스트는 영속성과 Flyway가 없는 환경에서 기본 애플리케이션 연결 구성만 검증한다. MySQL 고유 동작, 첫 Flyway 마이그레이션, 트랜잭션/격리 또는 실제 DB 제약이 생길 때 ADR-002의 도입 조건에 따라 Testcontainers를 검토한다. 그 전까지 DB 통합은 `NOT CONFIGURED`다.

## 검토한 대안

- 생성기의 시점별 기본 버전 사용: 같은 계획을 다시 실행할 때 결과가 달라지므로 거절한다.
- 모든 의존성에 범위 버전 사용: 잠금 파일과 Wrapper의 재현 가능성을 약화하므로 거절한다.
- H2 기반 컨텍스트 테스트: MySQL과 다른 동작을 DB 호환성 증거로 오해할 수 있어 거절한다.
- 스캐폴드부터 Testcontainers 사용: 검증할 마이그레이션이나 DB 고유 요구가 아직 없어 ADR-002의 단계적 도입 원칙에 어긋난다.
- ESLint 도구 체인 동시 활성화: 최소 스캐폴드의 필수 검증 관문이 아니며 TypeScript 컴파일러, Vitest와 빌드로 현재 범위를 검증할 수 있어 후속으로 미룬다.

## 결과

- Wrapper, 잠금 파일과 엔드별 명령 레지스트리가 정확한 입력값을 사용할 수 있다.
- 컨텍스트 테스트와 DB 통합 증거의 의미가 분리된다.
- 버전 업그레이드는 이 ADR과 실행 증거를 함께 갱신해야 한다.
- 린트(lint)와 실제 DB 통합은 현재 `NOT CONFIGURED`로 남는다.

## 재검토 조건

지원 종료, 보안 수정, Spring Boot·Gradle·Node·Vite 호환성 변화, 첫 DB 마이그레이션 또는 린트(lint)가 필수 검증 관문이 되는 변경이 발생하면 정확한 기준과 검증 명령을 다시 검토한다.

## 관련 문서

- [백엔드 및 프런트엔드 스캐폴딩 설계](../superpowers/specs/2026-07-24-backend-frontend-scaffolding-design.md)
- [시스템 아키텍처](../06-system-architecture.md)
- [UI 및 프론트엔드 가이드라인](../08-ui-and-frontend-guidelines.md)
- [품질 운영 및 규칙](../09-quality-operations-and-rules.md)
- [ADR-002](ADR-002-staged-technology-adoption.md)

## 2026-07-27 날짜별 개정

### 재검토 조건 충족과 현재 결정

- 최초 스캐폴딩 결정과 Testcontainers 초기 미도입 본문은 DB 상호작용이 없던 시점의 기록으로 보존한다.
- 1차 MVP가 Flyway 마이그레이션, MySQL 제약, 예약 수용량·회차별 팀 수·메뉴 홀드 경합, 잠금과 조건부 SQL을 실제 구현하므로 본문의 “첫 DB 마이그레이션” 재검토 조건이 충족되었다.
- 따라서 **Testcontainers MySQL을 1차 MVP 필수 테스트 기준선으로 활성화**한다. H2 또는 인메모리 대체 DB의 성공으로 MySQL 통합 테스트를 대신하지 않는다.
- 기존에 확정한 Java 21, Spring Boot 4.1.0, Gradle 9.6.1과 프론트엔드 Node 24.18.0, pnpm 11.17.0, React 19.2.8, TypeScript 7.0.2, Vite 8.1.5, Vitest 4.1.10, React Testing Library 16.3.2 기준을 유지한다.

### 검증과 결과

- 백엔드 게이트는 컴파일·단위 테스트에 더해 실제 MySQL 컨테이너에서 Flyway clean-start, 제약 위반, 트랜잭션 롤백, 동시 조건부 갱신, 중복 멱등 키를 검증한다.
- 개발 머신에 설치된 임의 MySQL 인스턴스나 공유 DB 상태에 의존하지 않고 테스트마다 재현 가능한 스키마와 데이터를 만든다.
- 컨테이너 시작 시간과 Docker 실행 환경이라는 비용이 추가된다. 빠른 단위 테스트와 MySQL 통합 테스트를 분리하되, DB 의미에 의존하는 변경은 통합 게이트를 생략할 수 없다.

## 2026-08-05 날짜별 개정

### CI 테스트 분리와 병렬 실행

- Testcontainers MySQL 실행 시간이 CI 대부분을 차지한다는 측정 결과에 따라 JUnit 5의 `@Tag("integration")`으로 빠른 테스트와 통합 테스트를 분리한다.
- `@SpringBootTest`, `@Testcontainers` 또는 `MySQLContainer`를 사용하는 테스트 클래스는 `@Tag("integration")`을 선언한다. Gradle 검증 task가 이 marker를 사용하는 클래스의 태그 누락을 실패시킨다.
- GitHub Actions는 `unit-test`, `integration-test-a`, `integration-test-b`를 병렬 실행한다. `dev` 브랜치 보호와 호환되는 `backend-ci` 집계 job은 세 job과 CD workflow 계약 검증이 모두 성공할 때만 성공한다.
- `build`는 두 테스트 task를 모두 포함하므로 로컬 전체 검증과 CI의 병합 gate 의미를 유지한다. 병렬화는 테스트를 생략하는 변경이 아니라 wall-clock 시간을 줄이는 변경이다.

### 통합 테스트 shard 분할

- `integration-test`는 `integration-test-a`, `integration-test-b` 두 matrix job으로 다시 분할해 병렬 실행한다. 모든 통합 테스트 클래스는 `integration-shard-a` 또는 `integration-shard-b` 중 정확히 하나를 추가로 선언한다.
- Gradle 검증 task는 통합 marker와 shard tag의 누락 또는 중복을 실패시킨다. 두 shard가 모두 성공해야 `backend-ci` 집계 job이 성공하므로 기존 Required check 이름과 전체 테스트 게이트는 유지한다.

## 2026-08-13 날짜별 개정

### 통합 테스트 4-shard 확장

- #288 2단계에서 `integration-test`를 `integration-test-a`부터 `integration-test-d`까지 네 matrix job으로 확장해 병렬 실행한다. 모든 통합 테스트 클래스는 `integration-shard-a`~`integration-shard-d` 중 정확히 하나를 선언한다.
- Gradle 검증 task는 통합 marker와 shard tag의 누락 또는 중복을 실패시킨다. 네 shard가 모두 성공해야 `backend-ci` 집계 job이 성공하므로 기존 Required check 이름과 전체 테스트 게이트는 유지한다. Required check는 집계 job 이름 하나이므로 shard 수를 바꿔도 브랜치 보호 설정을 변경하지 않는다.
- shard 배치는 도메인 단위가 아니라 최근 실행 시간과 테스트 구성 정보를 함께 보아 균형 있게 정한다. `@SpringBootTest` properties·`@AutoConfigureMockMvc`·`@Testcontainers` 조합은 후보를 찾는 힌트일 뿐, Spring ApplicationContext 캐시 키에는 `@DynamicPropertySource`, `@MockitoBean` 등의 context customizer도 포함된다. 따라서 정적 시그니처가 같다는 이유만으로 캐시 공유나 기동 횟수를 단정하지 않으며, 컨텍스트 재사용을 최적화 근거로 삼을 때는 cache debug log 또는 동등한 실행 증거를 남긴다.
