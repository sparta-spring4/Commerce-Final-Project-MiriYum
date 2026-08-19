# ADR-002: 요구 기반의 단계적 기술 도입

- 상태: Accepted
- 결정일: 2026-07-24

## 배경

초기 단계에서 조건부 기술을 의존성으로 미리 추가하면 운영·테스트 비용이 늘고, 아직 검증되지 않은 요구가 현재 구성처럼 굳어질 수 있다.

## 결정

초기 기술은 최소 구성으로 유지한다. QueryDSL, Redis, SSE, Outbox, Testcontainers는 각각의 도입 필요와 대체안의 한계를 기록한 뒤에만 추가한다.

Testcontainers는 다음 중 하나가 처음으로 필요한 통합 테스트가 생기고 인메모리 또는 가짜 구현으로 해당 동작을 검증할 수 없을 때 도입한다.

- MySQL 고유 동작
- Flyway 마이그레이션
- 트랜잭션 또는 격리 동작
- 실제 DB 제약

Docker Compose를 수동으로 실행하는 것만으로는 Pull Request 재현성과 테스트 격리를 보장할 수 없는 경우도 같은 도입 조건으로 기록한다.

### 2026-07-27 후속 결정

Redis 일반 후보 가운데 인증 상태 저장 요구는 [ADR-006](ADR-006-jwt-valkey-refresh-token.md)에 따라 Valkey 도입으로 구체화했다. Valkey는 리프레시 토큰 상태와 캐시, 속도 제한, 임시 선점과 실시간 전달을 위한 보조 기반 시설이며 MySQL 업무 원장을 대체하지 않는다.

별도 검색 엔진은 [ADR-007](ADR-007-unified-search-mysql.md)에 따라 OpenSearch와 Meilisearch 모두 `확정 + MVP 제외` 상태를 유지한다. 오타, 자동완성, 전문 검색 품질 또는 검색 부하의 측정 결과가 MySQL 기준을 넘을 때만 다시 검토한다.

이 후속 결정은 나머지 조건부 기술을 실제 요구와 검증 전략에 따라 단계적으로 도입한다는 기존 원칙을 변경하지 않는다.

## 검토한 대안

- 모든 조건부 기술을 초기 의존성으로 추가: 검증되지 않은 복잡성과 유지 비용을 앞당긴다.
- 인메모리 또는 가짜 구현만으로 모든 통합 검증 수행: 실제 MySQL 및 마이그레이션·격리·제약 동작을 재현하지 못할 수 있다.
- Docker Compose 수동 실행만 사용: Pull Request 재현성과 테스트 격리를 자동으로 보장하지 못한다.

## 긍정적 결과

- 기술 도입이 실제 요구와 검증 전략에 연결된다.
- 초기 런타임과 운영 복잡도를 줄일 수 있다.
- 실제 DB 호환성이 필요한 시점에는 격리되고 재현 가능한 통합 검증을 확보한다.

## 부정적 결과

- 조건부 기술이 필요한 시점마다 요구와 대체안을 명시적으로 검토해야 한다.
- Testcontainers 도입 뒤에는 컨테이너 실행 조건과 테스트 시간을 관리해야 한다.

## 재검토 조건

조회 복잡성, 측정된 캐시·세션·조율 요구, 실시간 상태 전달, 외부 부작용의 원자성·재시도 또는 실제 DB 호환성 요구가 확인되면 해당 기술의 도입 ADR을 추가로 검토한다.

## 관련 문서

- [시스템 아키텍처](../06-system-architecture.md)
- [품질·운영·규칙](../09-quality-operations-and-rules.md)
- [ADR 템플릿](ADR-000-template.md)
- [액세스 JWT와 Valkey 리프레시 토큰 상태 관리](ADR-006-jwt-valkey-refresh-token.md)
- [단일 검색창과 MySQL 기반 통합 검색](ADR-007-unified-search-mysql.md)

## 2026-07-27 날짜별 개정

### 현재 단계 결정

기존 본문과 최초 결정은 당시 판단 기록으로 보존한다. 현재 합의에서는 다음 네 단계를 적용하며, 같은 날짜의 과거 기술 시점 표현보다 이 절의 단계가 우선한다.

| 단계 | 활성 기술과 역할 | 제외 기술 |
|---|---|---|
| 1차 MVP | Java 21, Spring Boot 4.1.0, Gradle 9.6.1, MVC, JPA, Security, Flyway, MySQL, Testcontainers MySQL, stateless Access/Refresh JWT | Valkey, QueryDSL, Kakao 지도·로그인, S3, PortOne, SSE, 메시지 브로커, 검색 엔진 |
| 2차 MVP | 방식 A·MySQL 유지, `RuleInterpreter`, QueryDSL, 제한된 OpenAI 구조화 음식 개념 해석, 공개 batch 가용성 조회 계약, 매장 주소 등록·변경 시 Kakao Local API와 지도 SDK | 벡터 DB, 검색 엔진, 추천 캐시, 메시지 브로커 |
| 고도화 | Valkey Refresh Token 상태, Kakao 로그인, S3, 웨이팅·SSE, PortOne, 알림, 플랫폼 운영 기능, 기능별 MySQL durable task | 증거 없는 범용 분산 인프라 |
| 향후 고도화 | 별도 승인된 AI/LLM 또는 측정된 병목을 해결하는 인프라 | 승인 전 Kafka, 범용 Outbox, MSA, WebSocket, 검색 클러스터 |

### Testcontainers 전환 근거

- 과거의 초기 Testcontainers 미도입 결정은 실제 DB 상호작용이 없던 스캐폴딩 시점에는 유효했다.
- 1차 MVP가 Flyway 마이그레이션, MySQL 제약, 예약 수용량·회차별 팀 수·메뉴 홀드 경합, 잠금과 조건부 SQL을 구현하므로 기존 재검토 조건이 충족되었다. 이에 따라 Testcontainers MySQL을 1차 MVP 필수 검증 게이트로 활성화한다.
- H2나 인메모리 대체 DB는 MySQL 잠금·격리·제약 의미를 증명하지 못하므로 해당 통합 테스트의 대체재로 사용하지 않는다.

### 증거 게이트와 이행

- 새 기술은 현재 단계의 단순 구조로 재현한 병목·실패, 대안 비교, 운영 책임, 비용, 마이그레이션·되돌림 계획과 검증 명령을 승인한 뒤 도입한다.
- 고도화의 외부 효과는 결제·알림처럼 기능 목적이 명확한 MySQL durable task로 구현한다. 이 결정은 범용 Outbox 패턴이나 Kafka를 미리 승인하지 않는다.
- 단계 경계는 초기 기능 수를 줄이는 대신 후속 마이그레이션 비용을 만든다. 각 전환은 스키마 호환, 실패 복구, 보안 회귀와 미사용 단계의 의존성 부재를 함께 검증한다.
