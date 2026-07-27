# JWT·Valkey 인증과 통합 검색 정책 정렬 구현 계획

> **에이전트 작업자 필수 지침:** 작업별 실행에는 `superpowers:subagent-driven-development` 사용을 권장하며, 같은 세션에서 직접 실행할 때는 `superpowers:executing-plans`를 사용한다. 모든 단계는 확인란으로 추적한다.

**목표:** 승인된 JWT·Valkey 인증과 하나의 검색창·규칙 우선·AI 보조·MySQL 직접 조회 결정을 정책, 아키텍처, 계약, ADR과 중앙 변경 기록에 일관되게 반영한다.

**구조:** 현재 적용할 규칙은 서비스 정책이, 현재 기술 구성은 시스템 아키텍처가, 기술 선택 이유와 대안 비교는 결정별 ADR이 소유한다. 중앙 변경 기록은 결정자·수정자·날짜·대상·짧은 이유와 ADR 링크만 남기며 상세 비교를 복제하지 않는다.

**기술 구성:** Spring Security, JWT, Valkey 8.1.x, Spring Data Redis, Lettuce, MySQL, Spring AI

## 공통 제약

- 모든 새 설명 문장과 제목은 한글로 작성한다. `JWT`, `Valkey`, `Spring Security`, `MySQL`, 파일명과 코드 식별자처럼 번역하면 부정확해지는 고유 기술명만 원문을 유지한다.
- 정책 결정 상태와 MVP 포함 여부를 분리한다. `확정 + MVP 제외`를 `TODO`로 바꾸지 않는다.
- 토큰 수명·브라우저 저장 방식처럼 사용자가 아직 결정하지 않은 현재 MVP 항목은 임의 확정하지 않고 `팀원 상의 필요` 또는 `변경 필요`로 드러낸다.
- `miriyum-service-blueprint.md`는 역사적 입력이므로 현재 기술 기준에 맞추기 위해 수정하지 않는다.
- 작업 허용 목록 밖의 파일과 기존 사용자 변경은 수정·단계 추가·커밋하지 않는다.

## 확인란 의미와 남은 정책 결정

완료 확인란은 이 계획의 문서 정렬·검증·커밋 단계가 실행됐다는 뜻이다. 다음 `AUTH-007` 세부가 결정됐다는 뜻은 아니며, 이 6건은 현재 MVP 구현 전에 팀원 상의가 필요하다.

- 액세스 토큰 수명
- 역할별 리프레시 토큰 수명
- 브라우저 전달·저장 방식과 CSRF 경계
- 동시 로그인 허용 수
- 비밀번호·이메일 변경 시 세션 폐기 범위
- 만료 직전 민감 작업 재검증 범위

---

### 작업 1: 기술 결정 근거를 ADR로 분리

**파일**

- 생성: `docs/adr/ADR-006-jwt-valkey-refresh-token.md`
- 생성: `docs/adr/ADR-007-unified-search-mysql.md`
- 수정: `docs/adr/ADR-002-staged-technology-adoption.md`

**연결**

- 입력: `docs/superpowers/specs/2026-07-27-jwt-valkey-unified-search-design.md`
- 출력: 인증과 검색 정책에서 링크할 기술 선택 근거의 단일 원본

- [x] **1단계: ADR-006에 인증 결정과 대안 비교 작성**

다음 내용을 한글로 작성한다.

```text
결정:
- 모든 역할의 인증 API에 액세스 JWT 사용
- 액세스 토큰 정상 목록은 서버에 저장하지 않음
- 리프레시 토큰 해시와 로그인 단위·토큰 계열·만료·폐기·교체 상태를 Valkey에 저장
- 매 갱신 회전, 폐기 토큰 재사용 시 계열 전체 폐기

비교 대안:
- 완전 무상태 리프레시 JWT
- MySQL 리프레시 상태
- Spring Session Data Redis
- 외부 인증 제공자 또는 독립 인증 서버
```

- [x] **2단계: ADR-007에 검색 결정과 대안 비교 작성**

다음 내용을 한글로 작성한다.

```text
결정:
- 검색창 하나
- 키워드와 구조화 조건을 배타 분류하지 않고 함께 추출
- 규칙 우선, 미해석 표현만 Spring AI 외부 연동
- AI는 허용된 조건만 제안하고 결과 매장을 생성하지 않음
- 최종 조회는 MySQL
- OpenSearch와 Meilisearch는 MVP 제외 확정

비교 대안:
- 일반 검색과 자연어 검색의 별도 모드
- OpenSearch 읽기 모델
- Meilisearch 읽기 모델
- AI 직접 결과 생성
- 순수 의미·벡터 검색
```

