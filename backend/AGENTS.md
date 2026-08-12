# Backend AI 규칙

backend 작업 전에는 [`backend/ai/document-routing.md`](ai/document-routing.md)를 읽고 현재 backend 범위에 필요한 문서만 선택한다.

- backend의 소유 범위는 Spring Boot, Gradle, 영속성 및 마이그레이션, 서버 보안, 서버 명령, backend 검증으로 제한한다.
- API 계약 또는 backend/frontend 경계를 넘는 작업은 [루트 문서 라우팅](../ai/document-routing.md)으로 돌아가 [루트 통합 계약](../ai/integration-contracts.md)을 따른다.
- 관련 없는 사용자 변경 사항을 보존하고, 수정·stage·commit은 승인된 작업 허용 목록 안에서만 수행한다.
- 승인된 요구사항과 활성화 증거 없이 제품 엔드포인트, 빈 도메인 패키지, 인프라, 자격 증명, 연기된 도구를 추가하지 않는다.
- 저장소의 결과 어휘를 사용하여 실제로 실행한 명령과 실제로 관찰한 결과만 보고한다.
- 최상위 도메인 목록은 활성 정본 [`ai/implementation-guardrails.md`](ai/implementation-guardrails.md)의 package tree를 따르며 `DomainPackageArchitectureTest`의 정확한 allowlist와 일치시킨다. `booking`, `account`, `application` wrapper package를 만들지 않는다.
- 다른 도메인은 소유자의 공개 Service 메서드와 DTO로만 사용하며 Entity·Repository를 직접 참조하지 않는다. 통합 검색·추천의 읽기 전용 QueryDSL 예외는 활성 정본에 명시된 정확한 reader와 대상 도메인에만 허용한다.
- 선행 계약이 없으면 가짜 구현을 만들지 않고 `BLOCKED`로 보고한다.

## 중단 조건

다음 중 하나면 해당 범위의 구현을 중단한다.

- 비소유 도메인의 Entity·Repository·공개 Service 메서드·DTO·API·에러 코드가 필요하다.
- 기능 명세와 OpenAPI가 충돌한다.
- 새 공개 route·role·state·error code가 필요하다.
- 승인되지 않은 provider·version·수치 또는 후속 단계 기술이 필요하다.
- CI나 배포가 구성됐다고 가정해야만 진행할 수 있다.

독립적인 범위만 계속하고, 필요한 계약은 contract-first Issue·PR로 요청한다. production dummy나 중복 API로 중단 조건을 우회하지 않는다.
