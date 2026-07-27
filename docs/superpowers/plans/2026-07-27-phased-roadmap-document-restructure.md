# 단계별 로드맵 정본 재구성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 활성 정본을 1차 MVP·2차 MVP·고도화·향후 고도화의 네 단계와 승인된 불변식에 맞춰 재구성한다.

**Architecture:** 기존 번호 문서·서비스 정책·ADR의 소유 구조를 유지하고 단계 포함 여부를 별도 축으로 추가한다. Task 1~4는 파일이 겹치지 않아 병렬 실행할 수 있고, Task 5는 모든 결과가 모인 뒤 동일 파일군의 교차 불일치를 수정하는 순차 통합 gate다.

**Tech Stack:** Markdown, Git, `rg`, PowerShell, 기존 문서 링크와 ADR 이력

## Global Constraints

- 모든 본문과 커밋 메시지는 한글로 작성한다. 기술 식별자는 원문을 유지할 수 있다.
- 주체명은 항상 `매장 운영자` 또는 `플랫폼 운영자`로 명시한다. 사용자 행위는 `예약`으로 통일하고 승인되지 않은 유형 수식어를 사용하지 않는다.
- 모든 범위는 `1차 MVP`, `2차 MVP`, `고도화`, `향후 고도화` 네 단계 중 하나로 분류한다. 고도화는 필수, 향후 고도화는 시간이 남을 때만 진입한다.
- 일반 사용자·매장 운영자·플랫폼 운영자는 전용 계정 테이블·PK·principal·토큰 namespace를 사용하며 공통 `USERS`+role로 합치지 않는다.
- 방식 A를 유지한다. 하나의 Spring Boot와 MySQL 안에 현재 단계의 실제 모듈만 두고, 독립 배포·확장·장애 격리의 실제 증거 전에는 분리하지 않는다.
- 같은 주제의 기술 변경은 기존 ADR에 날짜별 개정으로 이어 쓰고 최초 결정·거절 이유·단점을 삭제하지 않는다. 새로운 주제만 새 ADR 대상이지만 이번 작업에서는 새 ADR을 만들지 않는다.
- `docs/superpowers/specs/`, `docs/superpowers/plans/`, `miriyum-service-blueprint.md`, `miriyum-service-decisions.md`와 비활성 ADR은 사용자가 과거 검토를 명시하지 않는 한 읽거나 기본 라우팅에 넣지 않는다.
- 정확한 버전·토큰 수명·제한 시간·횟수·보관 기간·임계치를 추측하지 않는다. 결정 주체·시점·검증 근거만 기록한다.
- 작업 시작 전 `git status --short`와 대상 파일 diff를 확인한다. 사용자 기존 변경을 보존하고 덮어쓰기·되돌리기·광범위 포맷팅을 하지 않는다.
- 파일 편집은 `apply_patch`만 사용한다. 미정 표식, 빈 섹션과 구현되지 않은 기능을 현재 제공하는 표현을 남기지 않는다.
- 이 계획과 승인 spec은 제품 정본이 아니다. 실행 결과는 활성 정본에 반영하고 계획·spec 경로는 기본 제품 라우팅에서 제외한다.

---

### Task 1: 거버넌스와 문서 라우팅

**Files:**
- Modify: `README.md`
- Modify: `CONTRIBUTING.md`
- Modify: `ai/document-routing.md`
- Modify: `docs/00-index.md`
- Modify: `docs/adr/ADR-000-template.md`
- Modify: `docs/service-policies/README.md`
- Modify: `docs/service-policies/00-policy-template.md`

**Produces:** 현재 승인 기준의 활성 정본 allowlist, 네 단계 표기 규칙, ADR 개정 양식과 사람·AI 작업 진입 경로.

- [ ] **Step 1: 변경 전 상태 확인**

Run: `git status --short; git diff -- README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md docs/adr/ADR-000-template.md docs/service-policies/README.md docs/service-policies/00-policy-template.md`

- [ ] **Step 2: 활성 정본 라우팅 정렬**