- [x] **3단계: ADR-002에 후속 결정 연결**

`Redis` 일반 후보가 `ADR-006`의 Valkey 도입으로 구체화됐고, 별도 검색 엔진은 `ADR-007`에 따라 MVP 제외를 유지한다는 후속 결정 문단을 추가한다. 기존 단계적 도입 원칙과 과거 결정 이유는 삭제하지 않는다.

- [x] **4단계: ADR 형식과 링크 검증**

실행:

```powershell
git diff --check
rg -n "상태: Accepted|결정일: 2026-07-27|검토한 대안|긍정적 결과|부정적 결과|재검토 조건|관련 문서" docs/adr/ADR-006-jwt-valkey-refresh-token.md docs/adr/ADR-007-unified-search-mysql.md
```

기대 결과: 두 ADR에 필수 절이 모두 존재하고 공백 오류가 없다.

- [x] **5단계: ADR 변경 커밋**

```powershell
git add -- docs/adr/ADR-002-staged-technology-adoption.md docs/adr/ADR-006-jwt-valkey-refresh-token.md docs/adr/ADR-007-unified-search-mysql.md
git commit -m "docs: 인증과 검색 기술 선택 근거 기록"
```

---

### 작업 2: 인증 정책과 아키텍처 정렬

**파일**

- 수정: `docs/service-policies/01-member-auth.md`
- 수정: `docs/04-user-flows.md`
- 수정: `docs/05-functional-requirements.md`
- 수정: `docs/06-system-architecture.md`
- 수정: `docs/07-data-and-api-contracts.md`
- 수정: `docs/service-policies/18-scale-reliability.md`

**연결**

- 입력: `ADR-006`
- 출력: `AUTH-007` 현재 정책, 인증 사용자 흐름, MVP 기능 경계, 인증·Valkey 책임과 계약 소유 경계

- [x] **1단계: AUTH-007 현재 정책을 JWT·Valkey 구조로 교체**

활성 정책 본문에서 Spring Session 중앙 세션과 브라우저 JWT 미사용 결정을 제거하고 다음을 명시한다.

```text
- 액세스 JWT는 서명·만료·발급자·대상·최소 클레임을 검증
- 정상 액세스 토큰 목록을 Valkey에서 매 요청 조회하지 않음
- 리프레시 토큰 원문이 아닌 해시와 최소 상태를 Valkey에 저장
- 매 갱신 회전
- 폐기·교체 토큰 재사용 시 토큰 계열 폐기와 AUTH-012 연계
- 로그아웃·계정 정지·권한 회수·개별/전체 로그인 종료 시 리프레시 상태 폐기
- Valkey 장애에는 로그인·갱신을 실패 폐쇄하되 기존 액세스 토큰 요청은 정해진 검증 경계 적용
```

2026-07-21의 세션 결정 기록은 역사로 보존하고, 2026-07-27 변경 기록이 이를 대체한다고 추가한다.

- [x] **2단계: 미결정 현재 MVP 토큰 세부값 노출**

`AUTH-007` 안에 다음 항목을 `팀원 상의 필요`로 표시한다.

```text
- 액세스 토큰 만료시간
- 역할별 리프레시 토큰 유효기간
- 브라우저 전달·저장 방식과 CSRF 경계
- 동시 로그인 상한 유지 여부
- 정상 비밀번호·이메일 변경 시 다른 로그인 유지 여부
- 액세스 토큰 만료 전 중앙 재검증이 필요한 중요 명령 범위
```

- [x] **3단계: 사용자 흐름과 기능 요구사항 갱신**

로그인 성공 종료를 액세스 JWT 발급과 갱신 가능한 리프레시 로그인 상태로 설명하고, 회원·인증 MVP 경계에 Spring Security·액세스 JWT·리프레시 토큰·Valkey 상태 관리를 명시한다.

- [x] **4단계: 시스템 아키텍처와 데이터·API 소유 경계 갱신**

초기 확정 기술에 Valkey 8.1.x, Spring Data Redis와 Lettuce를 추가한다. Redis 일반 후보 문구를 제거하고 Valkey의 리프레시 토큰·속도 제한·캐시·임시 선점·실시간 전달 책임을 활성 현재형으로 기록한다. 정확한 인증 헤더·쿠키 계약은 `AUTH-007`의 남은 결정을 받은 뒤 기능 명세에서 확정한다고 적어 미결정 값을 추측하지 않는다.

- [x] **5단계: 신뢰성 정책의 Valkey 책임 정렬**

