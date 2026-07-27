# Frontend AI 규칙

frontend 작업 전에는 이 디렉터리의 `ai/document-routing.md`를 먼저 읽는다.

변경이 backend/frontend 경계를 넘거나 공유 API 계약을 변경하면 수정 전에
루트 `AGENTS.md`와 `ai/document-routing.md`로 돌아간다.

- frontend 변경은 React, TypeScript, Vite, 접근성,
  client 경계 동작 및 그 로컬 검증 범위 안에 둔다.
- 관련 없는 변경 사항을 보존하고 승인된 작업 허용 목록을 사용한다.
- 실제로 관찰한 명령과 결과만 보고한다.
- 저장소 전반의 제품, API, 아키텍처, 품질 규칙은
  `../docs/` 아래의 정본 문서에 유지한다.
