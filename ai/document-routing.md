# Document routing

Contract status: ACTIVE

## 목적과 최소 읽기

이 문서는 작업의 route, 기준 문서 소유자, 문서 확장 시점을 결정한다. 저장소에 파일이 있다는 이유만으로 읽지 않으며 현재 질문, feature, 주제, 작업 단계에 필요한 최소 읽기만 선택한다. 제품·정책·아키텍처 내용을 이 문서에 복제하지 않는다.

다섯 가지 기본 동작을 유지한다.

1. Answer Mode, Light Route, Work Route 중 하나로 route를 분류한다.
2. feature와 주제 소유자를 먼저 정한다.
3. 단계별 진입 시점에 필요한 문서를 늦게 확장한다.
4. 실제 실행 파일과 성공 증거가 확인된 명령만 후속 registry에 등록한다.
5. 명령·결과·위험·증거가 없는 완료 주장을 차단한다.

## Route 분류

### Answer Mode

저장소 변경, 규범 결정, 실행 또는 검증 주장 없이 작은 질문에 답할 때 사용한다. 질문에 직접 필요한 파일만 읽고 전체 제품 문서, feature spec, 실행 문서와 완료 문서를 기본으로 열지 않는다.

### Light Route

좁은 구조 탐색, 최초 방향 파악, 비규범 문구 또는 색인 유지보수에 사용한다. 변경이 요구사항, 동작, 계약, 아키텍처, 보안 또는 완료 기준에 영향을 주면 계속하기 전에 Work Route로 재라우팅한다.

### Work Route

계획, 규범 문서, 기능 결정, 코드 변경, review, 검증 또는 완료 작업에 사용한다. Issue의 범위와 담당자를 확인하고, feature 및 주제 소유 문서를 선택한 뒤 현재 단계에 필요한 문서만 추가한다.

## 작업 범위 분류

| 분류 | 최소 필독 | 조건부 추가 문서 | 확장 trigger |
|---|---|---|---|
| backend-only | 관련 `docs/` 정본과 `backend/AGENTS.md` | `backend/ai/document-routing.md`에서 선택하는 엔드 문서 | backend 범위를 구현하거나 검증함 |
| frontend-only | 관련 `docs/` 정본과 `frontend/AGENTS.md` | `frontend/ai/document-routing.md`에서 선택하는 엔드 문서 | frontend 범위를 구현하거나 검증함 |
| cross-end | `ai/integration-contracts.md`, `docs/07-data-and-api-contracts.md` | 관련 `docs/specs/<feature>/spec.md`와 활성화된 엔드 문서 | API·인증·오류·데이터 handoff 또는 통합 검증이 범위에 포함됨 |

검증된 엔드 스캐폴드와 로컬 AI 계약이 존재하므로 backend-only는 `backend/AGENTS.md -> backend/ai/document-routing.md`, frontend-only는 `frontend/AGENTS.md -> frontend/ai/document-routing.md`로 라우팅한다. cross-end는 `ai/integration-contracts.md`와 필요한 엔드 문서로 라우팅한다. 엔드 경로가 활성화됐다는 사실만으로 API 또는 cross-end runtime이 구성됐다고 추론하지 않는다.

## 주제 소유자 선택

| 주제 | 최소 필독 기준 문서 | 조건부 추가 | 확장 trigger |
|---|---|---|---|
| 제품 목적·범위 | `docs/01-product-vision.md` | 관련 서비스 정책 | 사용자 가치, 범위 또는 비목표가 바뀜 |
| 사용자·권한·보안 | `docs/02-users-and-permissions.md` | 관련 보안·개인정보 서비스 정책 | 역할, 인증, 인가 또는 개인정보 경계가 바뀜 |
| 도메인·사용자 흐름 | `docs/03-domain-model.md` 또는 `docs/04-user-flows.md` | 관련 기능 명세와 서비스 정책 | 상태, 관계, 성공·실패 흐름이 바뀜 |
| 기능 요구사항 | `docs/05-functional-requirements.md` | 관련 `docs/specs/<feature>/spec.md`와 서비스 정책 | 영구 동작, 계약 또는 인수 조건을 결정함 |
| 아키텍처 | `docs/06-system-architecture.md` | 관련 `docs/adr/` 기록 | 지속적이고 여러 영역에 영향을 주는 기술 결정을 함 |
| 데이터·API | `docs/07-data-and-api-contracts.md` | 관련 기능 명세와 `ai/integration-contracts.md` | 데이터 원본, API, 이벤트 또는 cross-end handoff가 바뀜 |
| UI·frontend | `docs/08-ui-and-frontend-guidelines.md` | 관련 기능 명세 | 화면 구조, 상태 처리 또는 접근성 계약이 바뀜 |
| 품질·운영 | `docs/09-quality-operations-and-rules.md` | 검증·완료 단계 문서 | 테스트 수준, 관측 또는 배포 전환 조건이 바뀜 |

`docs/00-index.md`는 사람용 지식 지도다. 상세 규칙의 소유자가 아니므로 필요한 주제를 찾은 뒤 해당 정본을 읽는다.