`SCALE-006`에서 Valkey를 조건부 후보가 아닌 현재 선택된 보조 상태 저장소로 바꾸되 MySQL 업무 원장을 대체하지 않는다고 유지한다. OpenSearch와 Kafka의 조건부 상태는 이 작업에서 활성화하지 않는다.

- [x] **6단계: 인증 정합성 검증**

실행:

```powershell
git diff --check
rg -n "액세스 JWT|리프레시 토큰|Valkey|회전|재사용" docs/service-policies/01-member-auth.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/18-scale-reliability.md
rg -n "브라우저 인증에는 액세스 JWT나 리프레시 JWT를 사용하지 않는다|Spring Session Data Redis를 사용한 중앙 서버 세션 방식" docs/service-policies/01-member-auth.md
```

기대 결과: 첫 검색은 현재 정책과 연계 문서에서 일치하고, 두 번째 검색은 역사적 결정 표 이외의 활성 정책 본문에서 결과가 없어야 한다.

- [x] **7단계: 인증 정렬 커밋**

```powershell
git add -- docs/service-policies/01-member-auth.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/18-scale-reliability.md
git commit -m "docs: JWT와 Valkey 인증 정책 정렬"
```

---

### 작업 3: 통합 검색 정책과 MySQL 조회 정렬

**파일**

- 수정: `docs/service-policies/13-ad-recommendation.md`
- 수정: `docs/04-user-flows.md`
- 수정: `docs/05-functional-requirements.md`
- 수정: `docs/06-system-architecture.md`
- 수정: `docs/07-data-and-api-contracts.md`
- 수정: `docs/service-policies/18-scale-reliability.md`

**연결**

- 입력: `ADR-007`
- 출력: 하나의 검색창, 규칙 우선·AI 보조 조건 해석, MySQL 직접 조회와 검색 엔진 MVP 제외 경계

- [x] **1단계: 검색 정책을 통합 입력 구조로 교체**

`ADS-007`·`ADS-008`의 현재 검색 계약에 다음을 기록한다.

```text
- 일반 검색과 자연어 검색에 같은 입력창 사용
- 키워드와 구조화 조건을 한 입력에서 함께 추출
- 숫자·단위·시각·지역·카테고리·태그는 규칙 우선
- 미해석 표현만 외부 AI에 전달
- AI는 허용된 검색 조건만 반환
- 실패 시 규칙 결과 또는 키워드 검색으로 축소
- 키워드와 조건을 병합해 MySQL 조회
- 표시·예약·홀드 전에 최신 MySQL 상태 재검증
```

- [x] **2단계: OpenSearch·Meilisearch 경계 수정**

OpenSearch 조회와 검색 인덱스 동기화를 현재 필수 흐름에서 제거한다. 두 검색 엔진을 `확정 + MVP 제외`로 명시하고 오타·자동완성·전문 검색 품질 또는 검색 부하의 측정 결과가 기준을 넘을 때 ADR로 재검토하도록 한다.

- [x] **3단계: 사용자 흐름·기능 요구사항·API 소유 경계 정렬**

식당 탐색 흐름에 하나의 입력창, 규칙 해석, 선택적 AI 보조, MySQL 조회와 실패 시 키워드 축소를 요약한다. 기능 요구사항은 자연어·키워드 통합 검색을 초기 기능으로 유지하고 OpenSearch·Meilisearch를 초기 필수 기술로 해석하지 않게 한다. 데이터·API 계약에는 통합 검색 조건의 정확한 필드·열거형·범위가 기능 명세에서 확정된다는 소유 경계를 추가한다.

- [x] **4단계: 아키텍처·신뢰성 문서에서 검색 인덱스 의존 제거**

시스템 아키텍처는 MySQL 직접 검색을 현재 경로로 기록하고 OpenSearch·Meilisearch를 초기 제외 기술로 둔다. 신뢰성 정책의 `검색 인덱스 버전`, `재색인`, `OpenSearch 우회`처럼 활성 검색 엔진을 전제한 현재형 표현을 MySQL 조회·짧은 Valkey 파생 캐시의 무효화·원본 재조회 경계로 바꾼다.

- [x] **5단계: 검색 정합성 검증**

실행:

```powershell
git diff --check
rg -n "하나의 검색창|규칙|외부 AI|MySQL|MVP 제외" docs/service-policies/13-ad-recommendation.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/18-scale-reliability.md
rg -n "반환값.*OpenSearch를 조회|OpenSearch는 조건 추출기가 아니라|검색 인덱스 버전|재색인" docs/service-policies/13-ad-recommendation.md docs/service-policies/18-scale-reliability.md
```

