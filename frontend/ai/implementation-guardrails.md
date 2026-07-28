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

1차 MVP에서는 일반 사용자와 매장 운영자 화면·인증 상태·권한을 분리하고 플랫폼 운영자 화면·라우트·SDK를 만들지 않는다. 플랫폼 운영자 화면 분리는 해당 기능을 구현하는 고도화에서 적용한다. 와이어프레임은 정보 구조와 사용자 흐름을 이해하는 참고 자료로만 사용한다. 시안에 보이는 웨이팅·결제·관리자 기능, 수치, 상태와 문구가 현재 단계에 승인됐다고 추론하지 않는다. 정본 기능 명세·OpenAPI와 다르면 구현을 멈추고 차이를 보고한다.

## Client 경계

서버 endpoint, payload, 인증, 오류 코드, 시간 또는 금액 표현을 추측하지 않는다. 변경 사항은 `../../docs/07-data-and-api-contracts.md` 및 해당 기능 명세에 연결한다. cross-end 변경은 구현 전에 루트 통합 경로를 따라야 한다.

- 같은 backend API 계약을 재사용하고 OpenAPI에 없는 field, path, status 또는 fake response를 만들지 않는다.
- 인증 주체·권한은 token과 backend 결과를 기준으로 하며 client 입력 role로 승격하지 않는다.
- 성공은 HTTP status와 공통 response `code`, 실패 UI는 확정 error code로 판정한다.
- 빈 배열은 정상 empty result, `data: null`은 정상적인 응답 데이터 없음으로 처리한다.
- 계약이 부족하면 임시 DTO나 응답을 만들지 않고 contract-first Issue·PR을 요청한다.
- 지도·결제·추천·Valkey 등 후속 기능을 현재 단계에 선도입하지 않는다.

## 변경 격리

관련 없는 사용자 변경 사항을 보존한다. `verification-gates.md`의 해당 명령이 통과한 후에만 승인된 허용 목록을 수정·stage·commit한다. 자격 증명, 생성된 의존성 또는 프로덕션 빌드 출력을 Git에 저장하지 않는다.
