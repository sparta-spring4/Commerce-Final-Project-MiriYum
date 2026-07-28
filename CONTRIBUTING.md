# MiriYum 기여 가이드

이 작업 흐름(workflow)은 사람과 AI 협업자에게 적용된다. 범위, 수명 주기(lifecycle), 구현 증거 및 정본 문서의 소유자를 각각 분리한다.

## Issue로 시작하기

질문·조사처럼 저장소를 변경하지 않는 작업과 현재 진행 중인 1~5단계 문서 정비를 제외한 코드·문서·설정·테스트 변경은 구현 전에 GitHub Issue를 생성하거나 선택한다. Issue는 다음을 소유한다.

- 의도한 결과와 정확한 포함·제외 경로
- 현재 담당자와 위임된 작업
- 인수 조건
- 검증 계획과 알려진 위험

Issue 제목은 `[Auth]`, `[Store]`, `[Reservation]`, `[MenuHold]`, `[Pickup]`, `[Global]`, `[Docs]` 중 하나의 도메인 접두어로 시작한다. 사소한 문구 변경도 저장소 변경이면 원칙적으로 예외가 아니며, 선행 Issue가 실용적이지 않았다면 Issue의 `작은 변경 예외`에 이유를 남긴다.

둘 이상의 도메인 계약이 필요한 작업은 Issue에 `contract-first`, `blocks`, `blocked by` 관계를 기록한다. 소유자는 동작하는 최소 공개 Service 메서드·DTO·오류·테스트 계약을 먼저 제공하고 선행 PR을 `dev`에 병합한다. 소비자는 최신 `dev`를 반영한 뒤 그 계약을 사용한다. 준비되지 않은 계약은 `return null`, 가짜 성공 응답, 빈 구현 또는 `UnsupportedOperationException`으로 대신하지 않고 `BLOCKED`로 보고한다.

편집 전에 정확한 변경 경로를 확정한다. 탐색 패턴은 경로 식별에 도움이 될 수 있지만, Issue·사용자의 현재 작업 요청과 활성 정본에서 최종 파일 목록을 확정해야 한다. 무시되는 작업 artifact를 기본 라우팅이나 제품 사실의 근거로 읽지 않는다.

## 수명 주기(lifecycle)는 한 곳에서만 추적하기

백로그부터 완료까지 GitHub Project의 단일 `Status` field만 수명 주기(lifecycle) 상태로 사용한다. 저장소 작업 로그, 체크리스트 또는 AI 문서에 이 상태를 복제하지 않는다.

Issue는 작업 계약을 설명할 뿐 구현이나 검증을 증명하지 않는다. 인수 조건과 완료 증거를 검토한 후에만 완료 수명 주기(lifecycle) 상태로 변경한다.

## Pull Request 제출하기

Pull Request는 변경 요약과 구현 과정에서 생성된 증거를 소유한다. 다음을 기록한다.

- 인수 조건과 변경의 대응 관계
- 실제로 실행한 명령과 관찰한 결과
- 실행하지 않은 테스트 또는 검사와 그 이유
- 위험, rollback 고려 사항 및 문서 영향
- CI 증거 링크와 요청하는 검토자(reviewer) 중점 검토 사항

### 브랜치와 병합

- 유일한 기본·통합 개발 브랜치는 `dev`다. `develop`은 사용하지 않는다.
- `main`은 배포·최종 제출 브랜치이며 일반 작업의 PR 대상이 아니다.
- 작업 브랜치는 최신 `dev`에서 만들고 `feature/{issue}-{slug}`, `fix/{issue}-{slug}`, `docs/{issue}-{slug}`, `chore/{issue}-{slug}` 형식을 사용한다.
- 한 브랜치는 하나의 주 Issue를 중심으로 유지한다. 하루가 지났다는 이유만으로 브랜치나 PR을 나누거나 폐기하지 않는다.
- 작업 브랜치에서 `dev`로는 squash merge하고, `dev`에서 `main`으로는 merge commit을 사용한다.
- 병합 뒤 작업 브랜치는 삭제한다. `dev` 직접 push·force push·삭제는 금지한다.

### 커밋과 PR 제목

커밋과 PR 제목은 `<type>(<scope>): <한글 요약>` 형식을 사용한다.

- type: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `ci`
- 선택 scope: `auth`, `store`, `reservation`, `menu-hold`, `pickup`, `global`, `frontend`, `api`, `docs`
- `update`, `add`, `bugfix`, `gitfix`, `script` 같은 임의 분류와 emoji 접두어는 사용하지 않는다.

### 검토와 승인

- PR 작성자를 제외한 2명 이상의 승인이 필요하며 요구사항 담당자의 검토 참여를 허용한다.
- 변경한 도메인의 소유자 확인은 다른 승인 인원의 검토와 별개다.
- 소유자 확인 없이 다른 도메인의 Entity·Repository·공개 Service 메서드·DTO·오류·API 계약을 변경하지 않는다.
- 리뷰어의 담당 도메인을 제한하지 않지만, 모르는 계약을 추측해 승인하지 않는다.
- 충돌을 해결한 뒤 적용 가능한 검증을 다시 실행한다.
- PR 분리는 독립 인수 조건, 도메인 경계, 위험 격리와 rollback 가능성을 기준으로 한다.

