# 루트 명령 조합 레지스트리

계약 상태: ACTIVE

이 레지스트리는 검증된 엔드포인트 command ID를 조합한다. 리터럴 backend 또는 frontend 명령을 소유하거나 반복하지 않는다. 연결된 엔드포인트 레지스트리를 통해 각 delegate를 확인하고 해당 엔드포인트 증거를 보존한다.

- [Backend 명령 레지스트리](../backend/ai/command-registry.md)
- [Frontend 명령 레지스트리](../frontend/ai/command-registry.md)

루트 ID의 `CONFIGURED`는 순서가 지정된 모든 delegate가 `CONFIGURED`이며 조합 계약을 사용할 수 있음을 뜻한다. 이는 루트 runner, CI workflow, cross-end runtime 또는 현재 호출이 통과했음을 뜻하지 않는다.

## 루트 ID

| 루트 ID | 상태 | 순서가 지정된 delegate ID | 중지 조건 | 증거 경계 |
|---|---|---|---|---|
| `root.verify.backend` | `CONFIGURED` | 1. `backend.wrapper.version`<br>2. `backend.test`<br>3. `backend.build` | 첫 번째 `PASS` 이외의 delegate 결과에서 중지한다. 실행하지 않은 나머지는 `NOT RUN`으로 보고한다. | 각 backend 결과는 backend 증거와 함께 보존한다. 루트 결과에는 순서, delegate 결과, 위험 및 증거 참조만 기록한다. |
| `root.verify.frontend` | `CONFIGURED` | 1. `frontend.pnpm.version`<br>2. `frontend.install`<br>3. `frontend.typecheck`<br>4. `frontend.test`<br>5. `frontend.build` | 첫 번째 `PASS` 이외의 delegate 결과에서 중지한다. 실행하지 않은 나머지는 `NOT RUN`으로 보고한다. | 각 frontend 결과는 frontend 증거와 함께 보존한다. 루트 결과에는 순서, delegate 결과, 위험 및 증거 참조만 기록한다. |
| `root.verify.scaffold` | `CONFIGURED` | 1. `root.verify.backend`<br>2. `root.verify.frontend` | 엔드포인트 조합 결과가 `PASS`가 아니면 중지한다. 실행하지 않은 엔드포인트 조합은 `NOT RUN`으로 보고한다. | 두 엔드포인트 증거 세트의 참조를 조합한다. 이는 scaffold 검증이지 cross-end 통합 증거가 아니다. |

## 활성화 증거

- `root.verify.backend`는 세 delegate가 모두 `CONFIGURED`이고 Task 2가 완성된 backend scaffold에 대해 각 delegate 명령의 성공적인 종료를 관찰했으므로 `CONFIGURED`이다.
- `root.verify.frontend`는 다섯 delegate가 모두 `CONFIGURED`이고 Task 3이 완성된 frontend scaffold에 대해 각 delegate 명령의 성공적인 종료를 관찰했으므로 `CONFIGURED`이다.
- `root.verify.scaffold`는 두 엔드포인트 조합을 결정적인 순서로 사용할 수 있으므로 `CONFIGURED`이다. 이는 API 호환성, 인증 동작, 데이터 handoff 또는 통합 사용자 흐름을 확립하지 않는다.

`frontend.dev`는 레지스트리 상태가 `NOT CONFIGURED`이므로 제외한다. 시작과 명시적 종료는 여전히 검증되지 않았다. `NOT RUN`은 이전 중지 조건 이후 건너뛴 구성된 delegate를 포함하여 호출 결과에만 사용한다. Lint는 `NOT CONFIGURED`이므로 제외한다.

## 결과 및 증거 규칙

[검증 및 완료](verification-and-completion.md)의 결과 vocabulary와 완료 주장 필드 네 가지를 사용한다. 루트 결과는 해당 호출에서 순서가 지정된 모든 delegate가 실행되어 `PASS`를 반환한 경우에만 `PASS`이다. 레지스트리 항목이나 이전 활성화 실행은 현재 실행 증거가 아니다.

엔드포인트 출력, 작업 디렉터리, 종료 코드 및 artifact는 엔드포인트 증거와 함께 보관한다. 루트 증거에는 루트 ID, 순서가 지정된 delegate, 관찰된 결과, 중지 지점, 남은 위험 및 해당 엔드포인트 기록의 링크 또는 경로를 기록한다. 엔드포인트 로그를 이 레지스트리에 복사하지 않는다.

cross-end API, 인증, 오류 및 데이터 handoff 작업은 [루트 통합 계약](integration-contracts.md)을 따른다. 조합된 cross-end 통합 실행은 `NOT CONFIGURED` 상태를 유지한다.
