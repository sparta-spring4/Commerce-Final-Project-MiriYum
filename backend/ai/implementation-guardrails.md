# Backend 구현 가드레일

계약 상태: ACTIVE

## 빌드 및 애플리케이션 경계

- Java 21, Spring Boot 4.1.0 및 커밋된 Gradle 9.6.1 Wrapper를 사용한다.
- `com.miriyum` 아래에 Spring Boot 애플리케이션 하나를 유지한다.
- [ADR-001](../../docs/adr/ADR-001-domain-packages-three-layer.md)의 도메인 및 3계층 경계를 적용한다.
- 실제 구현에 필요할 때만 도메인 패키지와 그 하위 controller, service, repository, entity, DTO 또는 exception을 만든다.
- 자리표시자 controller, 예제 endpoint, 빈 패키지 또는 추측성 추상화를 추가하지 않는다.

## 영속성 및 마이그레이션

- MySQL은 영속성의 정본이며, 마이그레이션이 존재하는 시점부터 Flyway가 스키마 변경을 소유한다.
- 데이터베이스 연결 값은 환경 입력에서 읽는다. 자격 증명이나 로컬 기본 비밀번호를 절대 커밋하지 않는다.
- H2를 MySQL의 증거로 사용하지 않는다.
- DB 의존 통합은 `NOT CONFIGURED` 상태를 유지한다. [ADR-002](../../docs/adr/ADR-002-staged-technology-adoption.md) 및 [ADR-004](../../docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md)의 활성화 조건에 한해서만 Testcontainers를 검토한다.

## 서버 보안 및 경계

- 범위가 정해진 기능이 이를 정의한 후에는 Spring Security가 서버 인증 및 인가 경계를 소유한다.
- 사용자, 역할, 인증 흐름, 공개 경로 또는 secret 처리를 임의로 만들지 않는다.
- controller는 HTTP 경계에, service는 유스케이스 및 트랜잭션 경계에, repository는 영속성 경계에 둔다.

## 연기된 기능

Docker, Compose, CI, 배포, API 스모크, runner, 스키마, skill, cache, hook은 이 스캐폴드에서 구성되지 않았다. 정본 활성화 경로와 별도로 승인된 허용 목록을 통해서만 추가한다.
