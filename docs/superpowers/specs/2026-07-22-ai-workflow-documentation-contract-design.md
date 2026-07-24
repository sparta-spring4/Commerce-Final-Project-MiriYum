# MiriYum AI 워크플로 문서·실행 계약 설계

**작성일:** 2026-07-22

**상태:** 사용자 문서 검토 대기

**범위:** AI 워크플로 문서 구조와 후속 실행 게이트(gate) 구현 계약

## 1. 목적

MiriYum의 AI 작업이 필요한 문서만 늦게 읽고, 작업 단계에 맞는 검증을 빠뜨리지 않도록 문서 라우팅과 실행 계약을 먼저 정의한다.

이 설계는 프로젝트 인원수를 근거로 기능을 축소하지 않는다. 적용 여부는 작업 복잡도, 변경 위험, 검증 요구, 자동화 가능성과 저장소의 실제 실행 역량(capability)으로 판단한다.

완료 순서는 다음과 같다.

```text
AI 워크플로 문서·계약 완성
  -> 실행 스크립트·JSON schema·registry·CI gate 구현
  -> 실행 gate 검증
  -> 제품 기능 구현 시작
```

문서 단계에서는 스크립트가 구현될 때 필요한 입력, 출력, 상태, 증거와 실패 의미를 확정한다. 실제 명령, 포트, 작업(job) 이름처럼 아직 존재하지 않는 런타임(runtime) 값만 명시적인 미구성 상태로 남긴다.

## 2. 핵심 결정

### 2.1 전체 색인을 만들지 않는다

`ai/README.md`는 만들지 않는다. 전체 문서 목록을 먼저 보여 주면 에이전트가 관련 없는 문서까지 읽을 가능성이 높아지고, `document-routing`의 점진적 공개 목적이 약해진다.

항상 읽는 진입점은 짧은 루트 `AGENTS.md`뿐이다. 이 파일은 작업을 시작할 때 `ai/document-routing.md`에서 경로를 선택하라고 지시하고, 세부 정책을 복제하지 않는다.

```text
AGENTS.md
  -> ai/document-routing.md
       -> 현재 작업과 단계에 필요한 문서만 선택
```

### 2.2 문서 라우팅 구조를 유지한다

`ai/document-routing.md`는 레퍼런스 문서의 기능과 섹션 구조를 축소하지 않는다. MiriYum 경로와 소유권으로 바꾸되 다음 항목을 모두 유지한다.

1. 목적과 최소 읽기 원칙
2. 응답 모드(Answer Mode), 경량 경로(Light Route), 작업 경로(Work Route)
3. 작업 단계별 문서 확장
4. 주제별 기준 문서 선택
5. 조건부 기준 상태와 런타임(runtime) 문서
6. 검증 및 완료 단계 확장
7. 문서 변경 라우팅
8. 위임 경로
9. 범위 변경 시 재라우팅 규칙

라우팅 표에 파일이 존재한다고 해서 항상 읽지 않는다. 각 행은 트리거, 최소 필독 문서, 조건부 추가 문서와 확장 시점을 분리한다.

### 2.3 문서와 실행 역량(capability)을 구분한다

문서가 존재하는 것과 실행 기능이 구성된 것은 다른 상태다. 각 실행 연계 문서는 다음 메타데이터를 사용한다.

```markdown
Contract status: ACTIVE | TEMPLATE
Runtime status: CONFIGURED | NOT CONFIGURED | NOT APPLICABLE
Canonical owner: <문서 또는 실행 surface>
Activation trigger: <실행 구현을 시작할 조건>
Required inputs: <필수 입력>
Expected outputs: <출력과 증거>
Do not infer: <현재 문서로 주장할 수 없는 내용>
```

- `ACTIVE`: 문서 계약이 확정되어 현재부터 적용된다.
- `TEMPLATE`: 문서의 책임과 구조는 확정됐지만 환경 의존 값이 아직 없다.
- `CONFIGURED`: 실제 실행 surface가 존재하고 검증됐다.
- `NOT CONFIGURED`: 실행 계약은 있으나 대응하는 명령이나 스크립트가 없다.
- `NOT APPLICABLE`: 해당 작업에는 적용되지 않으며 이유가 기록됐다.

`TEMPLATE`와 `NOT CONFIGURED`는 성공을 의미하지 않는다. 실행 증거 없이 `CONFIGURED`, `PASS` 또는 완료를 주장할 수 없다.

## 3. 문서 구조

### 3.1 진입 및 라우팅

| 파일 | 책임 | 기본 읽기 시점 |
|---|---|---|
| `AGENTS.md` | 최소 전역 규칙과 라우터 진입점 | 모든 저장소 작업 시작 |
| `ai/document-routing.md` | 읽을 문서와 확장 시점 결정 | `AGENTS.md` 다음 |
| `ai/context-map.md` | 작업 단계(phase), 기능 소유권(feature ownership), 인계 맥락(handoff context) 정의 | 작업 경로(Work Route) 또는 위임이 활성화될 때 |

