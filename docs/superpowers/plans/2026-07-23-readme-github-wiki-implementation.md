# MiriYum README 및 GitHub Wiki 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용하여 이 계획을 작업 단위로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 문법을 사용한다.

**목표:** 구현되지 않은 동작을 완료된 것처럼 제시하지 않고, 세 독자 그룹을 버전 관리되는 원본 문서로 안내하는 정확한 루트 README를 작성하고 12페이지 GitHub Wiki를 게시한다.

**아키텍처:** 루트 `README.md`는 간결하게 유지하고 메인 저장소의 정본 문서로 연결한다. GitHub Wiki는 공통 탐색 및 원본 링크와 함께 별도 Wiki 저장소의 임시 복제본에서 작성·커밋하며, 제품 정책과 기술 결정은 메인 저장소가 계속 소유한다.

**기술 스택:** Markdown, Git, GitHub Wiki, PowerShell 링크 검증 스크립트

## 전역 제약

- `docs/superpowers/specs/2026-07-23-readme-github-wiki-design.md`를 커밋 `5dbcd51`에서 승인된 설계로 사용한다.
- 프로젝트가 설계 및 정책 정리 단계에 있음을 명확히 명시한다.
- 설치, 실행, 테스트, 배포, CI 또는 구현 완료 주장을 꾸며내지 않는다.
- `docs/`, `tastelock-service-decisions.md`, 현재 서비스 문서를 정본으로 취급하며, Wiki는 설명을 위한 탐색 수단이다.
- 메인 저장소에 `wiki/` 디렉터리를 만들거나 자동 동기화를 구현하지 않는다.
- `docs/service-policies/01-member-auth.md`의 기존 사용자 변경을 수정하거나 스테이징하지 않는다.
- 기존 미추적 파일 `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`를 수정하거나 스테이징하지 않는다.
- 메인 저장소 README 커밋과 Wiki 저장소 게시 커밋을 분리한다.
- Wiki 접근 또는 푸시가 실패하면 로컬 Wiki 작업을 보존하고 게시를 주장하지 않은 채 실패를 보고한다.

---

### 작업 1: 작업 기준선을 기록하고 보호한다

**파일:**
- 생성: `docs/superpowers/plans/2026-07-23-readme-github-wiki-implementation.md`
- 보존: `docs/service-policies/01-member-auth.md`
- 보존: `docs/superpowers/plans/2026-07-22-miriyum-repository-architecture-restructure.md`

**인터페이스:**
- 입력: 승인된 설계 커밋 `5dbcd51` 및 현재 브랜치 `codex/miriyum-area3-mvp-docs`
- 출력: 명시적인 실행 체크리스트와 기록된 제외 사용자 변경 집합

- [ ] **단계 1: 브랜치와 기준선 변경을 확인한다**

  실행:

  ```powershell
  git status --short --branch
  git log -1 --oneline 5dbcd51
  ```

  예상: 현재 브랜치는 `codex/miriyum-area3-mvp-docs`이고, 커밋 `5dbcd51`을 확인할 수 있으며, 선언된 두 사용자 변경이 계속 보인다.

- [ ] **단계 2: 계획이 모든 승인된 산출물을 다루는지 검증한다**

  작업 2~5가 정본 원본 추출, 루트 README 생성, 12개 Wiki 페이지와 `_Sidebar.md`, `_Footer.md`, 링크 검증, 원격 게시, 게시 경로 검사, 제외 변경 검사를 포함하는지 확인한다.

- [ ] **단계 3: 구현 계획만 커밋한다**

  실행:

  ```powershell
  git add -- docs/superpowers/plans/2026-07-23-readme-github-wiki-implementation.md
  git diff --cached --name-only
  git commit -m "docs: plan README and GitHub Wiki implementation"
  ```

  예상: 스테이징된 목록에는 새 2026-07-23 계획만 있고, 기존 사용자 변경 둘 중 어느 것도 포함하지 않은 채 커밋이 성공한다.

### 작업 2: 두 표면에 필요한 정본 사실만 추출한다