`README.md`, `docs/00-index.md`, `ai/document-routing.md`에 다음 allowlist를 명시한다: `docs/00-index.md`~`docs/09-quality-operations-and-rules.md`, 현재 승인 기준과 정렬된 `docs/service-policies/`, 현재 유효 ADR, `docs/05` 또는 소유 정책이 연결한 `docs/specs/<feature>/spec.md`. 기능 명세 템플릿은 정본이 아니며 `README.md`, `CONTRIBUTING.md`, `ai/`는 제품 사실이 아니라 진입·라우팅을 소유한다고 구분한다. 과거 문서와 이 계획·spec은 기본 라우팅에서 제외한다.

- [ ] **Step 3: 기여·정책·ADR 형식 정렬**

`CONTRIBUTING.md`에 단계 표기, 정본 소유권, 관련 정책·ADR 동시 검토, 증거 기록과 과거 문서 제외를 PR 점검 항목으로 추가한다. 서비스 정책 색인·템플릿에는 정책 상태와 단계 포함 여부를 분리하고 네 단계, 소유 문서, 관측 가능한 인수 조건을 기록하게 한다. ADR 템플릿에는 문제·단계 제약·대안·선택 이유·거절 이유·단점·관측 신호·단순 개선·다음 후보·검증·마이그레이션·새 단점과 날짜별 개정 이력을 넣는다.

- [ ] **Step 4: 문서 전용 검증**

Run: `rg -n "1차 MVP|2차 MVP|고도화|향후 고도화|allowlist|날짜별 개정" README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md docs/adr/ADR-000-template.md docs/service-policies/README.md docs/service-policies/00-policy-template.md`

Run: `git diff --check -- README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md docs/adr/ADR-000-template.md docs/service-policies/README.md docs/service-policies/00-policy-template.md`

- [ ] **Step 5: 커밋**

```powershell
git add README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md docs/adr/ADR-000-template.md docs/service-policies/README.md docs/service-policies/00-policy-template.md
git commit -m "docs: 단계별 정본 라우팅 기준 정립"
```

### Task 2: 제품·사용자·도메인 정렬

**Files:**
- Modify: `docs/01-product-vision.md`
- Modify: `docs/02-users-and-permissions.md`
- Modify: `docs/03-domain-model.md`
- Modify: `docs/service-policies/01-member-auth.md`
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/service-policies/03-store-operation.md`
- Modify: `docs/service-policies/04-reservation.md`
- Modify: `docs/service-policies/06-menu-hold.md`

**Produces:** 단계별 제품 범위, 계정 물리 분리, 매장 관계·운영 모드·예약 자원·카테고리의 단일 의미.

- [ ] **Step 1: 변경 전 상태 확인**

Run: `git status --short; git diff -- docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/service-policies/01-member-auth.md docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md docs/service-policies/04-reservation.md docs/service-policies/06-menu-hold.md`

- [ ] **Step 2: 제품·권한 경계 수정**

`docs/01`에 네 단계의 가치·비목표를, `docs/02`와 인증·입점 정책에 세 계정의 테이블·PK·principal·namespace 분리와 매장 운영자-매장 소속 검증을 반영한다. 1차는 저장소 없는 Access/Refresh JWT, 고도화는 Valkey 회전·폐기·재사용 탐지와 일반 사용자 카카오 로그인으로 구분한다. 이메일 일치나 외부 로그인으로 유형·권한을 바꾸지 않는다.

- [ ] **Step 3: 도메인·운영 불변식 수정**

`docs/03`은 방식 A나 모듈 기술을 소유하지 않고 세 계정 관계, 매장 운영자-매장 관계, 예약·메뉴 홀드·픽업과 카테고리 관계를 소유하게 한다. 매장은 주 카테고리 1개, 메뉴는 주 1개와 중복 없는 보조 0~5개다. 예약 자원은 날짜·시간대별 총 인원수·총 팀 수이며, `예약만`, `예약+메뉴 홀드`, 승인된 카페·베이커리의 `픽업 예약`과 모드 변경 후 기존 확정 건 보존을 관련 정책에 정렬한다.

- [ ] **Step 4: 문서 전용 검증**

Run: `$badSubject = '(?<!매장 )(?<!플랫폼 )' + '운영' + '자'; rg --pcre2 -n "$badSubject|USERS.+role" docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/service-policies/01-member-auth.md docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md docs/service-policies/04-reservation.md docs/service-policies/06-menu-hold.md`

Expected: 승인된 대안 설명의 `USERS`+role 외에 계정 통합을 현재 선택으로 표현한 결과가 없고, 주체명이 모두 한정되어 있다.

Run: `git diff --check -- docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/service-policies/01-member-auth.md docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md docs/service-policies/04-reservation.md docs/service-policies/06-menu-hold.md`

- [ ] **Step 5: 커밋**

```powershell
git add docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/service-policies/01-member-auth.md docs/service-policies/02-store-onboarding.md docs/service-policies/03-store-operation.md docs/service-policies/04-reservation.md docs/service-policies/06-menu-hold.md
git commit -m "docs: 제품 계정 예약 도메인 단계 정렬"
```

### Task 3: 흐름·요구사항·단계별 정책 정렬

**Files:**
- Modify: `docs/04-user-flows.md`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/service-policies/05-waiting.md`
- Modify: `docs/service-policies/07-course-tasting.md`
- Modify: `docs/service-policies/08-payment-refund.md`
- Modify: `docs/service-policies/09-checkin-noshow.md`
- Modify: `docs/service-policies/10-waitlist-transfer.md`
- Modify: `docs/service-policies/11-subscription.md`
- Modify: `docs/service-policies/12-review-trust.md`
- Modify: `docs/service-policies/13-ad-recommendation.md`
- Modify: `docs/service-policies/14-analytics-report.md`
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `docs/service-policies/16-notification.md`