### 3.2 전역 작업 정책

| 파일 | 책임 | 초기 상태 |
|---|---|---|
| `ai/agent.rules.md` | 상세 AI 작업 규칙 | `ACTIVE` |
| `ai/implementation-guardrails.md` | 구조·계약·보안·데이터 변경 경계 | `ACTIVE` |
| `ai/tool-call-policy.md` | 읽기, 쓰기, 외부 부작용과 승인 경계 | `ACTIVE` |
| `ai/resource-budget.md` | 탐색 범위와 컨텍스트 확장 기준 | `ACTIVE` |
| `ai/remove-ai-slop.md` | 추측, 불필요한 추상화, 중복 문서 방지 | `ACTIVE` |
| `ai/agent-mistakes.md` | 재발 방지가 필요한 검증된 실수 기록 | 빈 사례 섹션을 가진 `TEMPLATE` |

전역 정책은 제품 규칙의 사본이 아니다. 제품·도메인·API·품질 규칙은 각각 `docs/`, `adr/`, `specs/`의 기준 문서를 링크한다.

### 3.3 프로젝트 상태와 명령 계약

| 파일 | 책임 | 초기 상태 |
|---|---|---|
| `ai/project-state.md` | 알려진 경로, 환경, 포트, 도우미(helper) 상태의 사람용 계약 | `TEMPLATE` / 런타임(runtime) `NOT CONFIGURED` |
| `ai/command-registry.md` | 안정적인 명령 ID(command ID), 선행 조건, 출력, 증거, 실패 의미 | `TEMPLATE` / 런타임(runtime) `NOT CONFIGURED` |
| `ai/cache-policy.md` | 캐시 가능한 정보와 무효화 기준 | `TEMPLATE` |
| `ai/workflow-cache.md` | 워크플로 캐시(workflow cache) 입력·출력·최신성 계약 | `TEMPLATE` / 런타임(runtime) `NOT CONFIGURED` |
| `ai/native-runtime-adapters.md` | 로컬·CI 실행 어댑터(adapter) 경계와 신뢰 수준 | `TEMPLATE` / 런타임(runtime) `NOT CONFIGURED` |

`project-state.md`와 `command-registry.md`에는 존재하지 않는 명령이나 포트를 추측해서 채우지 않는다. 대신 안정적인 ID, 필요한 입력, 활성화 조건과 예상 증거를 먼저 정의한다.

예시:

```markdown
Command ID: backend.test
Runtime status: NOT CONFIGURED
Required input: backend Gradle Wrapper와 테스트 source set
Activation trigger: 백엔드 스캐폴딩 완료 후, 첫 제품 기능 구현 전
Expected evidence: 실행 명령, exit code, 테스트 요약, artifact 경로
Failure meaning: 제품 구현 진입 차단
```

### 3.4 이슈(Issue), 서브에이전트와 인계

| 파일 | 책임 | 초기 상태 |
|---|---|---|
| `ai/github-issue-planning.md` | 이슈(Issue) 준비, 범위, 인수 조건과 상태 전환 | `ACTIVE` |
| `ai/github-issue-template.md` | AI가 생성·검토할 이슈(Issue) 필드 계약 | `ACTIVE` |
| `.github/ISSUE_TEMPLATE/work-item.md` | GitHub에서 사용하는 실제 이슈(Issue) 입력 표면(surface) | `ACTIVE` |
| `ai/subagent-workflow.md` | 라우팅 게이트(routing gate), 역할, 배정(dispatch), 증거와 재개 규칙 | `ACTIVE` |
| `ai/agent-handoff.md` | 기능 소유, 읽은 문서, 결정, 미해결 질문, 남은 증거 | `ACTIVE` |

이슈(Issue)가 작업 범위와 담당자를, GitHub Project의 단일 `Status` 필드가 수명 주기(lifecycle)를, PR·CI가 실행 결과와 증거를 소유한다. 별도 작업 로그(work log)는 같은 상태를 복제하므로 생성하지 않는다.

### 3.5 검증과 완료