**파일:**
- 읽기: `docs/service-definition.md`
- 읽기: `docs/technical-architecture.md`
- 읽기: `docs/service-policies/README.md`
- 읽기: `docs/service-policies/01-member-auth.md`
- 읽기: `docs/service-policies/02-store-onboarding.md`
- 읽기: `docs/service-policies/03-store-operation.md`
- 읽기: `docs/service-policies/04-reservation.md`
- 읽기: `docs/service-policies/05-waiting.md`
- 읽기: `docs/service-policies/06-menu-hold.md`
- 읽기: `docs/service-policies/07-course-tasting.md`
- 읽기: `docs/service-policies/08-payment-refund.md`
- 읽기: `docs/service-policies/09-checkin-noshow.md`
- 읽기: `docs/service-policies/10-waitlist-transfer.md`
- 읽기: `docs/service-policies/11-subscription.md`
- 읽기: `docs/service-policies/12-review-trust.md`
- 읽기: `docs/service-policies/13-ad-recommendation.md`
- 읽기: `docs/service-policies/14-analytics-report.md`
- 읽기: `docs/service-policies/15-admin-operation.md`
- 읽기: `docs/service-policies/16-notification.md`
- 읽기: `docs/service-policies/17-privacy-security.md`
- 읽기: `docs/service-policies/18-scale-reliability.md`
- 읽기: `tastelock-service-decisions.md`
- 읽기: `tastelock-service-blueprint.md`

**인터페이스:**
- 입력: 필수 정본 파일의 현재 작업 트리 내용
- 출력: 프로젝트 상태, MVP 경계, 사용자 여정, 도메인 지도, 아키텍처 방향, 정책 소유권, 용어, 역사적 맥락으로 그룹화한 사실

- [ ] **단계 1: 문서 개요와 원본 메타데이터를 UTF-8로 읽는다**

  각 필수 파일 이름과 Markdown 제목을 출력하는 PowerShell을 실행한 후, 그 제목으로 선택한 섹션을 전체 읽는다.

  예상: 필수 22개 파일을 모두 읽을 수 있고, 정책 파일 `01`부터 `18`까지가 존재하며, 누락되었거나 추론한 원본에서 가져온 사실이 없다.

- [ ] **단계 2: 초안 작성 전에 원본 우선순위를 정한다**

  문서가 다를 때마다 다음 순서를 적용한다. 현재 제품 동작에는 현재 서비스 정의와 정책 문서, 현재 기술 방향에는 기술 아키텍처, 변경 맥락에는 결정 이력, 명시적으로 역사적 맥락이 표기된 경우에만 블루프린트를 사용한다.

  예상: Wiki 또는 README의 어떤 문장도 역사 블루프린트 아이디어를 현재 정본 결정보다 우선하지 않는다.

- [ ] **단계 3: 안정적인 설명과 변경 가능한 정책 상태를 분리한다**

  Wiki에서는 안정적인 도메인 목적과 흐름 설명을 사용한다. 변경 가능한 상태, 우선순위, 수량, 시간 제한, 수수료, 출시 값은 정책 상태 표를 복사하는 대신 `docs/service-policies/README.md` 또는 소유 정책 파일에 연결한다.

  예상: Wiki는 두 번째 정책 레지스트리가 아니라 온보딩 및 탐색 계층으로 유지된다.

### 작업 3: 루트 README를 작성하고 커밋한다

**파일:**
- 생성: `README.md`

**인터페이스:**
- 입력: 작업 2에서 추출한 사실과 승인된 README 정보 아키텍처
- 출력: 검증된 상대 링크와 실제 Wiki URL을 갖춘 간결한 저장소 랜딩 페이지

- [ ] **단계 1: 승인된 섹션으로 README를 생성한다**

  추가:

  - 제목과 한 줄 정의
  - 눈에 띄는 `현재 상태: 설계·정책 정리 단계` 알림
  - 사용자/매장 문제와 의도한 해결 흐름
  - 첫 MVP 경계와 이후 후보를 명시적으로 구분한 핵심 경험
  - 완료된 구현이 아닌 방향으로 설명한 현재 기술 방향
  - 상대 링크가 있는 정본 문서 지도
  - 외부 방문자, 신규 개발자, 현재 팀원을 위한 경로
  - 근거 없는 명령 또는 완료 배지가 없는 단계별 로드맵
  - 실제 Wiki Home 링크

