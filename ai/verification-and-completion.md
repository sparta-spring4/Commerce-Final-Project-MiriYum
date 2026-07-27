# 검증 및 완료

계약 상태: ACTIVE

## 결과 어휘(vocabulary)

허용 결과: PASS | FAIL | BLOCKED | NOT RUN | NOT CONFIGURED | NOT APPLICABLE

- `PASS`: 명시된 명령 또는 검토가 명시된 범위에서 실행되었고 예상 결과를 충족했다.
- `FAIL`: 명령 또는 검토가 실행되었지만 예상 결과를 충족하지 못했다.
- `BLOCKED`: 명명된 의존성, 권한, 결정 또는 환경 조건 때문에 검증을 진행할 수 없다.
- `NOT RUN`: 적용 가능한 검사를 실행하지 않았다. 이유와 그로 인한 위험을 기록한다.
- `NOT CONFIGURED`: 필요한 실행 surface, 명령, 환경 또는 workflow가 존재하지 않거나 검증되지 않았다.
- `NOT APPLICABLE`: 검사가 변경 사항에 적용되지 않는다. 범위에 따른 이유를 기록한다.

관찰된 결과만 사용한다. `TEMPLATE`, 계획, 문서 계약, 파일 이름 또는 예상 출력으로는 `PASS`를 만들 수 없다.

## 변경 유형별 검증 수준

| 변경 유형 | 최소 검증 |
|---|---|
| 텍스트 또는 링크만 변경 | 대상 콘텐츠 계약, 링크/경로 소유권 검토, 형식 검사 및 변경 내역(diff) 검토 |
| 제품, 정책, 기능 또는 API 계약 | 정본 소유자 검토, 인수 조건 추적, 문서 간 일관성 및 적용 가능한 계약 검증 |
| 실행 가능한 구현 | 집중된 자동화 테스트, 영향받는 더 넓은 테스트 모음, 정적/build 검사 및 적용 가능한 runtime 동작 |
| 데이터 또는 migration | 스키마 및 migration 검증, 호환성 및 rollback 검토, 데이터 무결성 증거 |
| 보안, 인증 또는 외부 부작용 | 부정 경로 테스트, 권한 및 secret 검토, 안전한 환경 증거 및 명시적 위험 검토 |
| cross-end 동작 | 엔드포인트 증거와 `ai/integration-contracts.md`에 따른 조합된 계약 또는 사용자 흐름 증거 |

가장 좁은 관련 검사를 먼저 실행한 뒤, 영향에 따라 정당화되는 더 넓은 gate를 실행한다. 누락된 runtime은 `NOT CONFIGURED`로 유지한다. 문서 전용 작업을 위해 제품 테스트를 만들어 내지 않는다.

## QA

QA는 사용자가 관찰할 수 있는 인수 동작과 의미 있는 실패 경로의 평가를 소유한다. 환경, 입력, 명령 또는 작업, 관찰 결과, 결함 및 증거를 기록한다. QA는 구현 테스트, 정본 계약 검토 또는 CI를 대체하지 않는다.

반복 가능한 QA 작업에 독립적으로 검증된 workflow나 소유자가 생기기 전까지 이 섹션이 정본 QA 계약이다. 별도의 QA 문서는 연기한다.

## CI

CI는 실제 실행의 결과와 로그를 소유한다. workflow는 존재하고 깨끗한 checkout에서 필수 gate를 재현한 후에만 활성 상태다. 저장소 설정도 검증된 후에만 검사가 필수 상태다. 구성 텍스트, 로컬 실행 또는 계획된 workflow는 활성/필수 CI를 주장하기에 충분하지 않다.

CI 상태는 `NOT CONFIGURED`이다. 로컬 엔드포인트 검증과 루트 명령 조합으로는 CI가 활성화되지 않는다. Pull Request는 로그를 영구 작업 로그에 복사하는 대신 CI 증거로 링크한다.

## 검토자(reviewer)

검토자(reviewer)는 변경 내역(diff)을 Issue 범위 및 각 인수 조건과 대응시키고, 소유권과 의존성 경계를 검사하며, 보안, 데이터, migration 및 rollback 위험을 살피고, 검증이 변경 유형에 맞는지 평가한다. 검토는 근거 없는 결과 레이블(label)에 이의를 제기하고 관련 없는 사용자 변경이 포함되지 않았음을 확인해야 한다.

승인은 검토 증거이지 적용 가능한 실행 gate를 대체하지 않는다.

## Issue 완료

Issue는 인수 조건이 해결되고, 담당자와 위임된 출력이 대조되며, 필요한 정본 문서가 갱신되고, 적용 가능한 검증이 허용된 결과 어휘(vocabulary)로 표현되며, 미해결 위험이 명시된 경우에만 완료될 수 있다. GitHub Project의 단일 `Status` field는 유일한 수명 주기(lifecycle) 상태로 유지하며 저장소 복제본을 만들지 않는다.

## 완료 주장

모든 완료 주장에는 다음 네 가지 필드가 포함되어야 한다.

- 명령: 실제로 수행한 정확한 명령 또는 검토
- 결과: 각 명령 또는 검토에 대한 하나의 허용 결과
- 위험: 남은 불확실성, 건너뛴 범위, rollback 또는 후속 작업 위험
- 증거: 결과를 뒷받침하는 출력 요약, CI/PR 링크, artifact 또는 검토 참조

필드가 하나라도 없거나, `PASS`에 실행 증거가 없거나, 검증되지 않은 runtime에 의존하는 완료 주장은 거부한다. 해당 실제 파일, 설정, 실행 및 성공 증거 없이 검사를 구성됨으로, CI를 활성/필수로 또는 작업을 완료됨으로 설명하지 않는다.