기대 결과: 첫 검색은 통합 검색과 MySQL 책임을 보여 주고, 두 번째 검색은 활성 현재형 정책에서 결과가 없어야 한다.

- [x] **6단계: 검색 정렬 커밋**

```powershell
git add -- docs/service-policies/13-ad-recommendation.md docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/18-scale-reliability.md
git commit -m "docs: 통합 자연어 검색을 MySQL 경로로 정렬"
```

---

### 작업 4: 중앙 변경 이력과 최종 검증

**파일**

- 수정: `miriyum-service-decisions.md`
- 수정: `docs/superpowers/specs/2026-07-27-jwt-valkey-unified-search-design.md`

**연결**

- 입력: 작업 1~3의 실제 커밋과 변경 파일
- 출력: 변경 추적 카드, 한글화된 승인 설계 기록과 최종 문서 정합성 증거

- [x] **1단계: 중앙 변경 기록 카드 두 개 추가**

긴 대안 비교를 복제하지 않고 다음 정보만 남긴다.

```text
DOC-20260727-006 · JWT·Valkey 인증 구조 확정
- 요청·결정자: 이병우
- 수정 작업자: Codex
- 대상: AUTH-007, 인증 흐름·아키텍처·계약, ADR-006
- 짧은 이유: JWT MVP 요구를 충족하면서 로그아웃·권한 회수·탈취 재사용을 중앙 통제하고 이미 운영할 Valkey를 재사용

DOC-20260727-007 · 하나의 검색창과 MySQL 직접 조회 확정
- 요청·결정자: 이병우
- 수정 작업자: Codex
- 대상: ADS-007·ADS-008, 탐색 흐름·아키텍처·계약, ADR-007
- 짧은 이유: 혼합 입력을 배타 분류하지 않고 AI 의존과 별도 검색 인덱스 운영 복잡도를 제한
```

추적 정보에는 PR #23과 실제 생성된 커밋 식별자를 기록한다.

- [x] **2단계: 설계 기록의 한글 표현 검토**

고유 기술명·파일명·코드 식별자를 제외한 설명 문장에 불필요한 영어 표현이 남지 않게 고친다. 토큰 종류, 대체 처리, 클레임, 토큰 계열과 전송 계약을 설명하는 일반 용어는 한글 표기로 통일한다.

- [x] **3단계: 전체 변경 범위 검사**

실행:

```powershell
git status --short
git diff --check
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

기대 결과: 허용 목록의 문서만 새로 변경됐고 공백 오류가 없다.

- [x] **4단계: 현재 정책과 MVP 상태 교차 검사**

실행:

```powershell
rg -n "Spring Session Data Redis 중앙 서버 세션|브라우저 JWT 미사용|OpenSearch를 조회|Meilisearch를 추가하지 않는다" docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/01-member-auth.md docs/service-policies/13-ad-recommendation.md docs/service-policies/18-scale-reliability.md
rg -n "액세스 JWT|Valkey.*리프레시 토큰|하나의 검색창|MySQL.*조회|OpenSearch.*MVP 제외|Meilisearch.*MVP 제외" docs/04-user-flows.md docs/05-functional-requirements.md docs/06-system-architecture.md docs/07-data-and-api-contracts.md docs/service-policies/01-member-auth.md docs/service-policies/13-ad-recommendation.md docs/service-policies/18-scale-reliability.md docs/adr
```

기대 결과: 첫 검색은 역사적 변경 기록을 제외한 활성 현재형 문구에서 결과가 없고, 두 번째 검색은 각 정본과 ADR의 새 결정을 찾는다.

- [x] **5단계: 문서 링크와 소유권 검토**

실행:

```powershell
rg -n "ADR-006-jwt-valkey-refresh-token.md|ADR-007-unified-search-mysql.md" docs miriyum-service-decisions.md
```

기대 결과: 정책·아키텍처·중앙 변경 기록이 상세 이유를 복제하지 않고 해당 ADR을 연결한다.

- [x] **6단계: 최종 변경 커밋**

```powershell
git add -- miriyum-service-decisions.md docs/superpowers/specs/2026-07-27-jwt-valkey-unified-search-design.md
git commit -m "docs: 인증과 검색 결정 변경 이력 정리"
```

- [x] **7단계: 브랜치 최종 상태 확인**

실행:

```powershell
git status --short --branch
git log -5 --oneline --decorate
```

기대 결과: 작업 트리가 깨끗하고 인증·검색 관련 커밋이 현재 문서 PR 브랜치에 순서대로 존재한다.
