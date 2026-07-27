# Frontend 구현 가드레일

계약 상태: ACTIVE

## 기술 경계

- `package.json`에 고정된 버전과 커밋된
  `pnpm-lock.yaml`을 사용한다.
- TypeScript를 strict하게 유지하고 Vite/React 시작 경계를 보존한다.
- 향후 제품 코드는 승인된 기능 범위별로 구성하며, 기능 폴더, routing, 상태 도구, UI 라이브러리, API client를 미리 만들지 않는다.
- 승인된 요구사항에 필요하고 그 검증이 정의된 경우에만 의존성을 추가한다.

## UI 및 접근성

의미론적 HTML, 눈에 보이고 예측 가능한 키보드 포커스, 연결된 레이블,
보조 기술이 읽을 수 있는 상태 및 오류 메시지를 사용한다. 색상만으로
의미를 전달하지 않는다. 사용자 대면 동작이 존재할 때
`../../docs/08-ui-and-frontend-guidelines.md`의 정본 규칙을 적용한다.

## Client 경계

서버 endpoint, payload, 인증, 오류 코드, 시간 또는 금액 표현을 추측하지 않는다. 변경 사항은 `../../docs/07-data-and-api-contracts.md` 및 해당 기능 명세에 연결한다. cross-end 변경은 구현 전에 루트 통합 경로를 따라야 한다.

## 변경 격리

관련 없는 사용자 변경 사항을 보존한다. `verification-gates.md`의 해당 명령이 통과한 후에만 승인된 허용 목록을 수정·stage·commit한다. 자격 증명, 생성된 의존성 또는 프로덕션 빌드 출력을 Git에 저장하지 않는다.
