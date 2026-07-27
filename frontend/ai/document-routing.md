# Frontend 문서 라우팅

계약 상태: ACTIVE

## 여기서 시작

`../AGENTS.md`를 읽은 뒤 변경에 필요한 문서만 선택한다.

- React, TypeScript, Vite, 접근성, client 상태 또는 frontend 전용
  검증: 이 디렉터리의 로컬 계약과
  `../../docs/08-ui-and-frontend-guidelines.md`를 사용한다.
- API 형상, 오류 의미, 시간, 금액 또는 멱등성:
  `../../docs/07-data-and-api-contracts.md`를 사용한다.
- cross-end 동작, 인증, 인가 또는 공유 인수 조건
  흐름: `../../AGENTS.md`, `../../ai/document-routing.md`,
  `../../ai/integration-contracts.md`로 돌아간다.
- 검증 및 완료 주장: `verification-gates.md`,
  `../../docs/09-quality-operations-and-rules.md`,
  `../../ai/verification-and-completion.md`를 사용한다.

## 소유권 경계

이 frontend 경로는 React 렌더링, TypeScript 및 Vite 구성,
접근 가능한 client 동작, frontend 명령 정의, frontend
검증 증거를 소유한다. backend 동작, 저장소 전반의 API
의미 체계, 제품 정책, 인프라, CI, 배포는 소유하지 않는다.

경로, payload, 기능 폴더, API client 또는 외부 런타임 상태를 임의로 만들지 않는다. 해결되지 않은 cross-end 결정은 루트 소유자에게 보낸다.