**Produces:** 네 단계 기능 매트릭스와 각 기능의 시작·종료·예외·비진입 경계.

- [ ] **Step 1: 변경 전 상태 확인**

Run: `git status --short; git diff -- docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/05-waiting.md docs/service-policies/07-course-tasting.md docs/service-policies/08-payment-refund.md docs/service-policies/09-checkin-noshow.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/11-subscription.md docs/service-policies/12-review-trust.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md docs/service-policies/15-admin-operation.md docs/service-policies/16-notification.md`

- [ ] **Step 2: 흐름·요구사항 재분류**

`docs/04`의 흐름과 `docs/05`의 요구사항을 네 단계로 재분류한다. 1차는 기본 거래, 2차는 AI 없는 규칙 해석·이력 추천·품절 대안·원 매장 좌표 기준 3km, 고도화는 웨이팅·SSE·결제·환불·알림·체크인·플랫폼 운영자 기능, 향후 고도화는 코스·구독·리뷰·AI·채팅 등으로 표시한다. 정책 확정 상태와 단계 포함 여부를 별도 열로 둔다.

- [ ] **Step 3: 상세 정책 정렬**

웨이팅·결제·체크인·일반 취소 승계·통계·플랫폼 운영자·알림은 고도화에 둔다. 자동 승계는 일반 취소로 반환된 예약 가능분만 대상으로 하며 노쇼는 승계·반환 원장·일반 가용 재공개를 만들지 않는다. 코스·구독·리뷰는 향후 고도화다. 추천 정책은 2차의 `RuleInterpreter`, QueryDSL, 결정적 Java 점수화, 같은 매장 우선과 원 매장 좌표 기준을 소유하고, 향후 TOP3도 결정적 코드가 정하며 LLM은 설명만 생성하고 실패 시 템플릿을 사용하게 한다.

- [ ] **Step 4: 문서 전용 검증**

Run: `rg -n "1차 MVP|2차 MVP|고도화|향후 고도화|노쇼|일반 취소|RuleInterpreter|TOP3" docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/05-waiting.md docs/service-policies/07-course-tasting.md docs/service-policies/08-payment-refund.md docs/service-policies/09-checkin-noshow.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/11-subscription.md docs/service-policies/12-review-trust.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md docs/service-policies/15-admin-operation.md docs/service-policies/16-notification.md`

Run: `git diff --check -- docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/05-waiting.md docs/service-policies/07-course-tasting.md docs/service-policies/08-payment-refund.md docs/service-policies/09-checkin-noshow.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/11-subscription.md docs/service-policies/12-review-trust.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md docs/service-policies/15-admin-operation.md docs/service-policies/16-notification.md`

- [ ] **Step 5: 커밋**