## 조건부 기준 상태와 runtime 문서

문서 계약과 실행 capability를 구분한다. `ACTIVE`는 문서 계약이 적용됨을 뜻하고, `TEMPLATE`은 환경 의존 입력이 아직 채워지지 않은 계약을 뜻한다. runtime의 `CONFIGURED`는 실제 실행 surface와 성공·실패 증거가 검증된 경우에만 사용한다. `NOT CONFIGURED`는 대응 명령이나 스크립트가 아직 없다는 뜻이며 성공이 아니다. `NOT APPLICABLE`은 현재 범위에 적용되지 않는 이유가 기록된 상태다.

조건부 문서는 해당 trigger가 충족되고 현재 범위에 필요할 때만 읽는다. 계획, wrapper/package script 또는 경로 이름만으로 runtime, 명령, CI, skill, cache나 hook의 존재를 추론하지 않는다.

## 단계별 문서 확장

1. **계획:** Issue의 결과, 정확한 경로, 인수 조건, 검증 계획과 위험을 확인한다. 기능 동작이나 계약을 바꿀 때만 관련 기능 명세를 확장한다.
2. **구현:** 선택한 feature와 주제의 정본 및 실제 수정 경로의 로컬 지침만 읽는다.
3. **검증:** 변경 유형에 맞는 gate를 결정할 때 `ai/verification-and-completion.md`를 읽는다. 실제 명령만 실행하고 관찰 결과를 보존한다.
4. **review:** 인수 조건, diff, 문서 단일 원본, 위험과 검증 증거를 대조한다.
5. **완료:** 명령·결과·위험·증거가 모두 있을 때만 완료 문구를 작성한다.

단계가 바뀌었다는 이유로 관련 없는 단계 문서를 미리 읽지 않는다.

## 검증 및 완료 확장

검증, QA, CI, reviewer, Issue completion 또는 done claim 단계에 진입할 때만 `ai/verification-and-completion.md`를 확장한다. 실행하지 않은 검사를 성공으로 쓰지 않고, 구성되지 않은 runtime은 `NOT CONFIGURED`, 현재 범위에 적용되지 않는 검사는 이유와 함께 `NOT APPLICABLE`로 기록한다.

## 문서 변경 라우팅

- 제품·정책·아키텍처·기능·품질 사실은 해당 `docs/` 정본에서만 변경하고 다른 문서는 링크만 갱신한다.
- 사람의 저장소 진입과 기여 흐름은 `README.md`, `docs/00-index.md`, `CONTRIBUTING.md`가 소유한다.
- route와 읽기 trigger 변경은 이 문서가 소유한다.
- 검증된 엔드 command ID의 루트 실행 순서와 위임은 `ai/command-registry.md`가 소유한다.
- cross-end 분류와 handoff 증거 경계 변경은 `ai/integration-contracts.md`가 소유한다.
- 검증 결과 vocabulary와 완료 증거 변경은 `ai/verification-and-completion.md`가 소유한다.
- Issue는 범위·담당자·인수 조건·검증 계획을, GitHub Project의 단일 `Status`는 lifecycle을, PR과 CI는 실제 명령·결과·증거를 소유한다.

영구 work log나 lifecycle mirror를 만들지 않는다.

## 위임 경로

현재 담당자는 위임 범위, 필수 입력, 예상 출력, 금지 주장과 증거 형식을 명시한다. delegate 결과는 원래 Issue, 현재 diff, 정본과 검증 gate에 다시 대조한 뒤 통합한다. 임시 artifact가 필요하면 ignored `.superpowers/sdd/` 아래에만 두며 현재 담당자가 통합·검증·완료 책임을 유지한다.

## 범위 변경과 재라우팅

새 feature, 주제, endpoint, 외부 부작용, 실행 capability 또는 완료 기준이 범위에 들어오면 편집을 멈추고 route, 소유자, 최소 필독, 조건부 문서와 allowlist를 재평가한다. Light Route의 규범 영향이나 단일 엔드 작업의 cross-end 영향도 같은 방식으로 Work Route 또는 통합 경계로 재라우팅한다.

## Backend·frontend 스캐폴드 연결

Backend·frontend 스캐폴드, 각 엔드의 `AGENTS.md`와 로컬 AI 계약, wrapper/package script 및 실행 증거가 생성되고 검증됐다. 엔드별 문서는 활성화됐으며 루트 검증 조합은 `ai/command-registry.md`에서 검증된 command ID만 위임한다.

이 상태는 제품 API, 인증, 데이터 handoff 또는 통합 사용자 흐름의 runtime을 구성하지 않는다. 그러한 cross-end 실행은 실제 surface와 성공·실패 증거가 생길 때까지 `NOT CONFIGURED`다.

반복 workflow와 실패 사례가 실제 확인된 뒤에만 runner, machine-readable schema, CI, skill, `lazycodex`, cache 책임을 별도 설계·계획으로 승격한다. 이 Phase 1 문서는 그 capability가 구성됐다고 주장하지 않는다.
