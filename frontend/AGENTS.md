# Frontend AI 규칙

frontend 작업 전에는 이 디렉터리의 `ai/document-routing.md`를 먼저 읽는다.

변경이 backend/frontend 경계를 넘거나 공유 API 계약을 변경하면 수정 전에
루트 `AGENTS.md`와 `ai/document-routing.md`로 돌아간다.

- API를 소비하는 작업은 루트 `ai/integration-contracts.md`와 관련 기능 OpenAPI를 먼저 읽는다.
- frontend 변경은 React, TypeScript, Vite, 접근성,
  client 경계 동작 및 그 로컬 검증 범위 안에 둔다.
- 관련 없는 변경 사항을 보존하고 승인된 작업 허용 목록을 사용한다.
- 실제로 관찰한 명령과 결과만 보고한다.
- 저장소 전반의 제품, API, 아키텍처, 품질 규칙은
  `../docs/` 아래의 정본 문서에 유지한다.
- 일반 사용자와 매장 운영자 화면·권한 경계는 분리하되 같은 backend 계약을 소비한다. 플랫폼 운영자 경계는 승인된 단계에서만 추가한다.
- 와이어프레임은 화면 구조 참고 자료이며 API·권한·상태·기능 단계의 정본으로 사용하지 않는다.