| 파일 | 책임 | 초기 상태 |
|---|---|---|
| `ai/verification-levels.md` | 변경 유형별 필요한 검증 깊이 | `ACTIVE` |
| `ai/verification-gates.md` | 게이트(gate) 진입점, 적용 가능성, 결과 매핑, 증거 경계 | 계약 `ACTIVE`, 런타임(runtime) `NOT CONFIGURED` |
| `ai/qa-gate.md` | 필수 검사, API 증거, 위임 증거, QA 결과 | 계약 `ACTIVE`, 런타임(runtime) 일부 `NOT CONFIGURED` |
| `ai/ci-gates.md` | CI 출처(provenance), 필수 검사(required check)와 내구성 증거 | `TEMPLATE` / 런타임(runtime) `NOT CONFIGURED` |
| `ai/reviewer-checklist.md` | 요구사항, 구조, 데이터, 테스트, 문서 검토 | `ACTIVE` |
| `ai/issue-completion-checklist.md` | 구현 전부터 Issue 종료까지의 완료 절차 | `ACTIVE` |
| `ai/done-claim-template.md` | 완료 주장에 필요한 명령·결과·위험·증거 | `ACTIVE` |
| `ai/lazycodex-runbook.md` | 검증 회피와 증거 없는 완료 주장 교정 | `ACTIVE` |

`lazycodex-runbook.md`는 기본 필독 문서가 아니다. 다음 경고 신호가 나타날 때 `document-routing.md`가 이 문서를 선택한다.

- 실행하지 않은 테스트를 성공처럼 표현함
- `NOT RUN`과 이유를 누락함
- 실패 로그나 영향을 축소함
- API 변경인데 실제 요청·응답 증거가 없음
- 계약, ADR 또는 운영 문서 반영을 확인하지 않음
- 적용되는 QA·완료 gate 없이 완료를 주장함

교정 결과는 관련 QA 게이트(gate)와 검토자 점검표(reviewer checklist)로 되돌아가며, 증거 없는 완료는 반려한다.

### 3.6 재사용 가능한 스킬(skill) 계약

`ai/skills/README.md`는 전체 AI 문서 색인이 아니다. 스킬(skill) 선택이나 위임이 활성화된 경우에만 읽는 스킬 카탈로그다.

다음 파일을 모두 만들고 입력, 출력, 선행 조건, 증거, 인계(handoff)와 실패 상태를 정의한다.

| 스킬 | 책임 | 초기 런타임(runtime) 상태 |
|---|---|---|
| `repo-intake.md` | 저장소 진입과 범위 파악 | `CONFIGURED` 가능한 문서 기반 절차 |
| `command-runner.md` | 레지스트리(registry) 기반 명령 실행 | `NOT CONFIGURED` |
| `verification-runner.md` | 적용 가능한 검증 게이트(gate) 실행 | `NOT CONFIGURED` |
| `api-smoke-verifier.md` | 실제 API 요청/응답(request/response) 검증 | `NOT CONFIGURED` |
| `failure-triage.md` | 실패 분류, 재현, 영향과 다음 조치 | 문서 기반 계약 `ACTIVE` |
| `docs-sync.md` | 기준 문서 영향과 링크 일관성 검증 | 일부 `ACTIVE`, 자동 검사는 `NOT CONFIGURED` |
| `review-gate.md` | 독립 검토와 완료 승인 | 문서 기반 계약 `ACTIVE` |

개별 스킬(skill) 문서는 라우팅 또는 배정(dispatch)에서 선택됐을 때만 읽는다. 존재한다는 이유로 일괄 로드하지 않는다.

## 4. 라우팅 동작

### 4.1 응답 모드(Answer Mode)

저장소 변경, 규범 결정, 검증 주장 없이 작은 질문에 답하는 경로다. 질문에서 직접 필요한 파일만 읽으며 프로젝트 전체 문서, 기능 명세(feature spec), 스킬 카탈로그(skill catalog)와 완료 문서를 기본으로 읽지 않는다.

### 4.2 경량 경로(Light Route)

좁은 구조 탐색, 최초 방향 파악, 비규범 문구 또는 색인 유지보수 경로다. 변경이 요구사항, 동작, 계약, 아키텍처, 보안 또는 완료 기준에 영향을 주면 작업 경로(Work Route)로 재라우팅한다.

### 4.3 작업 경로(Work Route)

구현 계획, 규범 문서, 기능 결정, 코드 변경, 검토, 검증 또는 완료 작업에 사용한다.

1. 소유 기능(feature)을 결정한다.
2. 필요한 경우 `specs/<feature>/spec.md`를 먼저 읽는다.
3. 주제별 기준 문서만 추가한다.
4. 계획, 구현, 검증, 완료 단계로 진입할 때 해당 단계 문서만 확장한다.
5. 범위가 바뀌면 계속하기 전에 재라우팅한다.

## 5. 후속 실행 게이트(gate) 구현 계약

AI 문서 작업이 완료되면 제품 구현보다 먼저 별도 실행 계획으로 다음을 만든다.