CI는 자체 실행 출력을 소유한다. 이를 영구 저장소 로그에 옮겨 적지 않는다. 구성된 workflow나 계획된 검사는 깨끗한 checkout이 통과했거나 저장소 검사가 필수라는 증거가 아니다.

현재 CI와 required check는 `NOT CONFIGURED`다. 존재하지 않는 check를 통과했다고 표시하거나 브랜치 보호의 required check로 등록하지 않는다.

## 위임과 인계(handoff)

현재 작업 담당자는 통합, 검증 및 최종 완료 주장에 대한 책임을 유지한다. delegate에게는 명시적으로 제한된 범위, 필수 입력, 예상 출력 및 증거 형식을 제공한다. 반환된 작업을 통합하기 전에 Issue 및 현재 저장소 상태와 대조한다.

임시 위임 artifact나 경로 목록이 필요하면 무시되는 `.superpowers/sdd/` 경로 아래에만 둔다. 저장소에 영구 work-log 또는 수명 주기(lifecycle) 복제본을 만들지 않는다.

## 문서 소유권

동작이나 지속적인 결정이 바뀌면 정본 제품, 정책, 아키텍처, 기능 또는 품질 문서를 갱신한다. 해당 규칙을 Issue, Pull Request 또는 AI 작업 흐름(workflow) 문서에 복사하지 말고 소유자 문서로 링크한다.

`README.md`, 이 문서와 `ai/`는 사람·AI의 진입, 라우팅, 명령·증거 경계를 소유하며 제품 사실을 소유하지 않는다. 제품 사실의 활성 정본 allowlist는 `docs/00-index.md`부터 `docs/09-quality-operations-and-rules.md`, 현재 승인 기준과 정렬된 서비스 정책, 현재 유효 ADR, `docs/05` 또는 소유 정책이 연결한 활성 `docs/specs/<feature>/spec.md`로 제한한다. 기능 명세 템플릿, `docs/superpowers/specs/`, `docs/superpowers/plans/`, `miriyum-service-blueprint.md`, `miriyum-service-decisions.md`, `.superpowers/sdd/`의 artifact와 상태가 `Superseded` 또는 `Deprecated`인 ADR은 활성 정본이나 ADR이 링크해도 기본 라우팅에서 절대 제외한다. 사용자가 과거 감사·결정 이력 검토를 명시적으로 요구한 경우에만 읽고 현재 결정·완료 증거로 사용하지 않는다.

## 단계 표기

모든 기능, 정책과 기술 결정은 정책 결정 상태와 별도로 다음 네 단계 가운데 적용 단계를 기록한다.

- `1차 MVP`: 첫 사용자·매장 흐름과 그 정확성 검증에 필요한 최소 범위
- `2차 MVP`: 1차 증거를 바탕으로 다음 제품 범위에서 활성화할 기능
- `고도화`: 이미 승인된 필수 구현 단계. 이 단계에 진입하면 해당 단계로 확정된 기능을 모두 구현·활성화하고 인수 조건을 검증한다.
- `향후 고도화`: 현재 활성화하지 않으며 시간과 근거가 남을 때 별도 승인해 검토하는 장기 후보 범위

단계가 뒤라는 이유로 정책 상태를 `TODO`로 바꾸지 않고, 정책이 `확정`이라는 이유로 현재 단계에 선도입하지 않는다. 다만 `고도화` 진입 뒤 승인 기능을 시간 여유나 구현자 선택으로 생략할 수 없다. 공급자 정확 버전·수치·토폴로지 같은 결정 게이트는 미정일 수 있지만 해당 기능을 선택 후보로 되돌리지 않으며, 기능 활성화 전에 책임자와 검증 근거로 확정한다. 단계 변경은 소유 제품·정책 문서와 관련 ADR을 함께 검토하고 적용 근거와 검증 방법을 기록한다.

## Pull Request 문서 점검

제품·정책·아키텍처·품질에 영향을 주는 Pull Request는 다음을 확인한다.

- [ ] 변경한 규칙의 소유 정본이 활성 정본 allowlist 안에 있다.
- [ ] 기능 또는 정책의 적용 단계를 `1차 MVP`, `2차 MVP`, `고도화`, `향후 고도화` 중 하나로 표시했다.
- [ ] 정책 상태와 단계 포함 여부를 서로 독립적으로 검토했다.
- [ ] 관련 서비스 정책과 현재 유효 ADR을 함께 검토하고 필요한 날짜별 개정을 반영했다.
- [ ] 기능 명세는 `docs/05` 또는 소유 정책이 연결한 실제 `docs/specs/<feature>/spec.md`이며 템플릿이 아니다.
- [ ] 실제 실행한 명령, 관찰 결과, 실행하지 않은 검사와 이유, 남은 위험을 PR 증거에 기록했다.
- [ ] `docs/superpowers/specs/`, `docs/superpowers/plans/`, `miriyum-service-blueprint.md`, `miriyum-service-decisions.md`, `.superpowers/sdd/`와 상태가 `Superseded` 또는 `Deprecated`인 ADR을 링크만으로 읽거나 현재 제품 결정·완료 증거로 사용하지 않았다.
- [ ] `고도화` 진입 시 승인된 필수 기능을 모두 구현·검증하며, 미정 공급자 버전·수치·토폴로지 결정 게이트를 기능 생략 근거로 사용하지 않았다.
