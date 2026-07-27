# Backend 문서 라우팅

계약 상태: ACTIVE

Backend 스캐폴드 검증 표면 상태: CONFIGURED

Backend 애플리케이션/제품 런타임 상태: NOT CONFIGURED

## 시작 경로

backend 전용 구현, 명령 실행, 검증, 검토에는 이 문서를 사용한다. 작업이 API, 인증 흐름, 오류 의미, 데이터 형상 또는 frontend가 사용하는 계약을 변경하면 먼저 루트 [문서 라우팅](../../ai/document-routing.md)을 읽는다.

작업에 필요한 로컬 소유자만 선택한다.

- Spring Boot, Gradle, 영속성, 마이그레이션 또는 서버 보안 구현: [구현 가드레일](implementation-guardrails.md)
- 검증된 backend 명령: [명령 레지스트리](command-registry.md)
- Backend 완료 증거 및 결과 상태: [검증 게이트](verification-gates.md)

## 정본 소유자

저장소 전반의 아키텍처는 [시스템 아키텍처](../../docs/06-system-architecture.md)에, 데이터 및 API 규칙은 [데이터 및 API 계약](../../docs/07-data-and-api-contracts.md)에, 품질 정책은 [품질 운영 및 규칙](../../docs/09-quality-operations-and-rules.md)에 유지한다. 로컬 문서는 제품 또는 cross-end 계약을 재정의하지 않고 이러한 소유자 규칙을 backend에 적용한다.

## 확장 조건

- 작업이 해당 기능을 구현하거나 변경할 때만 기능 명세를 읽는다.
- cross-end 작업에는 루트 [통합 계약](../../ai/integration-contracts.md)을 사용한다.
- 마이그레이션이 있을 때만 마이그레이션 전용 자료를 추가한다.
- 승인된 Testcontainers 변경이 ADR-002 및 ADR-004를 충족한 후에만 DB 의존 통합 명령을 활성화한다.
- backend 스캐폴드만으로 CI, Docker, API 스모크, 배포, runner, 스키마, skill, cache 또는 hook 기능을 추론하지 않는다.
