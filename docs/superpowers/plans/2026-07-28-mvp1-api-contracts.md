# 1차 MVP API 계약 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 승인된 1~3단계 결과와 C-001~C-013을 공통·기능별 OpenAPI와 기능 명세로 구체화하고 고정 버전 도구로 통합 검증한다.

**Architecture:** 공통 계약은 `mvp1-common/openapi.yaml`이 재사용 component로 소유하고, 네 기능은 각자의 `spec.md`와 `openapi.yaml`에서 공개 HTTP 계약을 단독 관리한다. `mvp1-openapi.yaml`은 기능별 Path Item을 조립하는 검증 진입점이며 기능 정책을 복제하지 않는다.

**Tech Stack:** OpenAPI 3.1.0, JSON Schema Draft 2020-12, YAML 1.2, Redocly CLI 2.35.1, Markdown

## Global Constraints

- 기준 브랜치와 격리 작업 위치는 `codex/mvp1-contract-docs` 안전 worktree다.
- 사용자 원본 checkout의 변경은 수정·stage·commit하지 않는다.
- 1차 MVP에 결제·환불·체크인·노쇼·플랫폼 운영자·Valkey·카카오 로그인·지도 고도화·추천을 포함하지 않는다.
- 공개 JSON ID는 문자열이며 내부 PK 전략을 API에서 노출하지 않는다.
- 모든 시간은 `Asia/Seoul`, 날짜는 `YYYY-MM-DD`, 현지 시각은 `HH:mm`, 구간은 `[start, end)`다.
- 상태 변경 명령은 UUID `Idempotency-Key`를 사용한다.
- Java 공통 응답은 `ApiResponse<T>`를 유지하고 OpenAPI의 성공 `data`는 구체 스키마로 선언한다.
- 미확정 사업자 업종 매핑 문자열, 쿠키 이름, 보관 기간과 뒤 단계 상태는 추측하지 않는다.
- 각 기능 명세에 추천안, 대안, 선택 이유와 기존 정책 연결을 남긴다.

---

### Task 1: 공통 OpenAPI와 쉬운 작성 안내

**Files:**
- Create: `docs/specs/mvp1-common/openapi.yaml`
- Create: `docs/specs/mvp1-common/openapi-guide.md`
- Create: `redocly.yaml`
- Modify: `docs/specs/mvp1-common/spec.md`

**Interfaces:**
- Consumes: C-001~C-013
- Produces: `PublicId`, 날짜·시간, 성공 코드·메시지, 오류, 페이지, 멱등성 헤더, Bearer 인증 component

- [x] **Step 1:** 공통 component 이름과 외부 `$ref` 경로를 작성한다.
- [x] **Step 2:** 단건·목록·페이지·생성·본문 없는 성공 응답의 복사형 예시를 안내서에 작성한다.
- [x] **Step 3:** Redocly 2.35.1, `recommended-strict`, telemetry off 설정을 작성한다.
- [x] **Step 4:** 최초 기능 문서가 공통 component를 참조하도록 한 뒤 lint로 실제 해석을 검증한다.

### Task 2: 인증·계정·마이페이지 계약

**Files:**
- Create: `docs/specs/auth-account/spec.md`
- Create: `docs/specs/auth-account/openapi.yaml`

**Interfaces:**
- Consumes: D-002~D-007, C-001~C-013, S-006, O-001·O-007·O-009
- Produces: 계정 유형별 가입·로그인·재발급·로그아웃, 본인 정보, 마이페이지 예약 내역 API

- [x] **Step 1:** 소비자와 매장 운영자의 물리 계정·인증 namespace를 경로에서도 분리한다.
- [x] **Step 2:** Access JWT 본문 전달, namespace별 Refresh 쿠키, CSRF와 `401/403/404` 계약을 작성한다.
- [x] **Step 3:** 마이페이지 예약 내역은 `auth-account`가 경로를 관리하고 예약 DTO·상태·필터는 예약 도메인 계약을 참조하도록 작성한다.
- [x] **Step 4:** 기능 OpenAPI를 독립 lint한다.

### Task 3: 매장·검색·운영 기본 계약

**Files:**
- Create: `docs/specs/store-search/spec.md`
- Create: `docs/specs/store-search/openapi.yaml`

