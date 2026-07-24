# Frontend 검증 게이트

계약 상태: ACTIVE

## 필수 순서

`frontend/`에서 다음을 실행한다.

1. `frontend.pnpm.version` — `pnpm --version`
2. `frontend.install` — `pnpm install --frozen-lockfile`
3. `frontend.typecheck` — `pnpm run typecheck`
4. `frontend.test` — `pnpm run test`
5. `frontend.build` — `pnpm run build`

모든 게이트에 대해 정확한 명령, 작업 디렉터리, 종료 코드, 결과 어휘 값,
간결한 증거를 기록한다. 0이 아닌 명령은 `FAIL`이며, 사용할 수 없는
runtime 또는 필수 외부 입력은 `BLOCKED`이다. Lint는 `NOT CONFIGURED`이다.

Task 4는 이 다섯 `CONFIGURED` 명령 ID만 조합할 수 있다. 시작 및
명시적 종료가 검증되지 않아 레지스트리 상태가 `NOT CONFIGURED`인
`frontend.dev`는 제외해야 하며, lint를 `NOT CONFIGURED`에서 승격해서는 안 된다.

## 완료 검사

명령 실행 후 다음을 수행한다.

- frozen install 동안 lockfile이 변경되지 않았는지 확인한다.
- `node_modules/` 및 `dist/`가 존재하고 `../.gitignore`의 정확한 두 규칙으로 ignore되는지 확인한다.
- 저장소에 보이는 frontend 변경 사항을 승인된 정확한 허용 목록과 비교한다.
- 별도 staging 승인 전에 staged index가 비어 있는지 확인한다.
- frontend 작업으로 루트 및 backend 경로가 변경되지 않았는지 확인한다.
- 기존에 사용자가 수정한 모든 경로와 해시를 보존하고 다시 확인한다.

`../../ai/verification-and-completion.md`에 정의된 결과 어휘와 완료 주장 필드만 사용한다. 구성 텍스트, 계획된 명령, 생성된 산출물을 실행 증거로 취급하지 않는다.
