# Backend AI 규칙

backend 작업 전에는 [`backend/ai/document-routing.md`](ai/document-routing.md)를 읽고 현재 backend 범위에 필요한 문서만 선택한다.

- backend의 소유 범위는 Spring Boot, Gradle, 영속성 및 마이그레이션, 서버 보안, 서버 명령, backend 검증으로 제한한다.
- API 계약 또는 backend/frontend 경계를 넘는 작업은 [루트 문서 라우팅](../ai/document-routing.md)으로 돌아가 [루트 통합 계약](../ai/integration-contracts.md)을 따른다.
- 관련 없는 사용자 변경 사항을 보존하고, 수정·stage·commit은 승인된 작업 허용 목록 안에서만 수행한다.
- 승인된 요구사항과 활성화 증거 없이 제품 엔드포인트, 빈 도메인 패키지, 인프라, 자격 증명, 연기된 도구를 추가하지 않는다.
- 저장소의 결과 어휘를 사용하여 실제로 실행한 명령과 실제로 관찰한 결과만 보고한다.