**Interfaces:**
- Consumes: STORE-002~STORE-014의 1차 범위, OPER-002~OPER-010의 1차 범위, S-005·S-007, O-002·O-007
- Produces: 공개 매장 탐색, 상세·메뉴·가용성 조합, 운영자 매장·영업시간·접수 시간대·메뉴 관리 API

- [x] **Step 1:** 서울·부산·대구·대전·광주와 텍스트 검색·카테고리·가용성 필터를 명시한다.
- [x] **Step 2:** 조회 가용성은 예약·메뉴 수량 소유 도메인의 공개 조회 결과를 조합하도록 명시한다.
- [x] **Step 3:** 매장 운영자 소속 검증과 입점·운영·픽업 자격 상태 축을 분리한다.
- [x] **Step 4:** 기능 OpenAPI를 독립 lint한다.

### Task 4: 일반 예약 계약

**Files:**
- Create: `docs/specs/reservation/spec.md`
- Create: `docs/specs/reservation/openapi.yaml`

**Interfaces:**
- Consumes: RES-001~RES-015의 1차 범위, S-001~S-003, E-003·E-005, O-003·O-005
- Produces: 예약 생성·조회·취소·방문 완료, 운영자 예약 조회·취소, 수용량 관리 API

- [x] **Step 1:** 예약과 선택 메뉴 홀드를 하나의 생성 명령·트랜잭션으로 작성한다.
- [x] **Step 2:** 인원 수와 팀 수를 모든 점유 구간에서 함께 검증하고 중복 예약을 차단한다.
- [x] **Step 3:** 일반 사용자 취소와 운영자 취소는 같은 예약 조정자가 자원 복구까지 원자적으로 수행하도록 작성한다.
- [x] **Step 4:** 결제·변경·체크인·노쇼 상태와 API가 없음을 검증한다.
- [x] **Step 5:** 기능 OpenAPI를 독립 lint한다.

### Task 5: 메뉴 홀드·수량·픽업 계약

**Files:**
- Create: `docs/specs/menu-hold-pickup/spec.md`
- Create: `docs/specs/menu-hold-pickup/openapi.yaml`

**Interfaces:**
- Consumes: HOLD-001~HOLD-010의 1차 범위, S-003~S-005, E-004·E-005, O-004·O-006
- Produces: 메뉴 홀드 가능 수량, 운영자 재고 구간 관리·품절, 픽업 생성·조회·취소·수령 완료 API

- [x] **Step 1:** 메뉴 홀드는 일반 예약 생성의 선택 항목이며 별도 사용자 확정 API가 없음을 작성한다.
- [x] **Step 2:** 일반 예약 홀드와 픽업이 같은 시간 구간별 메뉴 재고를 공유하도록 작성한다.
- [x] **Step 3:** 픽업은 별도 거래·상태 기계를 사용하고 방문 예약 수용량을 사용하지 않도록 작성한다.
- [x] **Step 4:** 결제·10분 임시 선점·대체 추천이 1차 계약에 없음을 검증한다.
- [x] **Step 5:** 기능 OpenAPI를 독립 lint한다.

### Task 6: 통합 진입점과 4단계 마감 검증

**Files:**
- Create: `docs/specs/mvp1-openapi.yaml`
- Modify: `docs/specs/mvp1-common/spec.md`

**Interfaces:**
- Consumes: 네 기능 OpenAPI와 공통 component
- Produces: 전체 1차 MVP 공개 계약의 단일 lint·bundle 진입점

- [x] **Step 1:** 기능별 Path Item을 소유 파일에서 통합 진입점으로 `$ref`한다.
- [x] **Step 2:** 네 기능 루트와 통합 루트를 Redocly 2.35.1로 lint한다.
- [x] **Step 3:** 통합 루트를 bundle하고 깨진 참조·중복 operationId·뒤 단계 용어를 검사한다.
- [x] **Step 4:** `git diff --check`, 허용 파일 목록과 원본 checkout 보존 상태를 확인한다.
- [x] **Step 5:** 4단계 결정·검증 이력과 실제 실행 결과를 공통 명세에 기록한다.
- [x] **Step 6:** 승인된 4단계 파일만 stage하고 `docs: define mvp1 api contracts`로 커밋한다.
