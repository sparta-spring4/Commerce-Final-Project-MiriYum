# Frontend 명령 레지스트리

계약 상태: ACTIVE

아래의 모든 명령은 `frontend/`에서 실행한다.

| 명령 ID | 상태 | 목적 | 정확한 명령 | 필수 입력 | 예상 출력 | 증거 | 0이 아니거나 누락된 경우의 의미 |
|---|---|---|---|---|---|---|---|
| `frontend.pnpm.version` | `CONFIGURED` | 패키지 관리자 버전 | `pnpm --version` | 고정된 Node 및 Corepack 환경 | `11.17.0` | 캡처한 명령 출력 및 종료 코드 | `FAIL`; 실행 파일 누락은 `BLOCKED` |
| `frontend.install` | `CONFIGURED` | lockfile을 준수하는 설치 | `pnpm install --frozen-lockfile` | `package.json`, `pnpm-lock.yaml`, 레지스트리 접근 또는 완전한 로컬 store | lockfile 변경 없이 의존성이 설치됨 | 캡처한 설치 출력, 종료 코드, 변경되지 않은 lockfile diff | `FAIL`; 사용할 수 없는 레지스트리/store는 `BLOCKED` |
| `frontend.dev` | `NOT CONFIGURED` | 개발 서버 | `pnpm run dev` | 설치된 의존성 및 사용 가능한 로컬 포트 | Vite 개발 서버가 시작됨 | 관찰한 시작 출력 및 명시적 종료 | 구성되어 호출된 경우 0이 아니면 `FAIL`; 시도하지 않은 호출은 `NOT RUN` |
| `frontend.typecheck` | `CONFIGURED` | 타입 검사 | `pnpm run typecheck` | 설치된 의존성 및 TypeScript 소스/구성 | TypeScript가 진단 없이 종료함 | 캡처한 출력 및 종료 코드 | `FAIL` |
| `frontend.test` | `CONFIGURED` | 컴포넌트 테스트 | `pnpm run test` | 설치된 의존성 및 jsdom 호환 테스트 | Vitest가 모든 테스트 통과를 보고함 | 캡처한 테스트 요약 및 종료 코드 | `FAIL` |
| `frontend.build` | `CONFIGURED` | 프로덕션 빌드 | `pnpm run build` | 설치된 의존성 및 타입이 올바른 소스 | Vite가 `dist/`를 성공적으로 작성함 | 캡처한 빌드 요약, 종료 코드, ignore된 `dist/` 증명 | `FAIL` |

Lint 상태: `NOT CONFIGURED`. 이 최소 스캐폴드에는 lint script나 lint 구성이 없으므로 lint를 `PASS`로 보고해서는 안 된다.