## 실패 수정 및 gate 재진입

검증에 실패하거나 완료 주장에 증거가 부족한 경우 다음을 수행한다.

1. 관찰된 결과를 정확하게 label한다.
2. 실패한 gate와 근본 조건을 식별한다.
3. 수정을 승인된 범위로 제한한다.
4. 수정 후 원래 필수 gate를 다시 실행한다.
5. 이전 위험을 지우지 않고 새 결과와 증거를 보존한다.

수정 경로는 원래 QA, CI, 검토자(reviewer) 또는 인수 gate를 우회하지 않는다. 반복적인 검증 회피 또는 증거 없는 완료 주장은 아래 행렬(matrix)을 통해서만 별도의 수정 runbook을 활성화할 수 있다.

## 연기된 활성화 행렬(matrix)

| 연기된 책임 | 초기 상태 | 활성화 조건(trigger) 및 필수 경계 |
|---|---|---|
| 기계 판독 가능한 프로젝트 상태, 기계 레지스트리 및 JSON schema | `NOT CONFIGURED` | 실제 엔드포인트 명령과 출력 형식이 안정적이고 검증됨. schema는 실제 fixture를 검증해야 함 |
| command runner | `NOT CONFIGURED` | 반복 명령, 실패 fixture, 종료 동작 및 증거 형식이 검증됨 |
| verification runner | `NOT CONFIGURED` | 여러 gate에 안정적인 입력, 순서, 실패 동작 및 재현 가능한 출력이 있음 |
| API smoke verifier | `NOT CONFIGURED` | 실제 API scaffold, 환경 계약, 안전한 데이터, 성공 사례 및 실패 사례가 검증됨 |
| failure triage artifact | `NOT CONFIGURED` | 반복된 실패가 안정적인 분류와 실행 가능한 다음 소유자 인계(handoff) 형식을 보여 줌 |
| 별도 QA, CI, reviewer, Issue 완료 및 완료 주장 문서 | `NOT CONFIGURED` | 독립 작업 흐름(workflow) 또는 소유자가 입증되어 이 섹션만으로 충분하지 않음 |
| `lazycodex-runbook.md` | `NOT CONFIGURED` | 검증 회피 또는 증거 없는 완료 주장이 반복되어 독립적인 수정 절차가 필요함 |
| Workflow cache 및 cache 정책 | `NOT CONFIGURED` | 반복적인 탐색 비용과 오래된 context 실패가 측정되고 무효화 증거가 있음 |
| Native runtime adapter 및 provenance | `NOT CONFIGURED` | 원격 또는 CI 실행이 호스트 신뢰와 지속적인 provenance 요구사항을 보임 |
| CI 작업 흐름(workflow) 및 required check | `NOT CONFIGURED` | 로컬 명령과 gate가 깨끗한 checkout에서 재현된 후 저장소 required-check 설정이 검증됨 |
| 선택적 로컬 Git hook | `NOT APPLICABLE` | 검증된 공통 script가 있고 반복된 로컬 실수가 관찰됨. 모든 hook은 얇은 선택적 wrapper이며 CI를 대체하지 않음 |

### 연기된 skill 계약

7개 skill 계약과 연기된 `ai/skills/README.md` 선택 catalog의 초기 상태는 모두 `NOT CONFIGURED`이다. skill 파일은 Phase 1에서 만들지 않는다. 반복 workflow가 입력, 출력, 실패 동작 및 handoff를 검증한 후에만 개별 skill을 생성하고 활성화한다. 검증된 skill 계약이 존재하고 선택, 소유권 및 활성화 규칙 자체가 검증된 후에만 선택 catalog를 생성하고 활성화한다. 연기된 이름을 나열하기 위해 catalog를 만들지 않는다.

- `repo-intake.md`: 반복된 저장소 intake에 안정적이고 제한된 context와 소유권 결과가 필요하다.
- `command-runner.md`: 반복 명령 실행에 검증된 호출 및 증거 형식이 있다.
- `verification-runner.md`: 반복 gate 조정에 검증된 순서와 실패 동작이 있다.
- `api-smoke-verifier.md`: 실제 엔드포인트가 반복 가능한 안전한 smoke 검사와 부정 사례를 지원한다.
- `failure-triage.md`: 반복되는 실패에 입증된 분류와 다음 소유자 handoff가 있다.
- `docs-sync.md`: 반복되는 정본 문서 drift에 검증 가능한 조정 workflow가 있다.
- `review-gate.md`: 반복되는 검토 입력과 출력을 Pull Request 복제 없이 강제할 수 있다.

## 엔드포인트 scaffold 및 이후 경계

backend와 frontend scaffold, 로컬 AI 계약, wrapper/package script 및 엔드포인트 실행 증거는 이제 존재한다. 해당 `CONFIGURED` command ID는 `ai/command-registry.md`를 통해 조합할 수 있다. 루트 조합은 위임과 증거 집계일 뿐 runner, CI, API smoke 또는 cross-end 통합 runtime을 활성화하지 않는다.

runner, schema, CI, skill, `lazycodex-runbook.md`, cache 또는 adapter 작업은 해당 반복 workflow나 실패 증거가 matrix를 충족한 후에만 별도 계획으로 옮긴다. 엔드포인트 scaffold 작업은 이러한 artifact를 만들지 않는다.

필수 Git hook은 금지한다. 공통 script가 검증되고 반복되는 로컬 실수가 문서화된 후에만 선택적인 얇은 wrapper를 고려한다. 이는 정책을 소유하거나 CI를 대체해서는 안 된다.