1. 실제 백엔드(backend)·프런트엔드(frontend) 명령 확인과 명령 레지스트리(command registry) 활성화
2. 명령 레지스트리(command registry)의 기계 판독 가능(machine-readable) 표현과 스키마(schema)
3. 검증 결과 스키마(schema)와 상태 매핑
4. 로컬 명령 실행기(command runner)와 검증 게이트(verification gate)
5. API 스모크 검증기(smoke verifier)
6. 실패 분류(triage) 아티팩트(artifact) 계약
7. CI 워크플로(workflow)와 필수 검사(required check) 연결
8. 클린 체크아웃(clean checkout) 실행 검증
9. 문서 계약과 실행 결과의 일치 검사

각 런타임 표면(runtime surface)은 다음 조건을 모두 충족해야 `CONFIGURED`로 전환한다.

- 실제 파일과 명령이 존재한다.
- 클린 체크아웃(clean checkout)에서 재현된다.
- 성공과 실패 픽스처(fixture)가 모두 검증된다.
- 출력이 문서화된 스키마(schema)를 만족한다.
- 시크릿(secret), 개인정보와 결제정보가 아티팩트(artifact)에 포함되지 않는다.
- CI 필수 검사(required check) 설정과 실제 워크플로(workflow) 결과가 일치한다.

## 6. 단일 원본과 중복 방지

| 정보 | 단일 원본 |
|---|---|
| 제품 범위와 요구사항 | `docs/01`, `docs/05`, 기능 명세(feature spec) |
| 상세 비즈니스 정책 | `docs/service-policies/` |
| 아키텍처와 기술 결정 | `docs/06`, ADR |
| 데이터·API 계약 | `docs/07`, feature spec |
| 품질과 운영 기준 | `docs/09` |
| AI 문서 선택 | `ai/document-routing.md` |
| AI 단계(phase)와 맥락(context) | `ai/context-map.md` |
| 실행 명령 계약 | `ai/command-registry.md`와 후속 기계 레지스트리(machine registry) |
| 작업 범위와 담당자 | GitHub 이슈(Issue) |
| 수명 주기(lifecycle) 상태 | GitHub Project `Status` |
| 실행 결과와 검증 증거 | PR·CI 아티팩트(artifact) |

`agent.rules`, 가드레일(guardrail), QA 문서는 이 내용을 복제하지 않고 해당 소유자를 링크하고 적용 시점과 실패 의미만 정의한다.

## 7. 제외 사항

다음은 문서 단계에서 구현하지 않는다.

- 실제 제품 백엔드(backend)·프런트엔드(frontend) 기능
- 존재하지 않는 Gradle 또는 패키지(package) 명령의 추측
- 성공 실행이 없는 CI를 활성(active) 또는 필수(required)로 표시
- 생성된 프로젝트 상태(project state), 체크섬(checksum), 캐시 적중(cache hit)과 런타임 증명(runtime attestation)의 허위 예시
- 이슈(Issue)·Project·PR 상태를 복제하는 영구 작업 로그(work log)
- 모든 AI 문서를 한 번에 읽으라는 전역 지시

## 8. 문서 단계 완료 기준

문서 단계는 다음 조건을 모두 충족해야 완료된다.

- `AGENTS.md`가 `ai/document-routing.md`만 초기 AI 진입점으로 가리킨다.
- `ai/README.md`가 존재하지 않는다.
- `document-routing.md`가 아홉 개 구조 영역과 조건부 읽기 규칙을 모두 가진다.
- 모든 라우팅 대상 파일이 존재하며 `ACTIVE` 또는 `TEMPLATE` 상태가 명시된다.
- 미구성 런타임(runtime) 항목에는 활성화 조건, 필수 입력, 예상 출력과 금지 주장이 있다.
- `lazycodex-runbook.md`의 경고 신호가 라우팅(routing), QA, 검토자(reviewer)와 연결된다.
- 스킬(skill) 7개의 입력, 출력, 증거, 인계(handoff)와 실패 의미가 정의된다.
- QA·CI·완료 문서가 `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`, `NOT CONFIGURED`, `NOT APPLICABLE`을 일관되게 사용한다.
- 문서 링크와 단일 원본 규칙이 검증된다.
- 실행 gate 구현을 위한 별도 후속 계획의 입력이 완성된다.

## 9. 기존 재구성 계획에 미치는 영향

기존 저장소·아키텍처 재구성 계획은 다음과 같이 수정한다.

- `ai/README.md` 생성과 전체 색인 검증을 제거한다.
- `AGENTS.md -> ai/document-routing.md` 진입 경로로 변경한다.
- `document-routing.md`의 전체 구조 보존을 명시한다.
- 이 설계에 정의된 AI 문서와 skill 파일을 문서 작업 범위에 추가한다.
- 런타임(runtime) 의존 값은 템플릿 메타데이터로 남기되 문서 계약은 완성하도록 한다.
- 문서 재구성 완료 다음 단계에 `AI workflow executable gates` 후속 계획을 추가한다.
- 실행 게이트(gate) 검증이 끝난 뒤 제품 구현을 시작하도록 순서를 변경한다.