- [ ] **단계 2: 모든 로컬 Markdown 대상을 검증한다**

  `README.md`의 Markdown 링크를 파싱하고, `https://` 대상과 앵커는 무시하며, 로컬 경로를 URL 디코드한 뒤 모든 해석된 경로가 저장소 루트 아래에 존재하는지 검증한다.

  예상: 검증 도구가 누락된 로컬 대상 0개를 보고한다.

- [ ] **단계 3: 내용 제약과 공백을 점검한다**

  실행:

  ```powershell
  rg -n "설계·정책 정리 단계|평가자|신규 개발자|현재 팀원|github\.com/sparta-spring4/Commerce-Final-Project-MiriYum/wiki" README.md
  git diff --check -- README.md
  ```

  예상: 프로젝트 단계, 세 독자 경로 모두, Wiki URL이 존재하고, `git diff --check`가 성공적으로 종료하며, 꾸며낸 설치·실행·테스트·배포·성공 배지 섹션이 없다.

- [ ] **단계 4: README만 커밋한다**

  실행:

  ```powershell
  git add -- README.md
  git diff --cached --name-only
  git commit -m "docs: add MiriYum project README"
  ```

  예상: 스테이징된 목록에는 `README.md`만 있고, 기존 사용자 변경을 포함하지 않은 채 커밋이 성공한다.

### 작업 4: GitHub Wiki를 작성·검증·커밋·게시한다

**임시 Wiki 복제본의 파일:**
- 생성 또는 갱신: `Home.md`
- 생성 또는 갱신: `Project-Overview.md`
- 생성 또는 갱신: `Core-User-Journeys.md`
- 생성 또는 갱신: `Getting-Started-for-Developers.md`
- 생성 또는 갱신: `Architecture-Guide.md`
- 생성 또는 갱신: `Domain-Guide.md`
- 생성 또는 갱신: `Policy-Guide.md`
- 생성 또는 갱신: `Documentation-Map.md`
- 생성 또는 갱신: `Decision-and-Change-History.md`
- 생성 또는 갱신: `Team-Workflow.md`
- 생성 또는 갱신: `Glossary.md`
- 생성 또는 갱신: `FAQ.md`
- 생성 또는 갱신: `_Sidebar.md`
- 생성 또는 갱신: `_Footer.md`

**인터페이스:**
- 입력: 작업 2의 정본 사실, 저장소 기본 브랜치, 기존 Wiki 이력, 승인된 12페이지 구조
- 출력: `https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum.wiki.git`로 푸시한 Wiki 커밋 하나

- [ ] **단계 1: 메인 저장소를 건드리지 않고 현재 Wiki를 복제하고 검사한다**

  고유 이름의 시스템 임시 디렉터리에 Wiki를 복제하고, 추적된 파일과 최근 커밋을 나열하며, 비교를 위해 기존 페이지 내용을 보존한다.

  예상: 파일을 교체하거나 병합하기 전에 복제가 성공하고 기존 Wiki 상태를 파악한다.

- [ ] **단계 2: 공통 원본 및 상태 섹션을 갖춘 모든 페이지를 작성한다**

  각 상세 페이지에는 다음이 있어야 한다.

  1. `이 페이지에서 알 수 있는 것`
  2. 주제 설명과 탐색
  3. `공식 기준 문서`
  4. `페이지 상태`
  5. `마지막 확인일: 2026-07-23`

  `Home.md`는 세 독자 경로를 모두 노출해야 한다. `_Sidebar.md`는 승인된 여섯 그룹을 순서대로 사용해야 한다. `_Footer.md`는 저장소와 README에 연결하고 `Wiki는 안내 문서이며 제품·정책의 정본은 메인 저장소입니다.`라고 명시해야 한다.

- [ ] **단계 3: Wiki 페이지 목록과 내부 링크를 검증한다**

  임시 복제본에 이 정보 아키텍처에 필요한 정확히 12개 콘텐츠 페이지와 `_Sidebar.md`, `_Footer.md`가 있는지 검증한다. 상대 Wiki 링크를 파싱하고 모든 페이지 이름 대상이 해당 `.md` 파일로 해석되는지 검증한다.

  예상: 필요한 14개 파일이 모두 존재하고 모든 내부 링크가 해석된다.