```powershell
git add docs/04-user-flows.md docs/05-functional-requirements.md docs/service-policies/05-waiting.md docs/service-policies/07-course-tasting.md docs/service-policies/08-payment-refund.md docs/service-policies/09-checkin-noshow.md docs/service-policies/10-waitlist-transfer.md docs/service-policies/11-subscription.md docs/service-policies/12-review-trust.md docs/service-policies/13-ad-recommendation.md docs/service-policies/14-analytics-report.md docs/service-policies/15-admin-operation.md docs/service-policies/16-notification.md
git commit -m "docs: 흐름 요구사항 정책 단계 재분류"
```

### Task 4: 아키텍처·계약·UI·품질·ADR 정렬

**Files:**
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/07-data-and-api-contracts.md`
- Modify: `docs/08-ui-and-frontend-guidelines.md`
- Modify: `docs/09-quality-operations-and-rules.md`
- Modify: `docs/service-policies/17-privacy-security.md`
- Modify: `docs/service-policies/18-scale-reliability.md`
- Modify: `docs/adr/ADR-001-domain-packages-three-layer.md`
- Modify: `docs/adr/ADR-002-staged-technology-adoption.md`
- Modify: `docs/adr/ADR-003-aws-after-verification.md`
- Modify: `docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md`
- Modify: `docs/adr/ADR-005-portone-v2-payment-adapter.md`
- Modify: `docs/adr/ADR-006-jwt-valkey-refresh-token.md`
- Modify: `docs/adr/ADR-007-unified-search-mysql.md`

**Produces:** 방식 A와 단계별 실제 기술·계약·검증 기준, 기존 ADR의 연속 개정.

- [ ] **Step 1: 변경 전 상태 확인**

Run: `git status --short; git diff -- docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies/17-privacy-security.md docs/service-policies/18-scale-reliability.md docs/adr/ADR-001-domain-packages-three-layer.md docs/adr/ADR-002-staged-technology-adoption.md docs/adr/ADR-003-aws-after-verification.md docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md docs/adr/ADR-005-portone-v2-payment-adapter.md docs/adr/ADR-006-jwt-valkey-refresh-token.md docs/adr/ADR-007-unified-search-mysql.md`

- [ ] **Step 2: 아키텍처·계약·품질 수정**

`docs/06`은 방식 A와 단계별 실제 모듈을 소유한다. 1차는 JPA+좁은 SQL·무상태 JWT·Testcontainers MySQL, 2차는 QueryDSL·규칙 해석·동기 추천 API·카카오 좌표 포트, 고도화는 Valkey·S3·SSE·PortOne·기능별 MySQL 내구성 작업을 사용한다. Kafka·범용 Outbox·마이크로서비스·WebSocket·검색 클러스터는 증거 전까지 금지한다. `docs/07~09`와 개인정보·신뢰성 정책에는 계정 namespace, 멱등성, 수량 원장, 외부 어댑터, 실패 복구, UI 단계 노출, 문서·링크·형식 검증과 추측 수치 결정 gate를 정렬한다.

- [ ] **Step 3: ADR-001~007 연속 개정**

모든 ADR을 단계 축과 대조하되 실제 결정 변경이 있는 파일만 날짜별 개정을 추가한다. ADR-001은 방식 A, ADR-002는 점진 인프라·전환, ADR-004는 1차 Testcontainers 검증, ADR-006은 1차 무상태 JWT에서 고도화 Valkey로의 전환, ADR-007은 2차 규칙 기반 검색·MySQL·검색엔진 제외를 명시한다. ADR-003의 S3와 ADR-005의 PortOne은 고도화에 위치시킨다. 기존 본문·결정·단점을 삭제하지 않고 새 ADR 파일을 만들지 않는다.

- [ ] **Step 4: 문서 전용 검증**

Run: `rg -n "1차 MVP|2차 MVP|고도화|향후 고도화|방식 A|Testcontainers|Valkey|S3|PortOne|날짜별 개정" docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies/17-privacy-security.md docs/service-policies/18-scale-reliability.md docs/adr/ADR-00[1-7]-*.md`

Run: `git diff --check -- docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies/17-privacy-security.md docs/service-policies/18-scale-reliability.md docs/adr/ADR-001-domain-packages-three-layer.md docs/adr/ADR-002-staged-technology-adoption.md docs/adr/ADR-003-aws-after-verification.md docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md docs/adr/ADR-005-portone-v2-payment-adapter.md docs/adr/ADR-006-jwt-valkey-refresh-token.md docs/adr/ADR-007-unified-search-mysql.md`

- [ ] **Step 5: 커밋**

```powershell
git add docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies/17-privacy-security.md docs/service-policies/18-scale-reliability.md docs/adr/ADR-001-domain-packages-three-layer.md docs/adr/ADR-002-staged-technology-adoption.md docs/adr/ADR-003-aws-after-verification.md docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md docs/adr/ADR-005-portone-v2-payment-adapter.md docs/adr/ADR-006-jwt-valkey-refresh-token.md docs/adr/ADR-007-unified-search-mysql.md
git commit -m "docs: 아키텍처 계약 ADR 단계 정렬"
```

### Task 5: 전체 일관성 검수와 교차 수정

**Files:**
- Modify if required: Task 1~4의 모든 파일
- Do not create: 새 정본·ADR·정책 파일

**Consumes:** Task 1~4의 커밋과 승인 설계 spec.

**Produces:** 링크·용어·단계·소유권·ADR 이력이 서로 일치하는 최종 문서 집합.

- [ ] **Step 1: 전체 변경 범위와 충돌 확인**

Run: `git status --short; git log --oneline -5; git diff HEAD~4..HEAD --stat; git diff HEAD~4..HEAD --name-only`

Task 1~4에 지정되지 않은 추적 파일이 있으면 원인을 확인하고 사용자 기존 변경은 통합 diff에서 제외한다.

- [ ] **Step 2: 불변식 교차 검수와 최소 수정**

전체 diff에서 네 단계 누수, 계정 물리 분리, 방식 A, 세 운영 모드, 인원수·팀 수 예약 원장, 카테고리 모델, 원 매장 좌표 기준, 결정적 추천·TOP3, 일반 취소 승계와 노쇼 금지, 파일 소유권을 대조한다. `docs/03`에 아키텍처 소유가 남거나 `docs/06`에 도메인 관계 세부가 복제되지 않게 한다. 발견한 불일치는 소유 정본을 수정하고 참조 문서는 링크·요약만 남긴다.

- [ ] **Step 3: 금지어·과거 라우팅·형식 검사**

Run: `$badTerms = @(('(?<!매장 )(?<!플랫폼 )' + '운영' + '자'), ('온' + '라인 예약'), ('방' + '문 예약'), ('좌' + '석 예약'), ('\\bTO' + 'DO\\b'), ('\\bTB' + 'D\\b')) -join '|'; rg --pcre2 -n $badTerms README.md CONTRIBUTING.md ai docs/00-index.md docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies docs/adr`

Expected: 결과 없음. 과거 문서 경로는 제외 규칙을 설명하는 위치에서만 허용한다.

Run: `rg -n "docs/superpowers/specs|docs/superpowers/plans|miriyum-service-blueprint.md|miriyum-service-decisions.md" README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md`

Expected: 네 경로가 기본 제외 대상으로 명시된다.

- [ ] **Step 4: 링크·diff 검사**

Run: `rg -n "\[[^]]+\]\([^)]+\)" README.md CONTRIBUTING.md ai/document-routing.md docs/00-index.md docs/01-product-vision.md docs/02-users-and-permissions.md docs/03-domain-model.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/08-ui-and-frontend-guidelines.md docs/09-quality-operations-and-rules.md docs/service-policies docs/adr`

각 변경 링크의 상대 경로와 anchor 소유 문서를 확인하고 깨진 링크를 수정한다.

Run: `git diff --check HEAD~4..HEAD`

Run: `git status --short`

- [ ] **Step 5: 통합 커밋**

교차 수정이 있을 때만 관련 파일을 명시적으로 stage한다. `git add -A`는 사용하지 않는다.

```powershell
git add <교차 수정한 정확한 파일 경로>
git commit -m "docs: 단계별 정본 교차 일관성 보정"
```

교차 수정이 없으면 새 커밋을 만들지 않고 Task 1~4 검증 결과를 최종 증거로 남긴다.