- [ ] **단계 4: 메인 저장소 링크와 정책 복사 제약을 검증한다**

  모든 `https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/blob/<default-branch>/...` 링크를 파싱하고, 경로를 로컬 메인 저장소에 매핑한 뒤 URL 디코드하여 각 대상이 존재하는지 검증한다. Wiki 변경 사항을 검토하여 변경 가능한 정책 상태 표, 근거 없는 명령, 꾸며낸 구현 완료 주장이 없는지 확인한다.

  예상: 깨진 저장소 링크가 0개이고 중복된 정책 상태 레지스트리가 없다.

- [ ] **단계 5: Wiki 파일만 커밋한다**

  임시 Wiki 복제본에서 실행:

  ```powershell
  git status --short
  git diff --check
  git add -- Home.md Project-Overview.md Core-User-Journeys.md Getting-Started-for-Developers.md Architecture-Guide.md Domain-Guide.md Policy-Guide.md Documentation-Map.md Decision-and-Change-History.md Team-Workflow.md Glossary.md FAQ.md _Sidebar.md _Footer.md
  git diff --cached --name-only
  git commit -m "docs: build MiriYum project wiki"
  ```

  예상: 정확히 14개 Wiki 파일이 스테이징되고, 공백 검증이 성공하며, Wiki 커밋 하나가 생성된다.

- [ ] **단계 6: Wiki 커밋을 게시한다**

  실행:

  ```powershell
  git push origin HEAD:master
  ```

  예상: 원격이 커밋을 수락한다. 수락하지 않으면 임시 복제본을 유지하고 성공을 주장하지 않은 채 정확한 실패와 복구 경로를 보고한다.

### 작업 5: 게시된 경로와 최종 변경 격리를 검증한다

**파일:**
- 검증: `README.md`
- 검증: 게시된 14개 Wiki 파일
- 보존: 기존 사용자 변경 전체

**인터페이스:**
- 입력: 커밋된 README와 푸시된 Wiki 리비전
- 출력: 로컬 링크, 원격 Wiki 경로, 독자 경로, 커밋, 제외 변경이 올바르다는 증거

- [ ] **단계 1: 원격에서 Wiki 리비전을 다시 가져온다**

  임시 Wiki 복제본에서 `git fetch origin`을 실행하고 로컬 Wiki 커밋을 `origin/master`와 비교한다.

  예상: 두 커밋 ID가 일치한다.

- [ ] **단계 2: 게시된 Home과 독자 경로를 점검한다**

  게시된 `Home`을 열거나 요청한 다음, 다음 경로가 성공적으로 응답하고 다음 페이지로 연결되는지 검증한다.

  - 외부 방문자: `Home` → `Project-Overview` → `Core-User-Journeys`
  - 신규 개발자: `Home` → `Getting-Started-for-Developers` → `Architecture-Guide` → `Domain-Guide`
  - 현재 팀원: `Home` → `Policy-Guide` → `Documentation-Map` → `Team-Workflow`

  예상: 게시된 Wiki에서 Home과 모든 경로 페이지를 사용할 수 있고 Sidebar와 Footer 탐색이 있다.

- [ ] **단계 3: 최종 메인 저장소 검사를 다시 실행한다**

  실행:

  ```powershell
  git diff --check
  git status --short --branch
  git show --stat --oneline HEAD
  git show --stat --oneline HEAD~1
  ```

  예상: 계획과 README 커밋은 파일 범위가 격리되어 있고, 두 사용자 변경은 이 작업으로 커밋되지 않았으며 변경되지 않았고, 메인 저장소에는 `wiki/` 디렉터리가 없다.

- [ ] **단계 4: 정확한 결과를 보고한다**

  생성 또는 수정된 메인 저장소 파일, 메인 커밋 ID, Wiki 커밋 ID와 푸시 결과, 링크 검증 결과, 게시 경로 결과, 보존된 사용자 변경을 보고한다. 검증을 통과하지 않은 원격 작업은 성공으로 표현하지 않는다.
